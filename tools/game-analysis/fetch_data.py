"""只读采集游戏字典、注册国家及金币聚合，支持分页检查点和离线重建。

ES 按 user_id、prop_id 排序聚合；逐用户处理即可精确去重，无须下载原始记录，
也不必在内存保存全部用户。MySQL 只执行本文件中的参数化 SELECT。
"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import re
import ssl
import time
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
from http.client import IncompleteRead
from pathlib import Path
from typing import Any, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
COIN_INDEX = "sano_wallet_coin_record"
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
MAX_ES_INTEGER = 2**53 - 1
FIELDS = ("consumeTokens", "consumeRecords", "rewardTokens", "rewardRecords",
          "jackpotTokens", "jackpotRecords")
METRICS = (("consume", 21, -1), ("reward", 22, 1), ("jackpot", 26, 1))
RATE_DEFAULTS = {"coin_tokens_per_usd": "10000", "game_margin_rate": "0.04",
                 "anchor_rate": "0.005", "jackpot_pool_rate": "0.005",
                 "service_fee_share": "0.15"}


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON 根节点必须为对象：{path}")
    return value


def parse_time(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("时间必须为 yyyy-MM-dd HH:mm:ss")
    result = datetime.strptime(value, TIME_FORMAT)
    if result.strftime(TIME_FORMAT) != value:
        raise ValueError("时间必须为补零的 yyyy-MM-dd HH:mm:ss")
    return result


def validate_analysis(source: dict[str, Any]) -> dict[str, Any]:
    analysis = dict(source)
    if parse_time(analysis.get("start")) >= parse_time(analysis.get("end")):
        raise ValueError("start 必须早于 end，end 为不含边界")
    zone = analysis.setdefault("es_time_zone", "+08:00")
    # 固定偏移无需额外 tzdata 依赖；日界与 ES range 使用同一偏移。
    if not isinstance(zone, str) or not re.fullmatch(r"[+-](?:0\d|1[0-3]):[0-5]\d|[+-]14:00", zone):
        raise ValueError("es_time_zone 必须是固定偏移，例如 +08:00 或 +00:00")
    for name, default in RATE_DEFAULTS.items():
        value = analysis.get(name, default)
        if not isinstance(value, (str, int)) or isinstance(value, bool):
            raise ValueError(f"{name} 使用十进制字符串配置，不能使用浮点数")
        try:
            number = Decimal(value)
        except InvalidOperation as error:
            raise ValueError(f"{name} 不是有效数字") from error
        if not number.is_finite() or number < 0 or (name == "coin_tokens_per_usd" and number <= 0):
            raise ValueError(f"{name} 不在有效范围内")
        if name != "coin_tokens_per_usd" and number > 1:
            raise ValueError(f"{name} 必须在 0 至 1 之间")
        analysis[name] = str(number)
    margin = Decimal(analysis["game_margin_rate"])
    fee = margin * Decimal(analysis["service_fee_share"])
    profit = margin - Decimal(analysis["anchor_rate"]) - Decimal(analysis["jackpot_pool_rate"]) - fee
    if profit < 0:
        raise ValueError("利润公式计算为负，请核对比例配置")
    analysis["service_fee_rate"], analysis["platform_profit_rate"] = str(fee), str(profit)
    for name, default, maximum in (("es_page_size", 1000, 5000), ("mysql_batch_size", 500, 2000)):
        value = analysis.setdefault(name, default)
        if type(value) is not int or not 1 <= value <= maximum:
            raise ValueError(f"{name} 必须在 1 至 {maximum} 之间")
    return analysis


def load_config(mysql_path: Path, es_path: Path, analysis_path: Path) -> dict[str, Any]:
    mysql, es = read_json(mysql_path), read_json(es_path)
    analysis = validate_analysis(read_json(analysis_path))
    for name in ("host", "database", "username", "password"):
        if not isinstance(mysql.get(name), str) or not mysql[name].strip():
            raise ValueError(f"MySQL {name} 必须填写")
    if mysql["host"] == "your-mysql-host" or mysql["username"] == "readonly_user":
        raise ValueError("请填写 MySQL 真实只读账号")
    port = mysql.setdefault("port", 3306)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("MySQL port 无效")
    for name, default in (("connect_timeout_seconds", 15), ("read_timeout_seconds", 300)):
        value = mysql.setdefault(name, default)
        if type(value) is not int or not 1 <= value <= 3600:
            raise ValueError(f"MySQL {name} 必须在 1 至 3600 之间")
    address = es.get("es_url")
    if not isinstance(address, str):
        raise ValueError("es_url 必须填写")
    address = address.strip().rstrip("/")
    parsed = urlsplit(address)
    if (parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username
            or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}
            or "your-es-host" in address):
        raise ValueError("es_url 必须是 ES 服务地址，不能包含认证信息、路径或查询参数")
    es["es_url"] = address
    username, password = es.setdefault("username", ""), es.setdefault("password", "")
    if (not isinstance(username, str) or not isinstance(password, str)
            or bool(username) != bool(password) or username == "your_username"):
        raise ValueError("ES 用户名密码需同时填写真实值或同时留空")
    timeout = es.setdefault("timeout_seconds", 300)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError("ES timeout_seconds 必须在 1 至 3600 之间")
    if es.get("ca_cert"):
        certificate = Path(es["ca_cert"])
        certificate = certificate if certificate.is_absolute() else es_path.parent / certificate
        if not certificate.is_file():
            raise ValueError("ES ca_cert 文件不存在")
        es["ca_cert"] = str(certificate.resolve())
    return {"mysql": mysql, "es": es, "analysis": analysis}


def identity(config: dict[str, Any]) -> dict[str, Any]:
    mysql = config["mysql"]
    return {"schema_version": 2, "mysql_host": mysql["host"],
            "mysql_port": mysql.get("port", 3306), "mysql_database": mysql["database"],
            "es_url": config["es"]["es_url"], "analysis": config["analysis"]}


def output_paths(tag: str, root: Path = ROOT) -> dict[str, Path]:
    return {"manifest": root / f"game-analysis-manifest-{tag}.json",
            "snapshot": root / f"game-analysis-mysql-{tag}.json",
            "data": root / f"game-analysis-{tag}.json",
            "report": root / f"游戏分析-{tag}.xlsx",
            "sql": root / f"game-analysis-sql-{tag}.sql",
            "parts": root / f".game-analysis-{tag}.parts"}


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def replace_with_retry(temporary: Path, destination: Path) -> None:
    for attempt in range(8):
        try:
            temporary.replace(destination)
            return
        except PermissionError:
            if attempt == 7:
                raise
            time.sleep(min(0.2 * 2**attempt, 1))


def save_json(path: Path, value: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.part")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        replace_with_retry(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


class MySQLReader:
    """仅允许固定参数化 SELECT；将实际发送的 SQL 保存到本地便于核对。"""

    def __init__(self, config: dict[str, Any], sql_path: Path):
        import pymysql
        self.sql_path = sql_path
        self.connection = pymysql.connect(
            host=config["host"], port=config.get("port", 3306), user=config["username"],
            password=config["password"], database=config["database"], charset="utf8mb4",
            autocommit=True, cursorclass=pymysql.cursors.SSDictCursor,
            connect_timeout=config.get("connect_timeout_seconds", 15),
            read_timeout=config.get("read_timeout_seconds", 300))

    def query(self, sql: str, parameters: tuple[Any, ...] = ()) -> Iterator[dict[str, Any]]:
        if (not re.match(r"\s*SELECT\b", sql, re.IGNORECASE) or ";" in sql
                or re.search(r"\b(?:INTO|OUTFILE|DUMPFILE|LOCK|SLEEP|BENCHMARK)\b|FOR\s+UPDATE",
                             sql, re.IGNORECASE)):
            raise ValueError("只允许执行单条只读 SELECT 查询")
        with self.connection.cursor() as cursor:
            rendered = cursor.mogrify(sql, parameters)
            with self.sql_path.open("a", encoding="utf-8") as handle:
                handle.write(f"-- {datetime.now().isoformat(timespec='seconds')}\n{rendered.strip()};\n\n")
            cursor.execute(sql, parameters)
            yield from cursor

    def close(self) -> None:
        self.connection.close()


class ESReader:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.context = ssl.create_default_context(cafile=config.get("ca_cert"))

    def search(self, body: dict[str, Any]) -> dict[str, Any]:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.config.get("username"):
            auth = f"{self.config['username']}:{self.config['password']}".encode("utf-8")
            headers["Authorization"] = "Basic " + base64.b64encode(auth).decode("ascii")
        request = Request(f"{self.config['es_url']}/{COIN_INDEX}/_search",
                          data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
        attempts = 3
        for attempt in range(attempts):
            try:
                with urlopen(request, timeout=self.config.get("timeout_seconds", 300),
                             context=self.context if request.full_url.startswith("https:") else None) as response:
                    # tokens 在解析时即保持十进制，避免先经过 Python float。
                    result = json.load(response, parse_float=Decimal)
                if (not isinstance(result, dict) or "error" in result or result.get("timed_out") is True
                        or not isinstance(result.get("_shards"), dict)
                        or result["_shards"].get("failed") != 0):
                    raise ValueError("ES 超时、分片失败或响应格式错误")
                return result
            except HTTPError as error:
                if error.code not in {429, 502, 503, 504} or attempt == attempts - 1:
                    raise ValueError(f"ES HTTP {error.code}") from error
                reason = f"HTTP {error.code}"
            except (URLError, TimeoutError, ConnectionError, IncompleteRead, ssl.SSLEOFError) as error:
                # WinError 10054 / RemoteDisconnected 属于 ConnectionError，
                # 并不一定被 urllib 包装成 URLError；响应读取期间也可能断连。
                # 仅返回完整响应后才由 collect 提交页检查点，重试仍使用同一个 after。
                if attempt == attempts - 1:
                    raise ValueError(
                        f"ES 请求连续 {attempts} 次失败（{type(error).__name__}）；"
                        "已保存分页保留。请检查连接后运行 python .\\export_report.py --resume"
                    ) from error
                reason = type(error).__name__
            delay = 2**attempt
            print(f"ES 请求失败（{reason}），{delay} 秒后重试当前页（{attempt + 2}/{attempts}）")
            time.sleep(delay)
        raise RuntimeError("ES 查询失败")


def fetch_dictionary(reader: MySQLReader) -> dict[str, Any]:
    games = []
    seen = set()
    for row in reader.query("""
        SELECT id, producer_game_id, producer_code, ch_name, en_name,
               status, delete_flag, create_time
        FROM sano_game_list WHERE status = 1 ORDER BY id
    """):
        if row.get("status") != 1:
            raise ValueError("可用游戏查询返回了非 status=1 的游戏")
        game_id = int(row["id"])
        if game_id in seen:
            raise ValueError("游戏字典存在重复 ID")
        seen.add(game_id)
        games.append({"gameId": game_id, "producerGameId": str(row.get("producer_game_id") or ""),
                      "producerCode": str(row.get("producer_code") or ""),
                      "chName": str(row.get("ch_name") or ""), "enName": str(row.get("en_name") or ""),
                      "status": row.get("status"), "deleteFlag": row.get("delete_flag"),
                      "createTime": str(row["create_time"]) if row.get("create_time") else None})
    countries = {}
    for row in reader.query("SELECT country_code_small, country_name_remark FROM sano_config_country"):
        code = str(row.get("country_code_small") or "").strip().upper()
        if not code:
            continue
        name = str(row.get("country_name_remark") or "未匹配国家")
        if code in countries and countries[code] != name:
            raise ValueError(f"国家字典存在名称冲突：{code}")
        countries[code] = name
    return {"capturedAt": datetime.now().isoformat(timespec="seconds"),
            "games": games, "countries": countries}


def fetch_user_countries(reader: MySQLReader, ids: list[int], batch_size: int) -> list[dict[str, Any]]:
    result = []
    for offset in range(0, len(ids), batch_size):
        batch = ids[offset:offset + batch_size]
        allowed = set(batch)
        seen = set()
        sql = f"SELECT id, country_code FROM sano_user WHERE id IN ({','.join(['%s'] * len(batch))})"
        for row in reader.query(sql, tuple(batch)):
            user_id = int(row["id"])
            if user_id not in allowed or user_id in seen:
                raise ValueError("用户国家查询包含重复或范围外用户")
            seen.add(user_id)
            result.append({"userId": user_id,
                           "countryCode": str(row.get("country_code") or "").strip().upper()})
    return result


def jobs(analysis: dict[str, Any]) -> list[dict[str, str]]:
    result = [{"name": "users", "kind": "users", "start": analysis["start"], "end": analysis["end"]}]
    start, end = parse_time(analysis["start"]), parse_time(analysis["end"])
    day = start.replace(hour=0, minute=0, second=0)
    while day < end:
        following = day + timedelta(days=1)
        result.append({"name": "day-" + day.strftime("%Y%m%d"), "kind": "daily",
                       "day": day.strftime("%Y-%m-%d"),
                       "start": max(start, day).strftime(TIME_FORMAT),
                       "end": min(end, following).strftime(TIME_FORMAT)})
        day = following
    return result


def metric_filter(business: int, status: int) -> dict[str, Any]:
    return {"bool": {"filter": [{"term": {"business_type": business}}, {"term": {"status": status}}]}}


def available_game_ids(snapshot: dict[str, Any]) -> list[int]:
    """同一批次固定使用游戏列表快照；status=1 是唯一可用条件。"""
    return sorted(game["gameId"] for game in snapshot["games"] if game["status"] == 1)


def es_query(analysis: dict[str, Any], job: dict[str, Any], after: dict[str, Any] | None = None,
             game_ids: list[int] | None = None) -> dict[str, Any]:
    sources = []
    if job["kind"] == "users":
        sources.append({"userId": {"terms": {"field": "user_id", "missing_bucket": True}}})
    sources.append({"gameId": {"terms": {"field": "prop_id", "missing_bucket": True}}})
    composite = {"size": analysis["es_page_size"], "sources": sources}
    if after is not None:
        composite["after"] = after
    body = {"size": 0, "track_total_hits": False,
            "query": {"bool": {"filter": [
                {"range": {"create_time": {"gte": job["start"], "lt": job["end"],
                                            "time_zone": analysis["es_time_zone"]}}},
                {"bool": {"should": [metric_filter(business, status) for _, business, status in METRICS],
                          "minimum_should_match": 1}}]}},
            "aggs": {"groups": {"composite": composite, "aggs": {
                name: {"filter": metric_filter(business, status),
                       "aggs": {"tokens": {"sum": {"field": "tokens"}}}}
                for name, business, status in METRICS}}}}
    # None 只用于校验旧版全游戏检查点；新版采集始终传快照中的可用 ID。
    if game_ids is not None:
        body["query"]["bool"]["filter"].append(
            {"terms": {"prop_id": game_ids}} if game_ids else {"match_none": {}})
    return body


def exact_integer(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, Decimal, float)):
        raise ValueError(f"{field} 不是整数")
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError(f"{field} 不是有限整数")
    number = Decimal(str(value))
    if not number.is_finite() or number != number.to_integral_value() or not 0 <= number <= MAX_ES_INTEGER:
        raise ValueError(f"{field} 非整数、负数或超过 ES 安全整数范围；请缩短统计区间")
    return int(number)


def key_order(key: dict[str, Any], kind: str) -> tuple[tuple[bool, int], ...]:
    fields = ("userId", "gameId") if kind == "users" else ("gameId",)
    if set(key) != set(fields):
        raise ValueError("ES 聚合键字段不匹配")
    values = []
    for name in fields:
        value = key[name]
        if value is not None and (type(value) is not int or value < 0 or (name == "userId" and value == 0)):
            raise ValueError("ES 用户或游戏 ID 无效")
        values.append((value is not None, value or 0))
    return tuple(values)


def parse_page(response: dict[str, Any], job: dict[str, Any], previous_key: dict[str, Any] | None,
               after: dict[str, Any] | None) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    if ("error" in response or response.get("timed_out") is True
            or response.get("_shards", {}).get("failed") != 0):
        raise ValueError("ES 查询结果不完整")
    groups = response.get("aggregations", {}).get("groups")
    if not isinstance(groups, dict) or not isinstance(groups.get("buckets"), list):
        raise ValueError("ES 缺少 groups 聚合")
    last = key_order(previous_key, job["kind"]) if previous_key is not None else None
    rows = []
    for bucket in groups["buckets"]:
        key = bucket.get("key")
        if not isinstance(key, dict):
            raise ValueError("ES 聚合桶缺少 key")
        order = key_order(key, job["kind"])
        if last is not None and order <= last:
            raise ValueError("ES 聚合桶重复或顺序错误")
        last = order
        row = dict(key)
        for name, _, _ in METRICS:
            metric = bucket.get(name, {})
            row[name + "Tokens"] = exact_integer(metric.get("tokens", {}).get("value"), name + "Tokens")
            row[name + "Records"] = exact_integer(metric.get("doc_count"), name + "Records")
            if row[name + "Records"] == 0 and row[name + "Tokens"]:
                raise ValueError("ES 零记录桶包含非零 tokens")
        if sum(row[name + "Records"] for name, _, _ in METRICS) != exact_integer(bucket.get("doc_count"), "doc_count"):
            raise ValueError("ES 桶的业务分类记录数不一致")
        rows.append(row)
    following = groups.get("after_key")
    if following is not None:
        if not isinstance(following, dict) or not rows:
            raise ValueError("ES 分页键无效")
        order = key_order(following, job["kind"])
        if order < last or (after is not None and order <= key_order(after, job["kind"])):
            raise ValueError("ES 分页键没有前进")
    return rows, following


def verified_pages(paths: dict[str, Path], manifest: dict[str, Any], job: dict[str, Any]) -> Iterator[dict[str, Any]]:
    state = manifest.get("jobs", {}).get(job["name"], {})
    game_ids = manifest["game_ids"] if manifest["identity"]["schema_version"] >= 2 else None
    expected_after, previous_key = None, None
    for number, metadata in enumerate(state.get("pages", []), start=1):
        stem = f"{job['name']}-{number:06d}"
        path, dsl_path = paths["parts"] / f"{stem}.json", paths["parts"] / f"{stem}.dsl.json"
        if (digest(path) != metadata.get("sha256") or digest(dsl_path) != metadata.get("dsl_sha256")):
            raise ValueError(f"检查点校验失败：{path}")
        page = read_json(path)
        if (page.get("job") != job["name"] or page.get("requestAfter") != expected_after
                or len(page.get("rows", [])) != metadata.get("rows")
                or read_json(dsl_path) != es_query(manifest["identity"]["analysis"], job, expected_after, game_ids)):
            raise ValueError("检查点内容或查询条件不匹配")
        for row in page["rows"]:
            key = {name: row[name] for name in (("userId", "gameId") if job["kind"] == "users" else ("gameId",))}
            order = key_order(key, job["kind"])
            if previous_key is not None and order <= key_order(previous_key, job["kind"]):
                raise ValueError("检查点包含重复聚合桶")
            if any(type(row.get(field)) is not int or row[field] < 0 for field in FIELDS):
                raise ValueError("检查点 tokens 或记录数不是非负整数")
            previous_key = key
        expected_after = page["afterKey"]
        if expected_after is None and number < len(state["pages"]):
            raise ValueError("检查点结束后仍有多余页")
        yield page
    if state.get("pages"):
        if state.get("after") != expected_after or state.get("last_key") != previous_key:
            raise ValueError("清单分页状态不一致")
        if bool(state.get("done")) != (expected_after is None):
            raise ValueError("清单结束状态不一致")


def find_resume_paths(config: dict[str, Any], root: Path = ROOT) -> dict[str, Path]:
    matches = []
    legacy = False
    expected = identity(config)
    for path in root.glob("game-analysis-manifest-*.json"):
        manifest = read_json(path)
        if manifest.get("identity") == {**expected, "schema_version": 1}:
            legacy = True
        if manifest.get("identity") == expected and manifest.get("stage") != "report_complete":
            tag = path.name.removeprefix("game-analysis-manifest-").removesuffix(".json")
            matches.append(output_paths(tag, root))
    if len(matches) != 1:
        if not matches and legacy:
            raise ValueError("旧批次为全游戏范围，不能续跑为可用游戏范围。请去掉 --resume 开始新批次；"
                             "已完成旧批次可用 --rebuild-from-checkpoints 按原快照筛选 status=1")
        raise ValueError(f"--resume 需要恰好一个相同配置的未完成批次，当前 {len(matches)} 个")
    return matches[0]


def collect(config: dict[str, Any], paths: dict[str, Path], *, resume: bool = False,
            mysql_reader: MySQLReader | None = None, es_reader: ESReader | None = None) -> Path:
    if resume:
        manifest = read_json(paths["manifest"])
        if manifest.get("identity") != identity(config):
            raise ValueError("续跑配置与原批次不一致")
        if manifest.get("stage") == "report_complete":
            raise ValueError("该批次已完成，请使用离线重建命令")
        if manifest.get("stage") == "json_complete":
            if digest(paths["data"]) != manifest.get("data_sha256"):
                raise ValueError("最终 JSON 校验失败")
            return paths["data"]
    else:
        if any(path.exists() for path in paths.values()):
            raise ValueError("批次文件已存在，拒绝覆盖")
        paths["parts"].mkdir(parents=True)
        manifest = {"identity": identity(config), "stage": "in_progress", "jobs": {}}
        save_json(paths["manifest"], manifest)
    analysis = config["analysis"]
    database = mysql_reader
    try:
        if not manifest.get("snapshot_sha256"):
            database = database or MySQLReader(config["mysql"], paths["sql"])
            save_json(paths["snapshot"], fetch_dictionary(database))
            manifest["snapshot_sha256"] = digest(paths["snapshot"])
            save_json(paths["manifest"], manifest)
        elif digest(paths["snapshot"]) != manifest["snapshot_sha256"]:
            raise ValueError("MySQL 字典快照校验失败")
        game_ids = available_game_ids(read_json(paths["snapshot"]))
        if "game_ids" not in manifest:
            manifest["game_ids"] = game_ids
            save_json(paths["manifest"], manifest)
        elif manifest["game_ids"] != game_ids:
            raise ValueError("清单可用游戏范围与游戏快照不一致")
        reader = es_reader
        for job in jobs(analysis):
            state = manifest["jobs"].setdefault(job["name"], {"pages": [], "after": None, "last_key": None, "done": False})
            for _ in verified_pages(paths, manifest, job):
                pass
            if state["done"]:
                print(f"跳过已完成聚合：{job['name']}")
                continue
            while not state["done"]:
                body = es_query(analysis, job, state["after"], game_ids)
                if game_ids:
                    reader = reader or ESReader(config["es"])
                    rows, following = parse_page(reader.search(body), job, state["last_key"], state["after"])
                    if any(row["gameId"] not in game_ids for row in rows):
                        raise ValueError("ES 返回了可用游戏列表以外的聚合桶")
                else:
                    # 空可用列表产生零结果，绝不能省略过滤而查询全平台游戏。
                    rows, following = [], None
                users = []
                if job["kind"] == "users" and rows:
                    ids = sorted({row["userId"] for row in rows if row["userId"] is not None})
                    if ids:
                        database = database or MySQLReader(config["mysql"], paths["sql"])
                        users = fetch_user_countries(database, ids, analysis["mysql_batch_size"])
                stem = f"{job['name']}-{len(state['pages']) + 1:06d}"
                path, dsl_path = paths["parts"] / f"{stem}.json", paths["parts"] / f"{stem}.dsl.json"
                save_json(dsl_path, body)
                save_json(path, {"job": job["name"], "requestAfter": state["after"],
                                 "afterKey": following, "rows": rows, "users": users})
                state["pages"].append({"sha256": digest(path), "dsl_sha256": digest(dsl_path), "rows": len(rows)})
                state["after"], state["done"] = following, following is None
                if rows:
                    fields = ("userId", "gameId") if job["kind"] == "users" else ("gameId",)
                    state["last_key"] = {name: rows[-1][name] for name in fields}
                save_json(paths["manifest"], manifest)
                print(f"ES 聚合 {job['name']} 第 {len(state['pages'])} 页：{len(rows):,} 桶")
        data = consolidate(paths, manifest)
        save_json(paths["data"], data)
        manifest["stage"], manifest["data_sha256"] = "json_complete", digest(paths["data"])
        save_json(paths["manifest"], manifest)
        return paths["data"]
    finally:
        if database is not None and mysql_reader is None:
            database.close()


def empty_metrics() -> dict[str, Any]:
    return {**dict.fromkeys(FIELDS, 0), "consumerCount": 0,
            "maxConsumerId": None, "maxConsumerTokens": 0, "missingUserConsumeRecords": 0}


def add_metrics(target: dict[str, Any], row: dict[str, Any]) -> None:
    for field in FIELDS:
        target[field] += row[field]


def consolidate(paths: dict[str, Path], manifest: dict[str, Any], *,
                only_available: bool | None = None) -> dict[str, Any]:
    """流式合并有序的用户游戏桶；国家、总体消费人数每位用户只加一次。"""
    version = manifest.get("identity", {}).get("schema_version")
    if version not in {1, 2}:
        raise ValueError("不支持该检查点版本")
    if only_available is None:
        only_available = version >= 2
    analysis = manifest["identity"]["analysis"]
    if digest(paths["snapshot"]) != manifest.get("snapshot_sha256"):
        raise ValueError("字典快照校验失败")
    snapshot = read_json(paths["snapshot"])
    dictionary = {game["gameId"]: game for game in snapshot["games"]}
    allowed = set(available_game_ids(snapshot))
    if version >= 2 and manifest.get("game_ids") != sorted(allowed):
        raise ValueError("清单可用游戏范围与游戏快照不一致")
    if only_available:
        dictionary = {game_id: game for game_id, game in dictionary.items() if game_id in allowed}
    game_totals, countries, country_games, daily = {}, {}, {}, {}
    totals = empty_metrics()
    warnings = {"missingUserCount": 0, "missingUserIdRecords": 0,
                "unknownCountryUserCount": 0, "unmatchedGameCount": 0}

    def game_target(game_id: int | None) -> dict[str, Any]:
        if game_id not in game_totals:
            info = dictionary.get(game_id, {"gameId": game_id, "chName": "未匹配游戏",
                    "enName": "", "producerCode": "", "producerGameId": "",
                    "status": None, "deleteFlag": None, "createTime": None})
            game_totals[game_id] = {**info, **empty_metrics(), "activeDays": 0}
        return game_totals[game_id]

    sentinel = object()
    current_user, current_country, current_found = sentinel, "", False
    current_consume, current_records = 0, 0

    def finish_user() -> None:
        if current_user is sentinel or current_user is None:
            return
        if not current_found:
            warnings["missingUserCount"] += 1
        if not current_country:
            warnings["unknownCountryUserCount"] += 1
        if current_records:
            totals["consumerCount"] += 1
            countries[current_country]["consumerCount"] += 1
            if current_consume > totals["maxConsumerTokens"]:
                totals["maxConsumerTokens"], totals["maxConsumerId"] = current_consume, current_user

    all_jobs = jobs(analysis)
    for job in all_jobs:
        if not manifest.get("jobs", {}).get(job["name"], {}).get("done"):
            raise ValueError(f"聚合作业未完成：{job['name']}")
        for page in verified_pages(paths, manifest, job):
            by_user = {user["userId"]: user["countryCode"] for user in page["users"]}
            if len(by_user) != len(page["users"]):
                raise ValueError("用户国家检查点重复")
            for row in page["rows"]:
                game_id = row["gameId"]
                if version >= 2 and game_id not in allowed:
                    raise ValueError("检查点包含可用列表外的游戏")
                if only_available and game_id not in allowed:
                    continue
                game = game_target(game_id)
                if job["kind"] == "daily":
                    daily[(job["day"], game_id)] = {"day": job["day"], "gameId": game_id,
                                                     **{field: row[field] for field in FIELDS}}
                    if row["consumeRecords"]:
                        game["activeDays"] += 1
                    continue
                user_id = row["userId"]
                country = by_user.get(user_id, "")
                found = user_id in by_user
                if user_id != current_user:
                    finish_user()
                    current_user, current_country, current_found = user_id, country, found
                    current_consume, current_records = 0, 0
                elif country != current_country or found != current_found:
                    raise ValueError("同一用户跨页的注册国家不一致，请重新采集")
                if country not in countries:
                    countries[country] = {"countryCode": country,
                        "countryName": snapshot["countries"].get(country, "未知国家" if not country else "未匹配国家"),
                        **empty_metrics()}
                group = country_games.setdefault((country, game_id),
                    {"countryCode": country, "gameId": game_id, **empty_metrics()})
                for target in (totals, game, countries[country], group):
                    add_metrics(target, row)
                if user_id is None:
                    warnings["missingUserIdRecords"] += sum(row[field] for field in FIELDS if field.endswith("Records"))
                    for target in (totals, game, countries[country], group):
                        target["missingUserConsumeRecords"] += row["consumeRecords"]
                elif row["consumeRecords"]:
                    current_consume += row["consumeTokens"]
                    current_records += row["consumeRecords"]
                    for target in (game, group):
                        target["consumerCount"] += 1
                        if row["consumeTokens"] > target["maxConsumerTokens"]:
                            target["maxConsumerTokens"], target["maxConsumerId"] = row["consumeTokens"], user_id
    finish_user()
    # 零消费游戏仅补齐统计终点前已创建的配置；有实际记录的游戏永远保留。
    for game in dictionary.values():
        if game["createTime"] is None or parse_time(game["createTime"]) < parse_time(analysis["end"]):
            game_target(game["gameId"])
    warnings["unmatchedGameCount"] = sum(game_id not in dictionary for game_id in game_totals)
    daily_totals = {}
    for row in daily.values():
        add_metrics(daily_totals.setdefault(row["gameId"], empty_metrics()), row)
    for game_id, game in game_totals.items():
        day_total = daily_totals.get(game_id, empty_metrics())
        if any(day_total[field] != game[field] for field in FIELDS):
            raise ValueError(f"游戏 {game_id} 的期间聚合与每日聚合不一致；源数据可能变化，请重新采集")
    for collection in (game_totals.values(), countries.values(), country_games.values()):
        values = list(collection)
        if any(sum(row[field] for row in values) != totals[field] for field in FIELDS):
            raise ValueError("汇总金额或记录数不一致")
    if sum(row["consumerCount"] for row in countries.values()) != totals["consumerCount"]:
        raise ValueError("国家去重消费人数与总体不一致")
    return {"schemaVersion": 2 if only_available else 1,
            "meta": {**analysis, "dictionaryCapturedAt": snapshot["capturedAt"],
                     **({"gameScope": "游戏列表快照 status=1（使用中）",
                         "availableGameIds": sorted(allowed)} if only_available else {}),
                     "countryBasis": "sano_user.country_code（固定注册国家）",
                     "sourceIndex": COIN_INDEX, "businessTypes": {"consume": 21, "reward": 22, "jackpot": 26}},
            "totals": totals, "warnings": warnings,
            "games": list(game_totals.values()), "countries": list(countries.values()),
            "countryGames": list(country_games.values()), "dailyGames": list(daily.values())}


def mark_report_complete(paths: dict[str, Path]) -> None:
    manifest = read_json(paths["manifest"])
    if manifest.get("stage") != "json_complete" or not paths["report"].is_file():
        raise ValueError("采集或报表尚未完成")
    manifest["stage"], manifest["report_sha256"] = "report_complete", digest(paths["report"])
    save_json(paths["manifest"], manifest)

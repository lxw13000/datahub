"""只读查询 MySQL 和 ES，按注册用户聚合并保存可续跑检查点。"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import re
import ssl
import time
from collections import defaultdict
from datetime import date, datetime, time as day_time, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
DATE_TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
USER_BATCH_SIZE = 500
ES_PAGE_SIZE = 1_000
MAX_EXACT_ES_INTEGER = 2**53 - 1
COIN_INDEX = "sano_wallet_coin_record"
DIAMOND_INDEX = "sano_wallet_diamond_record"


def parse_time(value: Any, name: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{name} 必须是 yyyy-MM-dd HH:mm:ss")
    try:
        parsed = datetime.strptime(value, DATE_TIME_FORMAT)
    except ValueError as error:
        raise ValueError(f"{name} 必须是 yyyy-MM-dd HH:mm:ss") from error
    if parsed.strftime(DATE_TIME_FORMAT) != value:
        raise ValueError(f"{name} 必须是补零的 yyyy-MM-dd HH:mm:ss")
    return parsed


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"文件不存在：{path}")
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON 根节点必须是对象：{path}")
    return value


def load_configs(mysql_path: Path, es_path: Path, analysis_path: Path) -> dict[str, Any]:
    mysql, es, analysis = load_json(mysql_path), load_json(es_path), load_json(analysis_path)
    for name in ("host", "database", "username", "password"):
        if not isinstance(mysql.get(name), str) or not mysql[name].strip():
            raise ValueError(f"MySQL {name} 必须填写真实值")
    if mysql["host"] == "your-mysql-host" or mysql["username"] == "readonly_user":
        raise ValueError("请先填写真实的 MySQL 只读账号配置")
    port = mysql.get("port", 3306)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("MySQL port 无效")
    for key in ("connect_timeout_seconds", "read_timeout_seconds"):
        value = mysql.get(key, 15 if key.startswith("connect") else 300)
        if type(value) is not int or not 1 <= value <= 3600:
            raise ValueError(f"MySQL {key} 必须是 1–3600 的整数")

    url = es.get("es_url")
    if not isinstance(url, str):
        raise ValueError("es_url 必须是 HTTP(S) 地址")
    url = url.strip().rstrip("/")
    parsed = urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username
            or parsed.password or parsed.query or parsed.fragment or "your-es-host" in url):
        raise ValueError("es_url 无效，请填写不含账号密码的真实 ES 地址")
    es["es_url"] = url
    username, password = es.get("username", ""), es.get("password", "")
    if (not isinstance(username, str) or not isinstance(password, str)
            or bool(username) != bool(password)
            or username == "your_username" or password == "your_password"):
        raise ValueError("ES 账号密码必须同时填写真实值，或同时留空")
    timeout = es.get("timeout_seconds", 300)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError("ES timeout_seconds 必须是 1–3600 的整数")
    ca_cert = es.get("ca_cert")
    if ca_cert is not None:
        if not isinstance(ca_cert, str) or not ca_cert.strip():
            raise ValueError("ca_cert 必须是文件路径")
        certificate = Path(ca_cert)
        es["ca_cert"] = str(certificate if certificate.is_absolute() else es_path.parent / certificate)
        if not Path(es["ca_cert"]).is_file():
            raise ValueError("ca_cert 文件不存在")

    country = analysis.get("country_code")
    if not isinstance(country, str) or not re.fullmatch(r"[A-Za-z]{2,10}", country.strip()):
        raise ValueError("country_code 必须是国家代码")
    analysis["country_code"] = country.strip().upper()
    start = parse_time(analysis.get("register_start"), "register_start")
    end = parse_time(analysis.get("register_end"), "register_end")
    observed = parse_time(analysis.get("observation_end"), "observation_end")
    if not start < end <= observed:
        raise ValueError("时间顺序须满足 register_start < register_end <= observation_end")
    zone = analysis.get("es_time_zone", "+00:00")
    if (not isinstance(zone, str) or not re.fullmatch(
            r"(?:UTC|[+-](?:0\d|1[0-4]):[0-5]\d|[A-Za-z_]+(?:/[A-Za-z0-9_+-]+)+)", zone)):
        raise ValueError("es_time_zone 无效，例如 +00:00 或 Asia/Shanghai")
    for key in ("coin_consumption_types", "coin_reward_types", "diamond_income_types",
                "diamond_reward_types"):
        values = analysis.get(key)
        if (not isinstance(values, list) or not values or any(type(x) is not int or x < 1 for x in values)
                or len(values) != len(set(values))):
            raise ValueError(f"{key} 必须是不重复的正整数数组")
    if set(analysis["coin_consumption_types"]) & set(analysis["coin_reward_types"]):
        raise ValueError("金币消费类型与金币奖励类型不能重叠")
    if not set(analysis["diamond_reward_types"]) <= set(analysis["diamond_income_types"]):
        raise ValueError("钻石奖励类型必须包含在钻石收益类型中")
    return {"mysql": mysql, "es": es, "analysis": analysis}


def identity(config: dict[str, Any]) -> dict[str, Any]:
    mysql, es, analysis = config["mysql"], config["es"], config["analysis"]
    return {
        "mysql_host": mysql["host"], "mysql_port": mysql.get("port", 3306),
        "mysql_database": mysql["database"], "es_url": es["es_url"],
        "analysis": analysis, "collection_version": 3, "user_batch_size": USER_BATCH_SIZE,
        "es_page_size": ES_PAGE_SIZE,
    }


def output_paths(tag: str, root: Path = ROOT) -> dict[str, Path]:
    return {
        "mysql": root / f"country-user-cohort-mysql-{tag}.json",
        "sql": root / f"country-user-cohort-mysql-queries-{tag}.sql",
        "data": root / f"country-user-cohort-{tag}.json",
        "manifest": root / f"country-user-cohort-manifest-{tag}.json",
        "report": root / f"国别注册用户30天分析-{tag}.xlsx",
        "parts": root / f".country-user-cohort-{tag}.parts",
    }


def find_resume_paths(config: dict[str, Any], root: Path = ROOT) -> dict[str, Path]:
    matches = []
    for path in root.glob("country-user-cohort-manifest-*.json"):
        value = load_json(path)
        if value.get("identity") == identity(config) and value.get("stage") != "report_complete":
            tag = path.name.removeprefix("country-user-cohort-manifest-").removesuffix(".json")
            paths = output_paths(tag, root)
            if not paths["report"].exists():
                matches.append(paths)
    if len(matches) != 1:
        raise ValueError(f"--resume 需要恰好一个相同配置的未完成批次，当前找到 {len(matches)} 个")
    return matches[0]


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def replace_with_retry(temporary: Path, path: Path) -> None:
    """在 Windows 短暂占用目标文件时重试原子替换，不删除旧文件。"""
    for attempt in range(8):
        try:
            temporary.replace(path)
            return
        except PermissionError:
            if attempt == 7:
                raise
            time.sleep(min(0.2 * 2**attempt, 1.0))


def save_json(path: Path, data: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.part")
    try:
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # 先写临时文件；重试期间旧清单仍可用于 --resume。
        replace_with_retry(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


class MySQLReader:
    """仅暴露 SELECT；正式环境也应使用数据库只读账号。"""

    def __init__(self, config: dict[str, Any], sql_log_path: Path | None = None):
        try:
            import pymysql
        except ImportError as error:
            raise RuntimeError("缺少 PyMySQL，请安装 requirements.txt") from error
        self.connection = pymysql.connect(
            host=config["host"], port=config.get("port", 3306), user=config["username"],
            password=config["password"], database=config["database"], charset="utf8mb4",
            cursorclass=pymysql.cursors.SSDictCursor, autocommit=True,
            connect_timeout=config.get("connect_timeout_seconds", 15),
            read_timeout=config.get("read_timeout_seconds", 300),
        )
        self.sql_log_path = sql_log_path
        self.query_count = 0

    def query(self, sql: str, parameters: tuple[Any, ...]) -> Iterator[dict[str, Any]]:
        # 所有 SQL 均由本文件固定定义，不能由配置或命令行注入。
        if not sql.lstrip().upper().startswith("SELECT ") or ";" in sql:
            raise ValueError("只允许执行单条 SELECT 查询")
        with self.connection.cursor() as cursor:
            # mogrify 使用 PyMySQL 的实际转义规则，日志与 execute 的参数一致。
            rendered = cursor.mogrify(sql, parameters)
            cursor.execute(sql, parameters)
            self.query_count += 1
            if self.sql_log_path is not None:
                with self.sql_log_path.open("a", encoding="utf-8") as log:
                    log.write(f"-- MySQL SELECT {self.query_count}\n")
                    log.write(rendered.strip() + ";\n\n")
            for row in cursor:
                yield row

    def close(self) -> None:
        self.connection.close()


def date_start(day: date) -> datetime:
    return datetime.combine(day, day_time.min)


def matured(day: date, days: int, observation_end: datetime) -> bool:
    """D7 在 D7 整天结束后成熟；30 天在 D29 整天结束后成熟。"""
    return observation_end >= date_start(day + timedelta(days=days))


def placeholders(count: int) -> str:
    return ",".join(["%s"] * count)


def fetch_mysql_snapshot(reader: MySQLReader, analysis: dict[str, Any]) -> dict[str, Any]:
    users = []
    sql_users = """
        SELECT u.id AS user_id, u.nick, u.country_code, u.create_time,
               u.real_person, i.user_id AS inviter_id
        FROM sano_user u
        LEFT JOIN sano_invite_log i ON i.invite_user_id = u.id
        WHERE u.country_code = %s AND u.create_time >= %s AND u.create_time < %s
        ORDER BY u.create_time, u.id
    """
    for row in reader.query(sql_users, (analysis["country_code"], analysis["register_start"],
                                       analysis["register_end"])):
        registered = row["create_time"]
        if not isinstance(registered, datetime):
            raise ValueError("用户注册时间不是 MySQL DATETIME")
        users.append({
            "userId": int(row["user_id"]), "nick": row["nick"] or "",
            "countryCode": row["country_code"],
            "inviterId": int(row["inviter_id"]) if row["inviter_id"] else None,
            "registerTime": registered.strftime(DATE_TIME_FORMAT),
            "realNameStatus": int(row["real_person"] or 0) == 1,
            "firstRealNameTime": None,
            "d1": None, "d3": None, "d7": None,
            "full30Days": False, "rechargeUsd": "0", "withdrawUsd": "0",
        })
    if len(users) > 1_048_575:
        raise ValueError("用户数超过 Excel 单工作表行数上限，请缩短注册区间")

    cutoff = parse_time(analysis["observation_end"], "observation_end")
    for offset in range(0, len(users), USER_BATCH_SIZE):
        batch = users[offset:offset + USER_BATCH_SIZE]
        ids = [user["userId"] for user in batch]
        id_placeholders = placeholders(len(ids))
        min_day = min(parse_time(u["registerTime"], "registerTime").date() for u in batch)
        max_day = max(parse_time(u["registerTime"], "registerTime").date() for u in batch)
        first_day = date_start(min_day)
        money_end = min(date_start(max_day + timedelta(days=30)), cutoff)

        sql_real = f"""
            SELECT user_id,
                   MIN(CASE WHEN type = 0 THEN create_time ELSE handle_time END) AS first_success
            FROM sano_anchor_real_error
            WHERE user_id IN ({id_placeholders})
              AND (type = 0 OR (handle_status = 1 AND handle_time IS NOT NULL))
            GROUP BY user_id
        """
        real_times = {int(r["user_id"]): r["first_success"] for r in reader.query(sql_real, tuple(ids))}

        # 活跃表主键为 (user_id, day_int)；只有完整结束的目标日才参与留存判定。
        active_days: dict[int, set[int]] = defaultdict(set)
        active_start = min_day + timedelta(days=1)
        active_end = min(max_day + timedelta(days=7), cutoff.date() - timedelta(days=1))
        if active_start <= active_end:
            sql_active = f"""
                SELECT user_id, day_int
                FROM sano_user_active
                WHERE user_id IN ({id_placeholders})
                  AND day_int >= %s AND day_int <= %s
            """
            start_int = int(active_start.strftime("%Y%m%d"))
            end_int = int(active_end.strftime("%Y%m%d"))
            for row in reader.query(sql_active, (*ids, start_int, end_int)):
                active_days[int(row["user_id"])].add(int(row["day_int"]))

        # 充值按提供的渠道口径：0/1 用 money，2 的 USDT 用 usdt_amount，
        # 三方非 USDT 用 money；不把 producer_type=4 用户找币商充值计入总充值。
        recharge_case = """
            CASE
              WHEN producer_type IN (0, 1) THEN COALESCE(money, 0)
              WHEN producer_type = 2 AND coin_business = 0 AND usdt_address IS NULL
                   THEN COALESCE(money, 0)
              WHEN producer_type = 2 AND coin_business = 0 AND usdt_address IS NOT NULL
                   THEN COALESCE(usdt_amount, 0)
              WHEN producer_type = 2 AND coin_business > 0 AND usdt_address IS NULL
                   THEN COALESCE(money, 0)
              WHEN producer_type = 2 AND coin_business > 0 AND usdt_address IS NOT NULL
                   THEN COALESCE(usdt_amount, 0)
              ELSE 0
            END
        """
        sql_recharge = f"""
            SELECT user_id, DATE(create_time) AS event_day,
                   SUM({recharge_case}) AS usd
            FROM sano_wallet_recharge
            WHERE user_id IN ({id_placeholders}) AND status = 2
              AND create_time >= %s AND create_time < %s
            GROUP BY user_id, DATE(create_time)
        """
        recharge: dict[int, dict[date, Decimal]] = defaultdict(dict)
        for row in reader.query(sql_recharge, (*ids, first_day, money_end)):
            recharge[int(row["user_id"])][row["event_day"]] = Decimal(row["usd"] or 0)

        sql_withdraw = f"""
            SELECT user_id, DATE(pay_time) AS event_day,
                   SUM(COALESCE(reality_money, 0)) AS usd
            FROM sano_wallet_withdraw
            WHERE user_id IN ({id_placeholders}) AND status = 4
              AND pay_time >= %s AND pay_time < %s
            GROUP BY user_id, DATE(pay_time)
        """
        withdraw: dict[int, dict[date, Decimal]] = defaultdict(dict)
        for row in reader.query(sql_withdraw, (*ids, first_day, money_end)):
            withdraw[int(row["user_id"])][row["event_day"]] = Decimal(row["usd"] or 0)

        for user in batch:
            user_id = user["userId"]
            first_success = real_times.get(user_id)
            if first_success is not None:
                user["firstRealNameTime"] = first_success.strftime(DATE_TIME_FORMAT)
            day = parse_time(user["registerTime"], "registerTime").date()
            for n in (1, 3, 7):
                if matured(day, n + 1, cutoff):
                    target_day = int((day + timedelta(days=n)).strftime("%Y%m%d"))
                    user[f"d{n}"] = target_day in active_days[user_id]
            user["full30Days"] = matured(day, 30, cutoff)
            last_day = day + timedelta(days=30)
            user["rechargeUsd"] = str(sum(
                (value for event_day, value in recharge[user_id].items()
                 if day <= event_day < last_day), Decimal(0),
            ))
            user["withdrawUsd"] = str(sum(
                (value for event_day, value in withdraw[user_id].items()
                 if day <= event_day < last_day), Decimal(0),
            ))
    return {"meta": {"country_code": analysis["country_code"],
                     "retention_source": "sano_user_active.day_int",
                     "register_start": analysis["register_start"],
                     "register_end": analysis["register_end"],
                     "observation_end": analysis["observation_end"]},
            "users": users}


class ESReader:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.context = ssl.create_default_context(cafile=config.get("ca_cert"))

    def search(self, index: str, body: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.config['es_url']}/{index}/_search"
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.config.get("username"):
            credential = f"{self.config['username']}:{self.config['password']}".encode()
            headers["Authorization"] = "Basic " + base64.b64encode(credential).decode("ascii")
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        for attempt in range(3):
            try:
                request = Request(url, data=payload, headers=headers, method="POST")
                with urlopen(request, timeout=self.config.get("timeout_seconds", 300),
                             context=self.context if url.startswith("https://") else None) as response:
                    root = json.load(response)
                shards = root.get("_shards") if isinstance(root, dict) else None
                if (not isinstance(root, dict) or "error" in root or root.get("timed_out") is True
                        or not isinstance(shards, dict) or shards.get("failed") != 0):
                    raise ValueError("ES 查询超时、分片失败或响应格式错误")
                return root
            except HTTPError as error:
                detail = error.read(500).decode("utf-8", errors="replace")
                if error.code not in {429, 502, 503, 504} or attempt == 2:
                    raise ValueError(f"ES HTTP {error.code}：{detail}") from error
            except (URLError, TimeoutError) as error:
                if attempt == 2:
                    raise ValueError(f"连接 ES 失败：{error}") from error
            time.sleep(2**attempt)
        raise RuntimeError("ES 查询重试失败")


def es_query(analysis: dict[str, Any], index: str, ids: list[int], start: str,
             end: str, after: dict[str, Any] | None = None) -> dict[str, Any]:
    if index == COIN_INDEX:
        allowed = [
            {"bool": {"filter": [{"term": {"status": -1}},
                                 {"terms": {"business_type": analysis["coin_consumption_types"]}}]}},
            {"bool": {"filter": [{"term": {"status": 1}},
                                 {"terms": {"business_type": analysis["coin_reward_types"]}}]}},
        ]
    elif index == DIAMOND_INDEX:
        allowed = [{"bool": {"filter": [{"term": {"status": 1}},
                                      {"terms": {"business_type": analysis["diamond_income_types"]}}]}}]
    else:
        raise ValueError("不允许查询其他 ES 索引")
    composite: dict[str, Any] = {"size": ES_PAGE_SIZE, "sources": [
        {"userId": {"terms": {"field": "user_id"}}},
        {"businessType": {"terms": {"field": "business_type"}}},
        {"status": {"terms": {"field": "status"}}},
    ]}
    if after:
        composite["after"] = after
    return {"size": 0, "track_total_hits": False,
            "query": {"bool": {"filter": [
                {"terms": {"user_id": ids}},
                {"range": {"create_time": {"gte": start, "lt": end,
                                            "time_zone": analysis["es_time_zone"]}}},
            ], "should": allowed, "minimum_should_match": 1}},
            "aggs": {"groups": {"composite": composite,
                                "aggs": {"tokens": {"sum": {"field": "tokens"}}}}}}


def exact_nonnegative(value: Any, field: str) -> int:
    if type(value) is int:
        result = value
    elif type(value) is float and math.isfinite(value) and value.is_integer():
        result = int(value)
    else:
        raise ValueError(f"ES 聚合 {field} 不是整数：{value!r}")
    if not 0 <= result <= MAX_EXACT_ES_INTEGER:
        raise ValueError(f"ES 聚合 {field} 超出安全整数范围；请缩短注册区间或 ES 聚合时间段")
    return result


def es_jobs(users: list[dict[str, Any]], cutoff: datetime) -> list[dict[str, Any]]:
    grouped: dict[date, list[int]] = defaultdict(list)
    for user in users:
        day = parse_time(user["registerTime"], "registerTime").date()
        grouped[day].append(user["userId"])
    jobs = []
    for day, ids in sorted(grouped.items()):
        end = min(date_start(day + timedelta(days=30)), cutoff)
        if end <= date_start(day):
            raise ValueError("观察截止时间必须晚于用户注册日零点")
        for offset in range(0, len(ids), USER_BATCH_SIZE):
            batch = ids[offset:offset + USER_BATCH_SIZE]
            for index, suffix in ((COIN_INDEX, "coin"), (DIAMOND_INDEX, "diamond")):
                jobs.append({"name": f"{day:%Y%m%d}-{offset // USER_BATCH_SIZE:06d}-{suffix}",
                             "index": index, "ids": batch,
                             "start": date_start(day).strftime(DATE_TIME_FORMAT),
                             "end": end.strftime(DATE_TIME_FORMAT)})
    return jobs


def export_es_job(reader: ESReader, analysis: dict[str, Any], job: dict[str, Any], path: Path) -> int:
    temporary = path.with_name(f".{path.name}.part")
    count = 0
    after = None
    seen: set[str] = set()
    try:
        with temporary.open("w", encoding="utf-8") as output:
            while True:
                root = reader.search(job["index"], es_query(
                    analysis, job["index"], job["ids"], job["start"], job["end"], after))
                groups = (root.get("aggregations") or {}).get("groups")
                if not isinstance(groups, dict) or not isinstance(groups.get("buckets"), list):
                    raise ValueError("ES 响应缺少 groups 聚合")
                buckets = groups["buckets"]
                for bucket in buckets:
                    key = bucket.get("key") if isinstance(bucket, dict) else None
                    if not isinstance(key, dict):
                        raise ValueError("ES 聚合桶缺少 key")
                    user_id = exact_nonnegative(key.get("userId"), "userId")
                    business_type = exact_nonnegative(key.get("businessType"), "businessType")
                    status = key.get("status")
                    if user_id not in job["ids"] or type(status) is not int or status not in {-1, 1}:
                        raise ValueError("ES 返回了查询条件之外的用户或状态")
                    tokens = exact_nonnegative((bucket.get("tokens") or {}).get("value"), "tokens")
                    document_count = exact_nonnegative(bucket.get("doc_count"), "doc_count")
                    output.write(json.dumps({"userId": user_id, "businessType": business_type,
                                             "status": status, "tokens": tokens,
                                             "recordCount": document_count},
                                            separators=(",", ":")) + "\n")
                    count += 1
                next_key = groups.get("after_key")
                if not next_key:
                    break
                marker = json.dumps(next_key, sort_keys=True) if isinstance(next_key, dict) else ""
                if not isinstance(next_key, dict) or not buckets or marker in seen:
                    raise ValueError("ES composite 分页键无效")
                seen.add(marker)
                after = next_key
        replace_with_retry(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
    return count


def validate_part(path: Path, metadata: dict[str, Any]) -> Iterator[dict[str, Any]]:
    if not path.is_file() or digest(path) != metadata.get("sha256"):
        raise ValueError(f"检查点缺失或校验失败：{path}")
    count = 0
    with path.open("r", encoding="utf-8") as source:
        for line in source:
            count += 1
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("ES 检查点记录格式错误")
            yield row
    if count != metadata.get("record_count"):
        raise ValueError(f"检查点记录数不匹配：{path}")


def run_collection(config: dict[str, Any], paths: dict[str, Path], *, resume: bool = False,
                   mysql_reader: MySQLReader | None = None, es_reader: ESReader | None = None) -> Path:
    analysis = config["analysis"]
    if resume:
        manifest = load_json(paths["manifest"])
        if manifest.get("identity") != identity(config):
            raise ValueError("续跑配置与原批次不一致")
        if paths["report"].exists():
            raise ValueError("报表已经存在，拒绝覆盖")
    else:
        if any(path.exists() for path in paths.values()):
            raise ValueError("目标输出已存在，请使用新的批次时间戳")
        paths["parts"].mkdir()
        manifest = {"identity": identity(config), "stage": "in_progress", "completed_jobs": {}}
        save_json(paths["manifest"], manifest)

    if manifest.get("stage") == "json_complete":
        if not paths["data"].is_file() or digest(paths["data"]) != manifest.get("data_sha256"):
            raise ValueError("聚合 JSON 缺失或校验失败")
        return paths["data"]
    if not paths["parts"].is_dir():
        raise ValueError("检查点目录不存在")
    if paths["mysql"].is_file():
        if digest(paths["mysql"]) != manifest.get("mysql_sha256"):
            raise ValueError("MySQL 快照校验失败")
        snapshot = load_json(paths["mysql"])
    else:
        database = mysql_reader or MySQLReader(config["mysql"], paths["sql"])
        try:
            snapshot = fetch_mysql_snapshot(database, analysis)
        finally:
            if mysql_reader is None:
                database.close()
        save_json(paths["mysql"], snapshot)
        manifest["mysql_sha256"] = digest(paths["mysql"])
        save_json(paths["manifest"], manifest)
        print(f"MySQL 用户快照：{len(snapshot['users']):,} 人")
        if mysql_reader is None:
            print(f"实际执行的 MySQL SELECT 已保存：{paths['sql'].resolve()}")

    cutoff = parse_time(analysis["observation_end"], "observation_end")
    jobs = es_jobs(snapshot["users"], cutoff)
    reader = es_reader or ESReader(config["es"])
    completed = manifest["completed_jobs"]
    for number, job in enumerate(jobs, start=1):
        part = paths["parts"] / f"{job['name']}.jsonl"
        if job["name"] in completed:
            for _ in validate_part(part, completed[job["name"]]):
                pass
            continue
        count = export_es_job(reader, analysis, job, part)
        completed[job["name"]] = {"sha256": digest(part), "record_count": count}
        save_json(paths["manifest"], manifest)
        print(f"ES 聚合 {number}/{len(jobs)}：{job['name']}（{count:,} 桶）")

    users_by_id = {u["userId"]: u for u in snapshot["users"]}
    for user in snapshot["users"]:
        user["consumptionCoinTokens"] = 0
        user["incomeDiamondTokens"] = 0
        user["rewardCoinTokens"] = 0
        user["rewardDiamondTokens"] = 0
    spend = set(analysis["coin_consumption_types"])
    coin_rewards = set(analysis["coin_reward_types"])
    diamond_income = set(analysis["diamond_income_types"])
    diamond_rewards = set(analysis["diamond_reward_types"])
    for job in jobs:
        part = paths["parts"] / f"{job['name']}.jsonl"
        for row in validate_part(part, completed[job["name"]]):
            user = users_by_id.get(row["userId"])
            if user is None:
                raise ValueError("ES 检查点包含用户范围外的记录")
            kind, bt, status, tokens = job["index"], row["businessType"], row["status"], row["tokens"]
            if kind == COIN_INDEX and status == -1 and bt in spend:
                user["consumptionCoinTokens"] += tokens
            elif kind == COIN_INDEX and status == 1 and bt in coin_rewards:
                user["rewardCoinTokens"] += tokens
            elif kind == DIAMOND_INDEX and status == 1 and bt in diamond_income:
                user["incomeDiamondTokens"] += tokens
                if bt in diamond_rewards:
                    user["rewardDiamondTokens"] += tokens
            else:
                raise ValueError("ES 检查点包含业务类型范围外的聚合桶")

    data = {"meta": {**snapshot["meta"], "es_time_zone": analysis["es_time_zone"],
                     "coin_consumption_types": analysis["coin_consumption_types"],
                     "coin_reward_types": analysis["coin_reward_types"],
                     "diamond_income_types": analysis["diamond_income_types"],
                     "diamond_reward_types": analysis["diamond_reward_types"]},
            "users": snapshot["users"]}
    save_json(paths["data"], data)
    manifest["stage"] = "json_complete"
    manifest["data_sha256"] = digest(paths["data"])
    save_json(paths["manifest"], manifest)
    return paths["data"]


def mark_report_complete(paths: dict[str, Path]) -> None:
    manifest = load_json(paths["manifest"])
    if manifest.get("stage") != "json_complete" or not paths["report"].is_file():
        raise ValueError("报表或聚合 JSON 尚未完整生成")
    manifest["stage"] = "report_complete"
    save_json(paths["manifest"], manifest)

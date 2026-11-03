"""只读查询 MySQL/ES，按当前公会归属汇总收益、成功提现与奖励。"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import re
import ssl
import time
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
COIN_INDEX = "sano_wallet_coin_record"
DIAMOND_INDEX = "sano_wallet_diamond_record"
USER_BATCH_SIZE = 500
ES_PAGE_SIZE = 1000
MAX_EXACT_ES_INTEGER = 2**53 - 1
# 对应 EBusinessType：收到礼物及代理/房主分账；其他钻石入账归入“其他收益”。
NORMAL_GIFT_INCOME_TYPES = frozenset((2, 3, 4, 5))
LUCKY_GIFT_INCOME_TYPES = frozenset((12, 13, 14))
# 对齐既有充值统计 SQL：成功订单中的 Google/Apple 和三方充值计入总额；
# 三方 USDT 取 usdt_amount，其余取 money；producer_type=4 不计入总充值。
RECHARGE_AMOUNT_CASE = """
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


def read_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"文件不存在：{path}")
    result = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(result, dict):
        raise ValueError(f"JSON 根节点必须是对象：{path}")
    return result


def parse_time(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{field} 必须是 yyyy-MM-dd HH:mm:ss")
    try:
        parsed = datetime.strptime(value, TIME_FORMAT)
    except ValueError as error:
        raise ValueError(f"{field} 必须是 yyyy-MM-dd HH:mm:ss") from error
    if parsed.strftime(TIME_FORMAT) != value:
        raise ValueError(f"{field} 必须使用补零的 yyyy-MM-dd HH:mm:ss")
    return parsed


def load_config(mysql_path: Path, es_path: Path, analysis_path: Path) -> dict[str, Any]:
    mysql, es, analysis = read_json(mysql_path), read_json(es_path), read_json(analysis_path)
    for field in ("host", "database", "username", "password"):
        if not isinstance(mysql.get(field), str) or not mysql[field].strip():
            raise ValueError(f"MySQL {field} 必须填写")
    if mysql["host"] == "your-mysql-host" or mysql["username"] == "readonly_user":
        raise ValueError("请先填写真实的 MySQL 只读账号配置")
    port = mysql.get("port", 3306)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("MySQL port 必须是 1–65535 的整数")
    for field in ("connect_timeout_seconds", "read_timeout_seconds"):
        value = mysql.get(field, 15 if field.startswith("connect") else 300)
        if type(value) is not int or not 1 <= value <= 3600:
            raise ValueError(f"MySQL {field} 必须是 1–3600 的整数")

    url = es.get("es_url")
    if not isinstance(url, str):
        raise ValueError("es_url 必须是 HTTP(S) 地址")
    url = url.strip().rstrip("/")
    parsed = urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username
            or parsed.password or parsed.query or parsed.fragment or "your-es-host" in url):
        raise ValueError("es_url 无效，地址中不要包含账号密码或查询参数")
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

    ids = analysis.get("proxy_ids")
    if (not isinstance(ids, list) or not ids or any(type(x) is not int or x <= 0 for x in ids)
            or len(ids) != len(set(ids))):
        raise ValueError("proxy_ids 必须是非空、不重复的正整数数组")
    start, end = parse_time(analysis.get("start"), "start"), parse_time(analysis.get("end"), "end")
    if start >= end:
        raise ValueError("start 必须早于 end；end 为不含边界")
    zone = analysis.get("es_time_zone", "+00:00")
    if (not isinstance(zone, str) or not re.fullmatch(
            r"(?:UTC|[+-](?:0\d|1[0-4]):[0-5]\d|[A-Za-z_]+(?:/[A-Za-z0-9_+-]+)+)", zone)):
        raise ValueError("es_time_zone 无效，例如 +08:00 或 Asia/Shanghai")
    for field in ("coin_reward_types", "diamond_income_types", "diamond_reward_types"):
        values = analysis.get(field)
        if (not isinstance(values, list) or not values
                or any(type(x) is not int or x <= 0 for x in values)
                or len(values) != len(set(values))):
            raise ValueError(f"{field} 必须是不重复的正整数数组")
    if not set(analysis["diamond_reward_types"]) <= set(analysis["diamond_income_types"]):
        raise ValueError("钻石奖励业务类型必须包含在钻石收益类型中")
    if 55 not in analysis["diamond_income_types"]:
        raise ValueError("diamond_income_types 必须包含 55，以单列提现订单奖励")
    # 保留配置中的公会顺序，报表公会汇总按这个顺序展示。
    analysis["proxy_ids"] = list(ids)
    return {"mysql": mysql, "es": es, "analysis": analysis}


def identity(config: dict[str, Any]) -> dict[str, Any]:
    mysql, es, analysis = config["mysql"], config["es"], config["analysis"]
    return {
        "schema_version": 4, "mysql_host": mysql["host"],
        "mysql_port": mysql.get("port", 3306), "mysql_database": mysql["database"],
        "es_url": es["es_url"], "analysis": analysis,
        "user_batch_size": USER_BATCH_SIZE, "es_page_size": ES_PAGE_SIZE,
    }


def output_paths(tag: str, root: Path = ROOT) -> dict[str, Path]:
    return {
        "mysql": root / f"guild-income-mysql-{tag}.json",
        "data": root / f"guild-income-{tag}.json",
        "manifest": root / f"guild-income-manifest-{tag}.json",
        "report": root / f"公会用户收益提现奖励分析-{tag}.xlsx",
        "parts": root / f".guild-income-{tag}.parts",
    }


def find_resume_paths(config: dict[str, Any], root: Path = ROOT) -> dict[str, Path]:
    matches = []
    for path in root.glob("guild-income-manifest-*.json"):
        manifest = read_json(path)
        if manifest.get("identity") == identity(config) and manifest.get("stage") != "report_complete":
            tag = path.name.removeprefix("guild-income-manifest-").removesuffix(".json")
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


def replace_with_retry(temporary: Path, destination: Path) -> None:
    # Windows 文件索引／安全软件短暂占用目标时，保留原文件并有限次重试。
    for attempt in range(8):
        try:
            temporary.replace(destination)
            return
        except PermissionError:
            if attempt == 7:
                raise
            time.sleep(min(0.2 * 2**attempt, 1.0))


def save_json(path: Path, data: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.part")
    try:
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        replace_with_retry(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


class MySQLReader:
    """固定 SQL 只允许单条 SELECT；生产连接还应使用数据库只读账号。"""

    def __init__(self, config: dict[str, Any]):
        try:
            import pymysql
        except ImportError as error:
            raise RuntimeError("缺少 PyMySQL，请安装 requirements.txt") from error
        self.connection = pymysql.connect(
            host=config["host"], port=config.get("port", 3306),
            user=config["username"], password=config["password"],
            database=config["database"], charset="utf8mb4",
            cursorclass=pymysql.cursors.SSDictCursor, autocommit=True,
            connect_timeout=config.get("connect_timeout_seconds", 15),
            read_timeout=config.get("read_timeout_seconds", 300),
        )

    def query(self, sql: str, parameters: tuple[Any, ...]) -> Iterator[dict[str, Any]]:
        if not re.match(r"\s*SELECT\b", sql, re.IGNORECASE) or ";" in sql:
            raise ValueError("只允许执行单条 SELECT 查询")
        with self.connection.cursor() as cursor:
            cursor.execute(sql, parameters)
            yield from cursor

    def close(self) -> None:
        self.connection.close()


def money(value: Any, field: str) -> Decimal:
    amount = Decimal(str(value if value is not None else 0))
    if not amount.is_finite() or amount < 0:
        raise ValueError(f"{field} 必须是非负的美元金额")
    return amount


def fetch_mysql_snapshot(reader: MySQLReader, analysis: dict[str, Any]) -> dict[str, Any]:
    proxy_ids = analysis["proxy_ids"]
    placeholders = ",".join(["%s"] * len(proxy_ids))
    sql_users = f"""
        SELECT id AS user_id, nick, proxy_id
        FROM sano_user
        WHERE proxy_id IN ({placeholders})
        ORDER BY proxy_id, id
    """
    users = []
    seen = set()
    for row in reader.query(sql_users, tuple(proxy_ids)):
        user_id, proxy_id = int(row["user_id"]), int(row["proxy_id"])
        if user_id <= 0 or proxy_id not in proxy_ids or user_id in seen:
            raise ValueError("MySQL 用户结果包含重复 ID 或配置外公会")
        seen.add(user_id)
        users.append({"userId": user_id, "proxyId": proxy_id,
                      "nick": row["nick"] or "", "withdrawUsd": "0", "rechargeUsd": "0"})
    if len(users) > 1_048_575:
        raise ValueError("用户数超过 Excel 单工作表行数上限")

    sql_platform_withdraw = """
        SELECT COALESCE(SUM(COALESCE(reality_money, 0)), 0) AS usd
        FROM sano_wallet_withdraw
        WHERE status = 4 AND pay_time >= %s AND pay_time < %s
    """
    platform_rows = list(reader.query(sql_platform_withdraw,
                                      (analysis["start"], analysis["end"])))
    if len(platform_rows) != 1:
        raise ValueError("MySQL 全平台提现汇总响应不唯一")
    platform_withdraw = money(platform_rows[0]["usd"], "全平台成功提现")

    by_id = {user["userId"]: user for user in users}
    for offset in range(0, len(users), USER_BATCH_SIZE):
        batch = users[offset:offset + USER_BATCH_SIZE]
        ids = [user["userId"] for user in batch]
        sql_withdraw = f"""
            SELECT user_id, SUM(COALESCE(reality_money, 0)) AS usd
            FROM sano_wallet_withdraw
            WHERE status = 4 AND pay_time >= %s AND pay_time < %s
              AND user_id IN ({','.join(['%s'] * len(ids))})
            GROUP BY user_id
        """
        for row in reader.query(sql_withdraw, (analysis["start"], analysis["end"], *ids)):
            user_id = int(row["user_id"])
            if user_id not in by_id or user_id not in ids:
                raise ValueError("MySQL 提现结果包含用户范围外的 ID")
            by_id[user_id]["withdrawUsd"] = str(money(row["usd"], "用户成功提现"))
    cohort_withdraw = sum((Decimal(user["withdrawUsd"]) for user in users), Decimal(0))
    if cohort_withdraw > platform_withdraw:
        raise ValueError("选定公会提现超过同期全平台提现，请检查数据是否在查询期间变化")

    platform_recharge = fetch_recharge_snapshot(reader, users, analysis)
    return {"users": users, "platformWithdrawUsd": str(platform_withdraw),
            "platformRechargeUsd": str(platform_recharge)}


def fetch_recharge_snapshot(reader: MySQLReader, users: list[dict[str, Any]],
                            analysis: dict[str, Any]) -> Decimal:
    """只用 SELECT 补充成功充值；可复用旧批次保存的用户快照。"""
    by_id = {user["userId"]: user for user in users}
    if len(by_id) != len(users):
        raise ValueError("MySQL 用户快照包含重复 ID")
    for user in users:
        user["rechargeUsd"] = "0"
    sql_platform_recharge = f"""
        SELECT COALESCE(SUM({RECHARGE_AMOUNT_CASE}), 0) AS usd
        FROM sano_wallet_recharge
        WHERE status = 2 AND create_time >= %s AND create_time < %s
    """
    platform_rows = list(reader.query(sql_platform_recharge,
                                      (analysis["start"], analysis["end"])))
    if len(platform_rows) != 1:
        raise ValueError("MySQL 全平台充值汇总响应不唯一")
    platform_recharge = money(platform_rows[0]["usd"], "全平台成功充值")
    for offset in range(0, len(users), USER_BATCH_SIZE):
        batch = users[offset:offset + USER_BATCH_SIZE]
        ids = [user["userId"] for user in batch]
        sql_recharge = f"""
            SELECT user_id, SUM({RECHARGE_AMOUNT_CASE}) AS usd
            FROM sano_wallet_recharge
            WHERE status = 2 AND create_time >= %s AND create_time < %s
              AND user_id IN ({','.join(['%s'] * len(ids))})
            GROUP BY user_id
        """
        for row in reader.query(sql_recharge, (analysis["start"], analysis["end"], *ids)):
            user_id = int(row["user_id"])
            if user_id not in by_id or user_id not in ids:
                raise ValueError("MySQL 充值结果包含用户范围外的 ID")
            by_id[user_id]["rechargeUsd"] = str(money(row["usd"], "用户成功充值"))
    cohort_recharge = sum((Decimal(user["rechargeUsd"]) for user in users), Decimal(0))
    if cohort_recharge > platform_recharge:
        raise ValueError("选定公会充值超过同期全平台充值，请检查数据是否在查询期间变化")
    return platform_recharge


class ESReader:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.context = ssl.create_default_context(cafile=config.get("ca_cert"))

    def search(self, index: str, body: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.config['es_url']}/{index}/_search"
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.config.get("username"):
            credentials = f"{self.config['username']}:{self.config['password']}".encode("utf-8")
            headers["Authorization"] = "Basic " + base64.b64encode(credentials).decode("ascii")
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


def es_query(analysis: dict[str, Any], index: str, ids: list[int] | None,
             after: dict[str, Any] | None = None) -> dict[str, Any]:
    if index == COIN_INDEX:
        types = analysis["coin_reward_types"]
    elif index == DIAMOND_INDEX:
        types = analysis["diamond_income_types"]
    else:
        raise ValueError("不允许查询其他 ES 索引")
    filters: list[dict[str, Any]] = [
        {"term": {"status": 1}},
        {"terms": {"business_type": types}},
        {"range": {"create_time": {"gte": analysis["start"], "lt": analysis["end"],
                                    "time_zone": analysis["es_time_zone"]}}},
    ]
    if ids is not None:
        filters.append({"terms": {"user_id": ids}})
    sources = ([{"userId": {"terms": {"field": "user_id"}}}] if ids is not None else []) + [
        {"businessType": {"terms": {"field": "business_type"}}}
    ]
    composite: dict[str, Any] = {"size": ES_PAGE_SIZE, "sources": sources}
    if after is not None:
        composite["after"] = after
    return {"size": 0, "track_total_hits": False,
            "query": {"bool": {"filter": filters}},
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
        raise ValueError(f"ES 聚合 {field} 超出安全整数范围；请缩短统计时间段")
    return result


def es_jobs(users: list[dict[str, Any]]) -> list[dict[str, Any]]:
    jobs = [{"name": "platform-coin", "index": COIN_INDEX, "ids": None},
            {"name": "platform-diamond", "index": DIAMOND_INDEX, "ids": None}]
    ids = sorted(user["userId"] for user in users)
    for offset in range(0, len(ids), USER_BATCH_SIZE):
        batch = ids[offset:offset + USER_BATCH_SIZE]
        suffix = offset // USER_BATCH_SIZE
        jobs.extend((
            {"name": f"guild-{suffix:06d}-coin", "index": COIN_INDEX, "ids": batch},
            {"name": f"guild-{suffix:06d}-diamond", "index": DIAMOND_INDEX, "ids": batch},
        ))
    return jobs


def export_es_job(reader: ESReader, analysis: dict[str, Any], job: dict[str, Any], path: Path) -> int:
    temporary = path.with_name(f".{path.name}.part")
    count = 0
    after = None
    seen_after = set()
    try:
        with temporary.open("w", encoding="utf-8") as output:
            output.write(json.dumps({"job": job["name"]}) + "\n")
            while True:
                root = reader.search(job["index"], es_query(analysis, job["index"], job["ids"], after))
                groups = (root.get("aggregations") or {}).get("groups")
                if not isinstance(groups, dict) or not isinstance(groups.get("buckets"), list):
                    raise ValueError("ES 响应缺少 groups 聚合")
                buckets = groups["buckets"]
                for bucket in buckets:
                    key = bucket.get("key") if isinstance(bucket, dict) else None
                    if not isinstance(key, dict):
                        raise ValueError("ES 聚合桶缺少 key")
                    business = exact_nonnegative(key.get("businessType"), "businessType")
                    allowed = (analysis["coin_reward_types"] if job["index"] == COIN_INDEX
                               else analysis["diamond_income_types"])
                    if business not in allowed:
                        raise ValueError("ES 返回了业务类型范围外的聚合桶")
                    row = {"businessType": business,
                           "tokens": exact_nonnegative((bucket.get("tokens") or {}).get("value"), "tokens"),
                           "recordCount": exact_nonnegative(bucket.get("doc_count"), "doc_count")}
                    if job["ids"] is not None:
                        user_id = exact_nonnegative(key.get("userId"), "userId")
                        if user_id not in job["ids"]:
                            raise ValueError("ES 返回了用户范围外的聚合桶")
                        row["userId"] = user_id
                    output.write(json.dumps(row, separators=(",", ":")) + "\n")
                    count += 1
                next_key = groups.get("after_key")
                if not next_key:
                    break
                marker = json.dumps(next_key, sort_keys=True) if isinstance(next_key, dict) else ""
                if not isinstance(next_key, dict) or not buckets or marker in seen_after:
                    raise ValueError("ES composite 分页键无效")
                seen_after.add(marker)
                after = next_key
        replace_with_retry(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
    return count


def checkpoint_rows(path: Path, metadata: dict[str, Any], name: str) -> Iterator[dict[str, Any]]:
    if not path.is_file() or digest(path) != metadata.get("sha256"):
        raise ValueError(f"检查点缺失或哈希不匹配：{path}")
    count = 0
    with path.open("r", encoding="utf-8") as source:
        if json.loads(source.readline()) != {"job": name}:
            raise ValueError(f"检查点任务名不匹配：{path}")
        for line in source:
            row = json.loads(line)
            if not isinstance(row, dict) or any(type(row.get(field)) is not int for field in
                                                ("businessType", "tokens", "recordCount")):
                raise ValueError(f"检查点记录格式错误：{path}")
            count += 1
            yield row
    if count != metadata.get("record_count"):
        raise ValueError(f"检查点记录数不匹配：{path}")


def consolidate(snapshot: dict[str, Any], jobs: list[dict[str, Any]],
                completed: dict[str, Any], parts: Path, analysis: dict[str, Any]) -> dict[str, Any]:
    users = snapshot["users"]
    by_id = {user["userId"]: user for user in users}
    for user in users:
        user.update(incomeDiamondTokens=0, normalGiftIncomeDiamondTokens=0,
                    luckyGiftIncomeDiamondTokens=0, otherIncomeDiamondTokens=0,
                    withdrawOrderRewardDiamondTokens=0,
                    rewardDiamondTokens=0, rewardCoinTokens=0)
    platform = {"incomeDiamondTokens": 0, "normalGiftIncomeDiamondTokens": 0,
                "luckyGiftIncomeDiamondTokens": 0, "otherIncomeDiamondTokens": 0,
                "withdrawOrderRewardDiamondTokens": 0,
                "rewardDiamondTokens": 0,
                "rewardCoinTokens": 0, "withdrawUsd": snapshot["platformWithdrawUsd"],
                "rechargeUsd": snapshot["platformRechargeUsd"]}
    diamond_rewards = set(analysis["diamond_reward_types"])
    for job in jobs:
        metadata = completed.get(job["name"])
        if not isinstance(metadata, dict):
            raise ValueError(f"缺少 ES 检查点：{job['name']}")
        for row in checkpoint_rows(parts / f"{job['name']}.jsonl", metadata, job["name"]):
            business, tokens = row["businessType"], row["tokens"]
            allowed = (analysis["coin_reward_types"] if job["index"] == COIN_INDEX
                       else analysis["diamond_income_types"])
            if business not in allowed or tokens < 0 or row["recordCount"] < 0:
                raise ValueError("检查点包含业务类型范围外或负数聚合")
            if job["ids"] is None:
                if "userId" in row:
                    raise ValueError("平台检查点不应包含用户 ID")
                target = platform
            else:
                user_id = row.get("userId")
                if type(user_id) is not int or user_id not in job["ids"] or user_id not in by_id:
                    raise ValueError("公会检查点包含用户范围外的 ID")
                target = by_id[user_id]
            if job["index"] == COIN_INDEX:
                target["rewardCoinTokens"] += tokens
            else:
                target["incomeDiamondTokens"] += tokens
                if business in NORMAL_GIFT_INCOME_TYPES:
                    target["normalGiftIncomeDiamondTokens"] += tokens
                elif business in LUCKY_GIFT_INCOME_TYPES:
                    target["luckyGiftIncomeDiamondTokens"] += tokens
                elif business == 55:
                    target["withdrawOrderRewardDiamondTokens"] += tokens
                else:
                    target["otherIncomeDiamondTokens"] += tokens
                if business in diamond_rewards and business != 55:
                    target["rewardDiamondTokens"] += tokens
    income_parts = ("normalGiftIncomeDiamondTokens", "luckyGiftIncomeDiamondTokens",
                    "otherIncomeDiamondTokens")
    for target in (platform, *users):
        if (sum(target[field] for field in income_parts)
                + target["withdrawOrderRewardDiamondTokens"] != target["incomeDiamondTokens"]):
            raise ValueError("钻石收益分类与收益总数不一致")
    for field in ("incomeDiamondTokens", *income_parts,
                  "withdrawOrderRewardDiamondTokens", "rewardDiamondTokens", "rewardCoinTokens"):
        if sum(user[field] for user in users) > platform[field]:
            raise ValueError(f"选定公会 {field} 超过全平台，请检查 ES 数据是否在查询期间变化")
    return {"platform": platform, "users": users}


def run_collection(config: dict[str, Any], paths: dict[str, Path], *, resume: bool = False,
                   mysql_reader: MySQLReader | None = None,
                   es_reader: ESReader | None = None) -> Path:
    analysis = config["analysis"]
    if resume:
        manifest = read_json(paths["manifest"])
        if manifest.get("identity") != identity(config):
            raise ValueError("续跑配置与原批次不一致")
        if paths["report"].exists():
            raise ValueError("报表已经存在，拒绝覆盖")
    else:
        if any(path.exists() for path in paths.values()):
            raise ValueError("输出已存在，请使用新的批次时间戳")
        paths["parts"].mkdir()
        manifest = {"identity": identity(config), "stage": "in_progress", "completed_jobs": {}}
        save_json(paths["manifest"], manifest)
    if manifest.get("stage") == "json_complete":
        if not paths["data"].is_file() or digest(paths["data"]) != manifest.get("data_sha256"):
            raise ValueError("聚合 JSON 缺失或哈希不匹配")
        return paths["data"]
    if not paths["parts"].is_dir():
        raise ValueError("检查点目录不存在")

    if paths["mysql"].is_file():
        if digest(paths["mysql"]) != manifest.get("mysql_sha256"):
            raise ValueError("MySQL 快照哈希不匹配")
        snapshot = read_json(paths["mysql"])
    else:
        database = mysql_reader or MySQLReader(config["mysql"])
        try:
            snapshot = fetch_mysql_snapshot(database, analysis)
        finally:
            if mysql_reader is None:
                database.close()
        save_json(paths["mysql"], snapshot)
        manifest["mysql_sha256"] = digest(paths["mysql"])
        save_json(paths["manifest"], manifest)
        print(f"MySQL 当前公会用户：{len(snapshot['users']):,} 人")

    jobs = es_jobs(snapshot["users"])
    completed = manifest.get("completed_jobs")
    if not isinstance(completed, dict):
        raise ValueError("续跑清单缺少 completed_jobs")
    reader = es_reader or ESReader(config["es"])
    for number, job in enumerate(jobs, start=1):
        part = paths["parts"] / f"{job['name']}.jsonl"
        if job["name"] in completed:
            for _ in checkpoint_rows(part, completed[job["name"]], job["name"]):
                pass
            print(f"跳过已完成 ES 查询 {number}/{len(jobs)}：{job['name']}")
            continue
        count = export_es_job(reader, analysis, job, part)
        completed[job["name"]] = {"sha256": digest(part), "record_count": count}
        save_json(paths["manifest"], manifest)
        print(f"ES 聚合 {number}/{len(jobs)}：{job['name']}（{count:,} 桶）")

    totals = consolidate(snapshot, jobs, completed, paths["parts"], analysis)
    data = {"meta": {"proxy_ids": analysis["proxy_ids"], "start": analysis["start"],
                     "end": analysis["end"], "es_time_zone": analysis["es_time_zone"],
                     "coin_reward_types": analysis["coin_reward_types"],
                     "diamond_income_types": analysis["diamond_income_types"],
                     "diamond_reward_types": analysis["diamond_reward_types"],
                     "normal_gift_income_types": sorted(NORMAL_GIFT_INCOME_TYPES),
                     "lucky_gift_income_types": sorted(LUCKY_GIFT_INCOME_TYPES),
                     "guild_basis": "sano_user.proxy_id（采集时当前值）"}, **totals}
    save_json(paths["data"], data)
    manifest["stage"] = "json_complete"
    manifest["data_sha256"] = digest(paths["data"])
    save_json(paths["manifest"], manifest)
    return paths["data"]


def mark_report_complete(paths: dict[str, Path]) -> None:
    manifest = read_json(paths["manifest"])
    if manifest.get("stage") != "json_complete" or not paths["report"].is_file():
        raise ValueError("聚合 JSON 或报表尚未完整生成")
    manifest["stage"] = "report_complete"
    save_json(paths["manifest"], manifest)

"""从 ES 导出金币消费聚合。每个查询任务独立检查点，支持安全续跑。"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import re
import ssl
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


TOOL_DIR = Path(__file__).resolve().parent
INDEX_ALIAS = "sano_wallet_coin_record"
BUSINESS_TYPES = (1, 11, 21, 41, 42, 105, 106)
USER_BATCH_SIZE = 1_000
PAGE_SIZE = 1_000
MAX_EXACT_ES_INTEGER = 2**53 - 1
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"


@dataclass(frozen=True)
class RunConfig:
    es_url: str
    username: str
    password: str
    timeout_seconds: int
    ca_cert: Path | None
    user_ids_file: Path
    start: str
    end: str
    bucket_time_zone: str


@dataclass(frozen=True)
class OutputPaths:
    data: Path
    manifest: Path
    report: Path


def output_paths(root: Path = TOOL_DIR, tag: str | None = None) -> OutputPaths:
    suffix = tag or datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    return OutputPaths(
        root / f"user-consumption-{suffix}.json",
        root / f"user-consumption-manifest-{suffix}.json",
        root / f"用户消费习惯分析-{suffix}.xlsx",
    )


def load_config(es_path: Path, analysis_path: Path) -> RunConfig:
    def read(path: Path) -> dict[str, Any]:
        if not path.is_file():
            raise ValueError(f"配置文件不存在：{path}")
        with path.open("r", encoding="utf-8-sig") as source:
            value = json.load(source)
        if not isinstance(value, dict):
            raise ValueError(f"配置文件必须是 JSON 对象：{path}")
        return value

    es, analysis = read(es_path), read(analysis_path)
    url = es.get("es_url")
    if not isinstance(url, str):
        raise ValueError("es_url 必须是完整 HTTP(S) 地址")
    url = url.strip().rstrip("/")
    parsed = urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.query
            or parsed.fragment or parsed.username or parsed.password or "your-es-host" in url):
        raise ValueError("es_url 无效，请填写不含账号密码和查询参数的真实 ES 地址")
    username, password = es.get("username", ""), es.get("password", "")
    if (not isinstance(username, str) or not isinstance(password, str)
            or bool(username) != bool(password)
            or username == "your_username" or password == "your_password"):
        raise ValueError("username 和 password 必须同时填写真实值，或同时留空")
    timeout = es.get("timeout_seconds", 300)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError("timeout_seconds 必须是 1–3600 的整数")

    def local_path(value: Any, config_path: Path, field: str, required: bool) -> Path | None:
        if value is None and not required:
            return None
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{field} 必须是非空路径")
        path = Path(value.strip())
        path = path if path.is_absolute() else config_path.parent / path
        if not path.is_file():
            raise ValueError(f"{field} 文件不存在：{path}")
        return path

    ca_cert = local_path(es.get("ca_cert"), es_path, "ca_cert", False)
    user_ids_file = local_path(analysis.get("user_ids_file"), analysis_path, "user_ids_file", True)
    start, end = analysis.get("start"), analysis.get("end")
    if not isinstance(start, str) or not isinstance(end, str):
        raise ValueError("start 和 end 必须是 yyyy-MM-dd HH:mm:ss 时间字符串")
    try:
        parsed_start, parsed_end = datetime.strptime(start, TIME_FORMAT), datetime.strptime(end, TIME_FORMAT)
    except ValueError as error:
        raise ValueError("start/end 格式应为 yyyy-MM-dd HH:mm:ss") from error
    if parsed_start.strftime(TIME_FORMAT) != start or parsed_end.strftime(TIME_FORMAT) != end:
        raise ValueError("start/end 格式应为补零的 yyyy-MM-dd HH:mm:ss")
    if parsed_end < parsed_start:
        raise ValueError("end 不能早于 start")
    zone = analysis.get("bucket_time_zone", "+00:00")
    if (not isinstance(zone, str) or not re.fullmatch(
            r"(?:UTC|[+-](?:0\d|1[0-4]):[0-5]\d|[A-Za-z_]+(?:/[A-Za-z0-9_+-]+)+)", zone.strip())):
        raise ValueError("bucket_time_zone 请使用 +00:00、+08:00 或 Asia/Shanghai 等 ES 时间区")
    return RunConfig(url, username, password, timeout, ca_cert, user_ids_file,
                     start, end, zone.strip())


class ESClient:
    def __init__(self, config: RunConfig):
        self.config = config
        self.context = ssl.create_default_context(
            cafile=str(config.ca_cert) if config.ca_cert else None
        )

    def search(self, query: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.config.es_url}/{INDEX_ALIAS}/_search"
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.config.username:
            credentials = f"{self.config.username}:{self.config.password}".encode("utf-8")
            headers["Authorization"] = "Basic " + base64.b64encode(credentials).decode("ascii")
        payload = json.dumps(query, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        for attempt in range(3):
            request = Request(url, data=payload, headers=headers, method="POST")
            try:
                with urlopen(request, timeout=self.config.timeout_seconds,
                             context=self.context if url.startswith("https://") else None) as response:
                    root = json.load(response)
                validate_response(root)
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


def validate_response(root: Any) -> None:
    if not isinstance(root, dict) or "error" in root:
        raise ValueError("ES 返回错误或无效响应")
    shards = root.get("_shards")
    if root.get("timed_out") is True or not isinstance(shards, dict) or shards.get("failed") != 0:
        raise ValueError("ES 查询超时、分片失败或缺少分片状态，拒绝生成报告")


def query_body(config: RunConfig, kind: str, user_ids: list[int] | None = None,
               after_key: dict[str, Any] | None = None) -> dict[str, Any]:
    filters: list[dict[str, Any]] = [
        {"terms": {"business_type": list(BUSINESS_TYPES)}},
        {"term": {"status": -1}},
        {"range": {"create_time": {"gte": config.start, "lte": config.end,
                                   "time_zone": config.bucket_time_zone}}},
    ]
    if user_ids is not None:
        filters.append({"terms": {"user_id": user_ids}})
    if kind == "daily":
        sources = ([{"userId": {"terms": {"field": "user_id"}}}] if user_ids is not None else []) + [
            {"day": {"date_histogram": {"field": "create_time", "calendar_interval": "1d",
                                          "time_zone": config.bucket_time_zone,
                                          "format": "yyyy-MM-dd"}}},
            {"businessType": {"terms": {"field": "business_type"}}},
        ]
    elif kind == "hourly":
        sources = [{"hour": {"date_histogram": {"field": "create_time", "fixed_interval": "1h",
                                                 "time_zone": config.bucket_time_zone,
                                                 "format": "yyyy-MM-dd'T'HH:mm:ss"}}}]
    else:
        raise ValueError(f"未知查询类型：{kind}")
    composite: dict[str, Any] = {"size": PAGE_SIZE, "sources": sources}
    if after_key is not None:
        composite["after"] = after_key
    return {"size": 0, "track_total_hits": False,
            "query": {"bool": {"filter": filters}},
            "aggs": {"groups": {"composite": composite,
                                 "aggs": {"total_tokens": {"sum": {"field": "tokens"}}}}}}


def exact_integer(value: Any, field: str) -> int:
    if type(value) is int:
        result = value
    elif type(value) is float and math.isfinite(value) and value.is_integer():
        result = int(value)
    else:
        raise ValueError(f"ES 聚合 {field} 不是整数：{value!r}")
    if result < 0 or result > MAX_EXACT_ES_INTEGER:
        raise ValueError(f"ES 聚合 {field} 超出非负浮点安全整数范围，请缩短时间段")
    return result


def checkpoint_dir(paths: OutputPaths) -> Path:
    return paths.data.with_name(f".{paths.data.stem}.parts")


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def save_manifest(path: Path, manifest: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.part")
    try:
        temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def run_identity(config: RunConfig, user_ids: set[int]) -> dict[str, Any]:
    ordered = sorted(user_ids)
    templates = {name: query_body(config, kind, ordered[:USER_BATCH_SIZE] if cohort else None)
                 for name, kind, cohort in (("platform_daily", "daily", False),
                                            ("platform_hourly", "hourly", False),
                                            ("cohort_daily", "daily", True),
                                            ("cohort_hourly", "hourly", True))}
    return {
        "es_url": config.es_url, "index_alias": INDEX_ALIAS,
        "start": config.start, "end": config.end,
        "bucket_time_zone": config.bucket_time_zone,
        "business_types": list(BUSINESS_TYPES),
        "user_count": len(ordered),
        "user_ids_sha256": hashlib.sha256(",".join(map(str, ordered)).encode("ascii")).hexdigest(),
        "user_batch_size": USER_BATCH_SIZE, "page_size": PAGE_SIZE,
        "query_sha256": hashlib.sha256(json.dumps(templates, sort_keys=True).encode("utf-8")).hexdigest(),
    }


def find_resume_paths(config: RunConfig, user_ids: set[int], root: Path = TOOL_DIR) -> OutputPaths:
    identity = run_identity(config, user_ids)
    matches = []
    for manifest_path in root.glob("user-consumption-manifest-*.json"):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise ValueError(f"续跑清单不可读取：{manifest_path}：{error}") from error
        if manifest.get("query") != identity or manifest.get("status") not in {"in_progress", "json_complete"}:
            continue
        tag = manifest_path.name.removeprefix("user-consumption-manifest-").removesuffix(".json")
        paths = output_paths(root, tag)
        if not paths.report.exists():
            matches.append(paths)
    if len(matches) != 1:
        raise ValueError(f"--resume 需要恰好一个相同口径的未完成批次，当前找到 {len(matches)} 个")
    return matches[0]


def jobs(ordered_ids: list[int]) -> list[tuple[str, str, list[int] | None]]:
    result = [("platform_daily", "daily", None), ("platform_hourly", "hourly", None)]
    for start in range(0, len(ordered_ids), USER_BATCH_SIZE):
        batch = ordered_ids[start:start + USER_BATCH_SIZE]
        index = start // USER_BATCH_SIZE
        result.extend(((f"cohort_daily_{index:06d}", "daily", batch),
                       (f"cohort_hourly_{index:06d}", "hourly", batch)))
    return result


def part_path(parts_dir: Path, name: str) -> Path:
    return parts_dir / f"{name}.jsonl"


def checkpoint_rows(path: Path, name: str, metadata: dict[str, Any]) -> Iterator[dict[str, Any]]:
    if not path.is_file() or digest_file(path) != metadata.get("sha256"):
        raise ValueError(f"续跑检查点缺失或校验失败：{path}")
    with path.open("r", encoding="utf-8") as source:
        header = json.loads(source.readline())
        if header != {"job": name}:
            raise ValueError(f"续跑检查点任务不匹配：{path}")
        count = 0
        for line in source:
            row = json.loads(line, parse_float=reject_float)
            if not isinstance(row, dict):
                raise ValueError(f"续跑检查点记录格式错误：{path}")
            count += 1
            yield row
    if count != metadata.get("record_count"):
        raise ValueError(f"续跑检查点记录数不匹配：{path}")


def reject_float(value: str) -> None:
    raise ValueError(f"检查点含小数：{value}")


def export_job(client: ESClient, config: RunConfig, name: str, kind: str,
               batch: list[int] | None, destination: Path) -> int:
    temporary = destination.with_name(f".{destination.name}.part")
    count = 0
    after_key = None
    seen_keys: set[str] = set()
    batch_ids = set(batch) if batch is not None else None
    try:
        with temporary.open("w", encoding="utf-8") as output:
            output.write(json.dumps({"job": name}) + "\n")
            while True:
                root = client.search(query_body(config, kind, batch, after_key))
                validate_response(root)
                aggregation = (root.get("aggregations") or {}).get("groups")
                if not isinstance(aggregation, dict) or not isinstance(aggregation.get("buckets"), list):
                    raise ValueError("ES 响应缺少 groups 聚合桶")
                buckets = aggregation["buckets"]
                for bucket in buckets:
                    if not isinstance(bucket, dict) or not isinstance(bucket.get("key"), dict):
                        raise ValueError("ES 聚合桶缺少 key")
                    key = bucket["key"]
                    record: dict[str, Any] = {
                        "tokens": exact_integer((bucket.get("total_tokens") or {}).get("value"), "tokens"),
                        "recordCount": exact_integer(bucket.get("doc_count"), "doc_count"),
                    }
                    if kind == "daily":
                        day = key.get("day")
                        business = exact_integer(key.get("businessType"), "businessType")
                        if not isinstance(day, str) or business not in BUSINESS_TYPES:
                            raise ValueError("ES 日期或业务类型无效")
                        datetime.strptime(day, "%Y-%m-%d")
                        record.update(day=day, businessType=business)
                        if batch_ids is not None:
                            user_id = exact_integer(key.get("userId"), "userId")
                            if user_id not in batch_ids:
                                raise ValueError("ES 返回了用户范围外的聚合桶")
                            record["userId"] = user_id
                    else:
                        hour = key.get("hour")
                        if not isinstance(hour, str):
                            raise ValueError("ES 小时桶无效")
                        datetime.strptime(hour, "%Y-%m-%dT%H:%M:%S")
                        record["hour"] = hour
                    output.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                    count += 1
                next_key = aggregation.get("after_key")
                if not next_key:
                    break
                marker = json.dumps(next_key, sort_keys=True) if isinstance(next_key, dict) else ""
                if not isinstance(next_key, dict) or not buckets or marker in seen_keys:
                    raise ValueError("ES composite after_key 无效，无法保证完整分页")
                seen_keys.add(marker)
                after_key = next_key
        temporary.replace(destination)
    finally:
        if temporary.exists():
            temporary.unlink()
    return count


def export_json(config: RunConfig, user_ids: set[int], paths: OutputPaths,
                client: ESClient | None = None, *, resume: bool = False) -> Path:
    if not user_ids:
        raise ValueError("用户 ID 名单为空")
    paths.data.parent.mkdir(parents=True, exist_ok=True)
    parts_dir = checkpoint_dir(paths)
    identity = run_identity(config, user_ids)
    if resume:
        if not paths.manifest.is_file():
            raise ValueError("--resume 需要已有批次清单")
        manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
        if manifest.get("query") != identity or manifest.get("status") not in {"in_progress", "json_complete"}:
            raise ValueError("续跑查询口径或用户名单与原批次不一致")
        if paths.report.exists():
            raise ValueError("该批次报表已存在，不能续跑覆盖")
        if manifest["status"] == "json_complete":
            if not paths.data.is_file() or digest_file(paths.data) != manifest.get("json_sha256"):
                raise ValueError("已完成的聚合 JSON 缺失或校验失败")
            return paths.data
    else:
        if any(path.exists() for path in (paths.data, paths.manifest, paths.report, parts_dir)):
            raise ValueError("本次输出文件已存在，请重新运行生成新时间戳文件")
        parts_dir.mkdir()
        manifest = {"query": identity, "status": "in_progress", "completed_jobs": {}}
        save_manifest(paths.manifest, manifest)
    if not parts_dir.is_dir() or not isinstance(manifest.get("completed_jobs"), dict):
        raise ValueError("续跑检查点目录或清单无效")
    completed = manifest["completed_jobs"]
    ordered = sorted(user_ids)
    all_jobs = jobs(ordered)
    es = client or ESClient(config)
    for index, (name, kind, batch) in enumerate(all_jobs, start=1):
        destination = part_path(parts_dir, name)
        if name in completed:
            for _ in checkpoint_rows(destination, name, completed[name]):
                pass
            print(f"跳过已完成查询 {index}/{len(all_jobs)}：{name}")
            continue
        count = export_job(es, config, name, kind, batch, destination)
        completed[name] = {"sha256": digest_file(destination), "record_count": count}
        save_manifest(paths.manifest, manifest)
        print(f"已完成查询 {index}/{len(all_jobs)}：{name}（{count:,} 桶）")

    temporary = paths.data.with_name(f".{paths.data.name}.part")
    arrays = {"platformDaily": [name for name, _, _ in all_jobs if name == "platform_daily"],
              "platformHourly": [name for name, _, _ in all_jobs if name == "platform_hourly"],
              "cohortDaily": [name for name, _, _ in all_jobs if name.startswith("cohort_daily_")],
              "cohortHourly": [name for name, _, _ in all_jobs if name.startswith("cohort_hourly_")]}
    try:
        with temporary.open("w", encoding="utf-8") as output:
            output.write('{"meta":')
            json.dump(identity, output, ensure_ascii=False, separators=(",", ":"))
            for key, names in arrays.items():
                output.write(f',"{key}":[')
                first = True
                for name in names:
                    for row in checkpoint_rows(part_path(parts_dir, name), name, completed[name]):
                        if not first:
                            output.write(",")
                        json.dump(row, output, ensure_ascii=False, separators=(",", ":"))
                        first = False
                output.write("]")
            output.write("}\n")
        temporary.replace(paths.data)
    finally:
        if temporary.exists():
            temporary.unlink()
    manifest["status"] = "json_complete"
    manifest["json_sha256"] = digest_file(paths.data)
    save_manifest(paths.manifest, manifest)
    print(f"聚合 JSON 已导出：{paths.data.resolve()}")
    return paths.data


def analysis_stream(paths: OutputPaths, user_ids: set[int]) -> dict[str, Any]:
    """报表按任务逐行读取聚合检查点，不把完整 JSON 加载进内存。"""
    manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
    if manifest.get("status") != "json_complete" or not paths.data.is_file():
        raise ValueError("聚合 JSON 尚未完整导出")
    if digest_file(paths.data) != manifest.get("json_sha256"):
        raise ValueError("聚合 JSON 校验失败")
    completed = manifest.get("completed_jobs")
    if not isinstance(completed, dict):
        raise ValueError("检查点清单无效")
    all_jobs = jobs(sorted(user_ids))
    parts_dir = checkpoint_dir(paths)

    def read_names(names: list[str]) -> Iterator[dict[str, Any]]:
        for name in names:
            if name not in completed:
                raise ValueError(f"缺少已完成查询：{name}")
            yield from checkpoint_rows(part_path(parts_dir, name), name, completed[name])

    return {
        "meta": manifest["query"],
        "platformDaily": read_names(["platform_daily"]),
        "platformHourly": read_names(["platform_hourly"]),
        "cohortDaily": read_names([name for name, _, _ in all_jobs if name.startswith("cohort_daily_")]),
        "cohortHourly": read_names([name for name, _, _ in all_jobs if name.startswith("cohort_hourly_")]),
    }


def mark_report_complete(paths: OutputPaths) -> None:
    manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
    if manifest.get("status") != "json_complete" or not paths.report.is_file():
        raise ValueError("报表或聚合 JSON 尚未完整生成")
    manifest["status"] = "report_complete"
    save_manifest(paths.manifest, manifest)

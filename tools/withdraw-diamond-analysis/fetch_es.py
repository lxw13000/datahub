"""按提现用户 ID 批量查询 ES，并保存与原接口兼容的扁平汇总 JSON。"""

from __future__ import annotations

import base64
import hashlib
import json
import math
import ssl
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


INDEX_ALIAS = "sano_wallet_diamond_record"
USER_BATCH_SIZE = 1_000
COMPOSITE_PAGE_SIZE = 1_000
MAX_EXACT_ES_INTEGER = 2**53 - 1
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
TOOL_DIR = Path(__file__).resolve().parent


@dataclass(frozen=True)
class RunConfig:
    es_url: str
    username: str
    password: str
    timeout_seconds: int
    ca_cert: Path | None
    user_ids_file: Path
    withdraw_start: str
    withdraw_end: str
    statistics_start: str
    statistics_end: str
    exclude_business_types: tuple[int, ...]


@dataclass(frozen=True)
class OutputPaths:
    data: Path
    manifest: Path
    report: Path


def output_paths(root: Path = TOOL_DIR, tag: str | None = None) -> OutputPaths:
    """每次运行在脚本目录生成独立文件，避免覆盖已有 JSON 或报表。"""
    suffix = tag or datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    return OutputPaths(
        root / f"withdraw-analysis-{suffix}.json",
        root / f"withdraw-analysis-manifest-{suffix}.json",
        root / f"提现用户钻石来源用途分析-{suffix}.xlsx",
    )


def load_config(es_path: Path, analysis_path: Path) -> RunConfig:
    def read_json(path: Path) -> dict[str, Any]:
        if not path.is_file():
            raise ValueError(f"配置文件不存在：{path}")
        with path.open("r", encoding="utf-8-sig") as source:
            raw = json.load(source)
        if not isinstance(raw, dict):
            raise ValueError(f"配置文件必须是 JSON 对象：{path}")
        return raw

    es = read_json(es_path)
    analysis = read_json(analysis_path)

    url = es.get("es_url")
    if not isinstance(url, str):
        raise ValueError("es_url 必须是完整的 HTTP(S) 地址")
    url = url.strip().rstrip("/")
    parsed = urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.query
            or parsed.fragment or parsed.username or parsed.password):
        raise ValueError("es_url 必须是完整的 HTTP(S) 地址，不能包含账号、密码或查询参数")
    if "your-es-host" in url:
        raise ValueError("请先把 es_url 示例值改成真实 ES 地址")

    username = es.get("username", "")
    password = es.get("password", "")
    if not isinstance(username, str) or not isinstance(password, str) or bool(username) != bool(password):
        raise ValueError("username 和 password 必须同时填写字符串，或同时留空")
    if username == "your_username" or password == "your_password":
        raise ValueError("请先填写真实 ES 账号密码")
    timeout = es.get("timeout_seconds", 300)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError("timeout_seconds 必须是 1–3600 的整数")

    def file_path(raw: dict[str, Any], base: Path, field: str, *, required: bool = True) -> Path | None:
        value = raw.get(field)
        if value is None and not required:
            return None
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{field} 必须是非空路径")
        candidate = Path(value.strip())
        return candidate if candidate.is_absolute() else base.parent / candidate

    ca_cert = file_path(es, es_path, "ca_cert", required=False)
    if ca_cert is not None and not ca_cert.is_file():
        raise ValueError(f"CA 证书文件不存在：{ca_cert}")
    user_ids_file = file_path(analysis, analysis_path, "user_ids_file")
    if not user_ids_file.is_file():
        raise ValueError(f"用户 ID 文件不存在：{user_ids_file}")

    periods = {}
    for field in ("withdraw_start", "withdraw_end", "statistics_start", "statistics_end"):
        value = analysis.get(field)
        if not isinstance(value, str):
            raise ValueError(f"{field} 必须是 {TIME_FORMAT} 格式的时间字符串")
        try:
            periods[field] = datetime.strptime(value, TIME_FORMAT)
        except ValueError as error:
            raise ValueError(f"{field} 格式错误，请使用 yyyy-MM-dd HH:mm:ss") from error
    if periods["withdraw_end"] < periods["withdraw_start"]:
        raise ValueError("提现结束时间不能早于开始时间")
    if periods["statistics_end"] < periods["statistics_start"]:
        raise ValueError("钻石统计结束时间不能早于开始时间")

    excluded = analysis.get("exclude_business_types", [])
    if not isinstance(excluded, list) or any(type(item) is not int or item < 0 for item in excluded):
        raise ValueError("exclude_business_types 必须是非负整数数组")
    return RunConfig(
        url, username, password, timeout, ca_cert, user_ids_file,
        analysis["withdraw_start"], analysis["withdraw_end"],
        analysis["statistics_start"], analysis["statistics_end"],
        tuple(sorted(set(excluded))),
    )


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
                if not isinstance(root, dict) or "error" in root:
                    raise ValueError("ES 返回无效响应或查询错误")
                shards = root.get("_shards")
                if root.get("timed_out") is True or not isinstance(shards, dict) or shards.get("failed") != 0:
                    raise ValueError("ES 查询超时、缺少分片状态或有分片失败，拒绝生成报告")
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


def query_body(user_ids: list[int], config: RunConfig, excluded: set[int],
               after_key: dict[str, Any] | None) -> dict[str, Any]:
    filters = [
        {"terms": {"user_id": user_ids}},
        {"terms": {"status": [1, -1]}},
        {"range": {"create_time": {
            "gte": config.statistics_start, "lte": config.statistics_end,
        }}},
    ]
    bool_query: dict[str, Any] = {"filter": filters}
    if excluded:
        bool_query["must_not"] = [{"terms": {"business_type": sorted(excluded)}}]
    composite: dict[str, Any] = {
        "size": COMPOSITE_PAGE_SIZE,
        "sources": [
            {"userId": {"terms": {"field": "user_id"}}},
            {"status": {"terms": {"field": "status"}}},
            {"businessType": {"terms": {"field": "business_type"}}},
        ],
    }
    if after_key:
        composite["after"] = after_key
    return {
        "size": 0,
        "track_total_hits": False,
        "query": {"bool": bool_query},
        "aggs": {"user_status_business": {
            "composite": composite,
            "aggs": {"total_tokens": {"sum": {"field": "tokens"}}},
        }},
    }


def bucket_integer(value: Any, field: str) -> int:
    if type(value) is int:
        result = value
    elif type(value) is float and math.isfinite(value) and value.is_integer():
        result = int(value)
    else:
        raise ValueError(f"ES 聚合 {field} 不是精确整数：{value!r}")
    if abs(result) > MAX_EXACT_ES_INTEGER:
        raise ValueError(f"ES 聚合 {field} 超过浮点安全整数范围，请缩短统计时间段")
    return result


def run_identity(config: RunConfig, user_ids: set[int], excluded: set[int]) -> dict[str, Any]:
    """清单只记录查询口径，不保存 ES 密码或用户 ID 明文。"""
    ordered_ids = sorted(user_ids)
    query = query_body(ordered_ids[:USER_BATCH_SIZE], config, excluded, None)
    return {
        "es_url": config.es_url,
        "index_alias": INDEX_ALIAS,
        "withdraw_start": config.withdraw_start,
        "withdraw_end": config.withdraw_end,
        "statistics_start": config.statistics_start,
        "statistics_end": config.statistics_end,
        "exclude_business_types": sorted(excluded),
        "user_count": len(ordered_ids),
        "user_ids_sha256": hashlib.sha256(",".join(map(str, ordered_ids)).encode("ascii")).hexdigest(),
        "user_batch_size": USER_BATCH_SIZE,
        "composite_page_size": COMPOSITE_PAGE_SIZE,
        "query_sha256": hashlib.sha256(
            json.dumps(query, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
    }


def save_manifest(path: Path, manifest: dict[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.part")
    try:
        temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def checkpoint_dir(paths: OutputPaths) -> Path:
    return paths.data.with_name(f".{paths.data.stem}.parts")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_resume_paths(config: RunConfig, user_ids: set[int], excluded: set[int],
                      root: Path = TOOL_DIR) -> OutputPaths:
    """裸 --resume 只接受唯一一个口径相同、尚未完成报表的批次。"""
    identity = run_identity(config, user_ids, excluded)
    matches = []
    for path in root.glob("withdraw-analysis-manifest-*.json"):
        try:
            manifest = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise ValueError(f"续跑清单不可读取：{path}：{error}") from error
        if manifest.get("query") != identity or manifest.get("status") not in {"in_progress", "json_complete"}:
            continue
        tag = path.name.removeprefix("withdraw-analysis-manifest-").removesuffix(".json")
        paths = output_paths(root, tag)
        if paths.report.exists():
            continue
        matches.append(paths)
    if len(matches) != 1:
        raise ValueError(f"--resume 需要恰好一个相同查询口径的未完成批次，当前找到 {len(matches)} 个")
    return matches[0]


def read_checkpoint(path: Path, batch_index: int, batch: list[int],
                    metadata: dict[str, Any]) -> list[dict[str, int]]:
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != metadata.get("sha256"):
        raise ValueError(f"续跑检查点校验失败：{path}")
    part = json.loads(raw)
    batch_digest = hashlib.sha256(",".join(map(str, batch)).encode("ascii")).hexdigest()
    if (not isinstance(part, dict) or part.get("batch_index") != batch_index
            or part.get("user_ids_sha256") != batch_digest
            or not isinstance(part.get("records"), list)
            or len(part["records"]) != metadata.get("record_count")):
        raise ValueError(f"续跑检查点内容不匹配：{path}")
    return part["records"]


def mark_report_complete(paths: OutputPaths) -> None:
    manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
    if manifest.get("status") != "json_complete" or not paths.report.is_file():
        raise ValueError("报表或聚合 JSON 尚未完整生成，不能标记完成")
    manifest["status"] = "report_complete"
    save_manifest(paths.manifest, manifest)


def export_json(config: RunConfig, user_ids: set[int], excluded: set[int],
                paths: OutputPaths, client: ESClient | None = None,
                *, resume: bool = False) -> Path:
    """按用户批次保存完整检查点；分页未完成的当前批次在续跑时重新查询。"""
    if not user_ids:
        raise ValueError("用户 ID 列表为空，不能执行 ES 查询")
    target = paths.data
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.resolve() == config.user_ids_file.resolve():
        raise ValueError("用户 ID 文件不能与聚合 JSON 输出路径相同")
    identity = run_identity(config, user_ids, excluded)
    parts_dir = checkpoint_dir(paths)
    if resume:
        if not paths.manifest.is_file():
            raise ValueError("--resume 需要已有的批次清单")
        manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
        if manifest.get("query") != identity or manifest.get("status") not in {"in_progress", "json_complete"}:
            raise ValueError("续跑时 ES 地址、查询口径或用户 ID 名单与原批次不一致")
        if paths.report.exists():
            raise ValueError("该批次报表已存在，不能续跑覆盖")
        if manifest["status"] == "json_complete":
            if not target.is_file() or file_sha256(target) != manifest.get("json_sha256"):
                raise ValueError("已完成的聚合 JSON 缺失或校验失败")
            return target
    else:
        if any(path.exists() for path in (paths.data, paths.manifest, paths.report, parts_dir)):
            raise ValueError("本次输出文件已存在，请重新运行以使用新的时间戳文件名")
        parts_dir.mkdir()
        manifest = {"query": identity, "status": "in_progress", "completed_batches": {}}
        save_manifest(paths.manifest, manifest)

    es = client or ESClient(config)
    ordered_ids = sorted(user_ids)
    if not parts_dir.is_dir():
        raise ValueError("续跑检查点目录不存在")
    completed = manifest.get("completed_batches")
    if not isinstance(completed, dict):
        raise ValueError("续跑清单的 completed_batches 无效")
    batches = [ordered_ids[start:start + USER_BATCH_SIZE]
               for start in range(0, len(ordered_ids), USER_BATCH_SIZE)]
    for index, batch in enumerate(batches):
        part_path = parts_dir / f"batch-{index:06d}.json"
        batch_ids = set(batch)
        metadata = completed.get(str(index))
        if metadata is not None:
            read_checkpoint(part_path, index, batch, metadata)
            print(f"已跳过完成批次 {index + 1:,}/{len(batches):,}")
            continue
        records: list[dict[str, int]] = []
        after_key = None
        seen_after_keys: set[str] = set()
        while True:
            root = es.search(query_body(batch, config, excluded, after_key))
            if (not isinstance(root, dict) or root.get("timed_out") is True
                    or not isinstance(root.get("_shards"), dict)
                    or root["_shards"].get("failed") != 0):
                raise ValueError("ES 查询超时、分片失败或分片状态缺失，拒绝生成报告")
            aggregations = root.get("aggregations")
            aggregation = (aggregations.get("user_status_business")
                           if isinstance(aggregations, dict) else None)
            if not isinstance(aggregation, dict) or not isinstance(aggregation.get("buckets"), list):
                raise ValueError("ES 响应缺少 user_status_business 聚合桶")
            buckets = aggregation["buckets"]
            for bucket in buckets:
                if not isinstance(bucket, dict) or not isinstance(bucket.get("key"), dict):
                    raise ValueError("ES 聚合桶缺少 key")
                key = bucket["key"]
                record = {
                    "userId": bucket_integer(key.get("userId"), "userId"),
                    "status": bucket_integer(key.get("status"), "status"),
                    "businessType": bucket_integer(key.get("businessType"), "businessType"),
                    "tokens": bucket_integer(
                        (bucket.get("total_tokens") or {}).get("value"), "tokens"),
                }
                if record["userId"] not in batch_ids or record["status"] not in (1, -1):
                    raise ValueError("ES 聚合桶返回了用户范围外的记录或非法 status")
                if record["businessType"] < 0 or record["tokens"] < 0:
                    raise ValueError("ES 聚合桶返回了负数业务类型或 tokens")
                records.append(record)
            next_key = aggregation.get("after_key")
            if not next_key:
                break
            marker = json.dumps(next_key, sort_keys=True) if isinstance(next_key, dict) else ""
            if not isinstance(next_key, dict) or marker in seen_after_keys or not buckets:
                raise ValueError("ES composite after_key 无效，无法保证完整分页")
            seen_after_keys.add(marker)
            after_key = next_key
        part = {"batch_index": index,
                "user_ids_sha256": hashlib.sha256(",".join(map(str, batch)).encode("ascii")).hexdigest(),
                "records": records}
        raw = (json.dumps(part, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        part_temp = part_path.with_name(f".{part_path.name}.part")
        try:
            part_temp.write_bytes(raw)
            part_temp.replace(part_path)
        finally:
            if part_temp.exists():
                part_temp.unlink()
        completed[str(index)] = {"sha256": hashlib.sha256(raw).hexdigest(),
                                 "record_count": len(records)}
        save_manifest(paths.manifest, manifest)
        print(f"已查询用户 {min((index + 1) * USER_BATCH_SIZE, len(ordered_ids)):,}/{len(ordered_ids):,}")

    temporary = target.with_name(f".{target.name}.part")
    count = 0
    try:
        with temporary.open("w", encoding="utf-8") as output:
            output.write('{"code":200,"data":[\n')
            for index, batch in enumerate(batches):
                part_path = parts_dir / f"batch-{index:06d}.json"
                for record in read_checkpoint(part_path, index, batch, completed[str(index)]):
                    if count:
                        output.write(",\n")
                    json.dump(record, output, ensure_ascii=False, separators=(",", ":"))
                    count += 1
            output.write('\n],"success":true}\n')
        temporary.replace(target)
    finally:
        if temporary.exists():
            temporary.unlink()
    manifest["status"] = "json_complete"
    manifest["aggregation_record_count"] = count
    manifest["json_sha256"] = file_sha256(target)
    save_manifest(paths.manifest, manifest)
    print(f"聚合 JSON 已导出：{target.resolve()}（{count:,} 条）")
    return target

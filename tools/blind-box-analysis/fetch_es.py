"""连接 ES，自动导出盲盒分析所需的四份聚合响应 JSON。"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import ssl
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from export_report import DEFAULT_BOX_PRICES, query_bodies, validate_period, write_report


INDEX_BY_QUERY = {
    'consumption.json': 'sano_wallet_coin_record',
    'coin-rewards.json': 'sano_wallet_coin_record',
    'diamond-rewards.json': 'sano_wallet_diamond_record',
    'top-users.json': 'sano_wallet_coin_record',
}
QUERY_AGGREGATIONS = {
    'consumption.json': 'by_box',
    'coin-rewards.json': 'by_gift',
    'diamond-rewards.json': 'by_type',
    'top-users.json': 'by_box',
}


@dataclass(frozen=True)
class ESConfig:
    url: str
    username: str
    password: str
    timeout_seconds: int
    ca_cert: Path | None


def load_config(path: Path) -> ESConfig:
    with path.open('r', encoding='utf-8-sig') as source:
        raw = json.load(source)
    if not isinstance(raw, dict):
        raise ValueError('ES 配置文件必须是 JSON 对象')
    url = raw.get('es_url')
    if not isinstance(url, str):
        raise ValueError('config.json 缺少 es_url')
    url = url.strip().rstrip('/')
    parsed = urlsplit(url)
    if (parsed.scheme not in {'http', 'https'} or not parsed.netloc or parsed.query
            or parsed.fragment or parsed.username or parsed.password):
        raise ValueError('es_url 必须是完整的 http(s) 地址，不能包含账号、密码、查询参数或片段')
    username = raw.get('username', '')
    password = raw.get('password', '')
    if not isinstance(username, str) or not isinstance(password, str):
        raise ValueError('username 和 password 必须是字符串')
    if bool(username) != bool(password):
        raise ValueError('username 和 password 必须同时填写或同时留空')
    if username == 'your_username' or password == 'your_password' or 'your-es-host' in url:
        raise ValueError('请先把 config.json 中的 ES 地址、账号和密码示例值改为真实配置')
    timeout = raw.get('timeout_seconds', 300)
    if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 3600:
        raise ValueError('timeout_seconds 必须是 1–3600 的整数')
    ca_value = raw.get('ca_cert')
    if ca_value is not None and (not isinstance(ca_value, str) or not ca_value.strip()):
        raise ValueError('ca_cert 必须是证书文件路径')
    ca_cert = None
    if ca_value:
        ca_cert = Path(ca_value)
        if not ca_cert.is_absolute():
            ca_cert = path.parent / ca_cert
        if not ca_cert.is_file():
            raise ValueError(f'CA 证书文件不存在：{ca_cert}')
    return ESConfig(url, username, password, timeout, ca_cert)


class ESClient:
    def __init__(self, config: ESConfig):
        self.config = config
        self.context = ssl.create_default_context(cafile=str(config.ca_cert) if config.ca_cert else None)

    def search(self, index: str, query: dict[str, Any]) -> tuple[bytes, dict[str, Any]]:
        url = f'{self.config.url}/{index}/_search'
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        if self.config.username:
            secret = f'{self.config.username}:{self.config.password}'.encode('utf-8')
            headers['Authorization'] = 'Basic ' + base64.b64encode(secret).decode('ascii')
        payload = json.dumps(query, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        for attempt in range(3):
            request = Request(url, data=payload, headers=headers, method='POST')
            try:
                with urlopen(request, timeout=self.config.timeout_seconds,
                             context=self.context if url.startswith('https://') else None) as response:
                    content = response.read()
                root = json.loads(content)
                if not isinstance(root, dict):
                    raise ValueError('ES 响应不是 JSON 对象')
                if 'error' in root:
                    raise ValueError(f'ES 返回错误：{str(root["error"])[:500]}')
                shards = root.get('_shards') or {}
                if root.get('timed_out') is True or shards.get('failed', 0):
                    raise ValueError('ES 查询超时或有分片失败，响应不可用于报表')
                return content, root
            except HTTPError as exc:
                detail = exc.read(500).decode('utf-8', errors='replace')
                if exc.code not in {429, 502, 503, 504} or attempt == 2:
                    raise ValueError(f'ES HTTP {exc.code}：{detail}') from exc
            except (URLError, TimeoutError) as exc:
                if attempt == 2:
                    raise ValueError(f'连接 ES 失败：{exc}') from exc
            time.sleep(2**attempt)
        raise RuntimeError('ES 查询重试失败')


def aggregation(root: dict[str, Any], name: str) -> dict[str, Any]:
    aggs = root.get('aggregations', root.get('aggs'))
    if not isinstance(aggs, dict) or not isinstance(aggs.get(name), dict):
        raise ValueError(f'ES 响应缺少 aggregations.{name}')
    return aggs[name]


def save_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.part')
    temporary.write_bytes(content)
    temporary.replace(path)


def read_response(path: Path, name: str) -> dict[str, Any]:
    root = json.loads(path.read_bytes())
    if not isinstance(root, dict) or root.get('timed_out') is True or (root.get('_shards') or {}).get('failed', 0):
        raise ValueError(f'{path}: 已保存的响应无效、超时或有分片失败')
    aggregation(root, name)
    return root


def prepare_output(output_dir: Path, query_set: dict[str, dict[str, Any]],
                   start: str | None, end: str, es_url: str, *, resume: bool) -> None:
    digest = hashlib.sha256(json.dumps(query_set, sort_keys=True).encode('utf-8')).hexdigest()
    # 恢复时同时核对 ES 地址，避免跨集群拼接响应；不在文件中保存账号密码。
    manifest = {'start': start, 'end': end, 'es_url': es_url, 'query_sha256': digest}
    manifest_path = output_dir / 'manifest.json'
    if resume:
        if not manifest_path.is_file():
            raise ValueError(f'{output_dir}: --resume 需要已有 manifest.json')
        existing = json.loads(manifest_path.read_text(encoding='utf-8'))
        if existing != manifest:
            raise ValueError('恢复导出时查询时间或 DSL 与原批次不一致，请使用新目录')
        return
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError(f'{output_dir} 不是空目录；请使用新目录或加 --resume')
    output_dir.mkdir(parents=True, exist_ok=True)
    save_bytes(manifest_path, (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    query_dir = output_dir / 'queries'
    for name, body in query_set.items():
        save_bytes(query_dir / name,
                   (json.dumps(body, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def export_all(args: argparse.Namespace, client: ESClient | None = None) -> Path:
    start = None if args.all_history else args.start
    validate_period(start, args.end)
    config = load_config(args.config) if client is None else None
    es = client or ESClient(config)
    query_set = query_bodies(start, args.end)
    output_dir = args.output_dir
    prepare_output(output_dir, query_set, start, args.end, es.config.url, resume=args.resume)

    for name, agg_name in QUERY_AGGREGATIONS.items():
        target = output_dir / name
        if args.resume and target.is_file():
            existing = read_response(target, agg_name)
            if name == 'top-users.json':
                aggregation(existing, 'by_user_all')
            print(f'已有聚合响应，跳过：{target.name}')
            continue
        content, root = es.search(INDEX_BY_QUERY[name], query_set[name])
        aggregation(root, agg_name)
        if name == 'top-users.json':
            aggregation(root, 'by_user_all')
        save_bytes(target, content)
        print(f'已导出：{target.name}')
    return output_dir


def main() -> None:
    parser = argparse.ArgumentParser(description='连接 ES 并导出盲盒分析的四份聚合 JSON')
    parser.add_argument('--config', required=True, type=Path, help='本地 ES 地址和账号密码配置 JSON')
    period = parser.add_mutually_exclusive_group(required=True)
    period.add_argument('--start', help='查询开始时间，含')
    period.add_argument('--all-history', action='store_true', help='不设开始时间')
    parser.add_argument('--end', required=True, help='固定截止时间，含')
    parser.add_argument('--output-dir', required=True, type=Path, help='响应 JSON 输出目录')
    parser.add_argument('--resume', action='store_true', help='跳过已保存的响应，继续未完成的查询')
    parser.add_argument('--report-output', type=Path, help='可选：导出 JSON 后直接生成 Excel')
    parser.add_argument('--box-prices', type=Path, default=DEFAULT_BOX_PRICES,
                        help='盲盒单价配置 JSON，仅生成 Excel 时使用')
    args = parser.parse_args()
    try:
        output_dir = export_all(args)
        if args.report_output:
            report_args = argparse.Namespace(
                consumption=output_dir / 'consumption.json',
                coin_rewards=output_dir / 'coin-rewards.json',
                diamond_rewards=output_dir / 'diamond-rewards.json',
                top_users=output_dir / 'top-users.json',
                box_prices=args.box_prices,
                start=args.start, all_history=args.all_history,
                end=args.end, output=args.report_output,
            )
            write_report(report_args)
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        parser.exit(2, f'错误：{exc}\n')


if __name__ == '__main__':
    main()

"""生成公会用户收益、成功提现、奖励、充值与同期全平台占比报表。"""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

from fetch_data import (ROOT, MySQLReader, consolidate, digest, es_jobs,
                        fetch_recharge_snapshot, find_resume_paths, load_config,
                        mark_report_complete, output_paths, read_json, replace_with_retry,
                        run_collection, save_json)


COIN_RATE = Decimal(10_000)
DIAMOND_RATE = Decimal(10_000_000)
THREE_PLACES = Decimal("0.001")
NAVY = "17324D"
INK = "263746"
LINE = Side(style="thin", color="AAB9C5")
BORDER = Border(left=LINE, right=LINE, top=LINE, bottom=LINE)
MONEY_FORMAT = '#,##0.000;[Red](#,##0.000)'
TOKEN_FORMAT = '#,##0'
ID_FORMAT = '0'
PERCENT_FORMAT = '0.00%'

GUILD_HEADERS = [
    "公会ID", "当前用户数", "钻石入账合计(tokens)", "钻石入账合计($)", "入账占平台",
    "成功提现($)", "提现占平台", "金币奖励(tokens)", "钻石奖励(tokens)",
    "其他奖励($)", "其他奖励占平台", "成功充值($)", "充值占平台",
    "提现订单奖励(tokens)", "提现订单奖励($)",
    "钻石收益不含55($)", "不含55收益占平台",
]
USER_HEADERS = [
    "公会ID", "用户ID", "昵称", "钻石入账合计(tokens)", "钻石入账合计($)",
    "幸运礼物收益($)", "普通礼物收益($)", "其他收益($)",
    "成功提现($)", "金币奖励(tokens)", "钻石奖励(tokens)", "其他奖励($)",
    "成功充值($)", "提现订单奖励(tokens)", "提现订单奖励($)",
    "钻石收益不含55($)",
]
INCOME_PARTS = ("luckyGiftIncomeDiamondTokens", "normalGiftIncomeDiamondTokens",
                "otherIncomeDiamondTokens")


def raw_amounts(source: dict[str, Any]) -> dict[str, Decimal]:
    order_reward = Decimal(source["withdrawOrderRewardDiamondTokens"]) / DIAMOND_RATE
    income = Decimal(source["incomeDiamondTokens"]) / DIAMOND_RATE
    return {
        "income": income,
        "income_excluding_55": income - order_reward,
        "withdraw_order_reward": order_reward,
        "withdraw": Decimal(str(source["withdrawUsd"])),
        "recharge": Decimal(str(source["rechargeUsd"])),
        "reward": (Decimal(source["rewardCoinTokens"]) / COIN_RATE
                   + Decimal(source["rewardDiamondTokens"]) / DIAMOND_RATE),
    }


def usd(value: Decimal) -> float:
    return float(value.quantize(THREE_PLACES, rounding=ROUND_HALF_UP))


def share(value: Decimal, total: Decimal) -> float | str:
    return float(value / total) if total else "无分母"


def aggregate(data: dict[str, Any]) -> dict[str, Any]:
    meta, platform, users = data["meta"], data["platform"], data["users"]
    proxy_ids = meta["proxy_ids"]
    if not isinstance(proxy_ids, list) or not isinstance(users, list):
        raise ValueError("聚合 JSON 缺少公会 ID 或用户列表")
    groups = {proxy_id: {"users": 0, "incomeDiamondTokens": 0,
                         "withdrawOrderRewardDiamondTokens": 0, "rewardCoinTokens": 0,
                         "rewardDiamondTokens": 0, "withdrawUsd": Decimal(0),
                         "rechargeUsd": Decimal(0)}
              for proxy_id in proxy_ids}
    seen_users = set()
    selected = {"users": 0, "incomeDiamondTokens": 0,
                "withdrawOrderRewardDiamondTokens": 0, "rewardCoinTokens": 0,
                "rewardDiamondTokens": 0, "withdrawUsd": Decimal(0),
                "rechargeUsd": Decimal(0)}
    for user in users:
        user_id, proxy_id = user["userId"], user["proxyId"]
        if proxy_id not in groups or user_id in seen_users:
            raise ValueError("聚合 JSON 包含配置外公会或重复用户 ID")
        if (any(type(user.get(field)) is not int or user[field] < 0 for field in INCOME_PARTS)
                or sum(user[field] for field in INCOME_PARTS)
                + user.get("withdrawOrderRewardDiamondTokens", 0) != user["incomeDiamondTokens"]):
            raise ValueError(f"用户 {user_id} 钻石收益分类与收益总数不一致")
        seen_users.add(user_id)
        for target in (groups[proxy_id], selected):
            target["users"] += 1
            for field in ("incomeDiamondTokens", "withdrawOrderRewardDiamondTokens",
                          "rewardCoinTokens", "rewardDiamondTokens"):
                if type(user[field]) is not int or user[field] < 0:
                    raise ValueError(f"用户 {field} 必须是非负整数")
                target[field] += user[field]
            target["withdrawUsd"] += Decimal(str(user["withdrawUsd"]))
            target["rechargeUsd"] += Decimal(str(user["rechargeUsd"]))
    selected["withdrawUsd"] = str(selected["withdrawUsd"])
    selected["rechargeUsd"] = str(selected["rechargeUsd"])
    for group in groups.values():
        group["withdrawUsd"] = str(group["withdrawUsd"])
        group["rechargeUsd"] = str(group["rechargeUsd"])
    for field in ("incomeDiamondTokens", "withdrawOrderRewardDiamondTokens",
                  "rewardCoinTokens", "rewardDiamondTokens"):
        if selected[field] > platform[field]:
            raise ValueError(f"选定公会 {field} 超过全平台")
    if (any(type(platform.get(field)) is not int or platform[field] < 0
            for field in INCOME_PARTS)
            or sum(platform[field] for field in INCOME_PARTS)
            + platform.get("withdrawOrderRewardDiamondTokens", 0) != platform["incomeDiamondTokens"]):
        raise ValueError("全平台钻石收益分类与收益总数不一致")
    if Decimal(selected["withdrawUsd"]) > Decimal(str(platform["withdrawUsd"])):
        raise ValueError("选定公会成功提现超过全平台")
    if Decimal(selected["rechargeUsd"]) > Decimal(str(platform["rechargeUsd"])):
        raise ValueError("选定公会成功充值超过全平台")
    return {"groups": groups, "selected": selected, "platform": platform, "users": users,
            "meta": meta}


def title(sheet, label: str) -> None:
    sheet.sheet_view.showGridLines = True
    sheet["A1"] = label
    sheet["A1"].font = Font(name="Arial", size=15, bold=True, color=NAVY)
    sheet.row_dimensions[1].height = 25


def header(sheet, row: int, columns: int) -> None:
    for cells in sheet.iter_rows(min_row=row, max_row=row, min_col=1, max_col=columns):
        for cell in cells:
            cell.fill = PatternFill("solid", fgColor=NAVY)
            cell.font = Font(name="Arial", size=10, bold=True, color="FFFFFF")
            cell.alignment = Alignment(horizontal="center", vertical="center")
            cell.border = BORDER
    sheet.row_dimensions[row].height = 27


def body(sheet, first: int, last: int, columns: int) -> None:
    if last < first:
        return
    for row in sheet.iter_rows(min_row=first, max_row=last, min_col=1, max_col=columns):
        for cell in row:
            cell.font = Font(name="Arial", size=10, color=INK)
            cell.alignment = Alignment(vertical="center")
            cell.border = BORDER
        sheet.row_dimensions[row[0].row].height = 21


def write_summary(workbook: Workbook, report: dict[str, Any]) -> None:
    sheet = workbook.active
    sheet.title = "总体对比"
    sheet.sheet_properties.tabColor = NAVY
    title(sheet, "公会用户收益、提现、奖励与充值")
    meta = report["meta"]
    context = [
        ("公会ID", ", ".join(map(str, meta["proxy_ids"]))),
        ("统计起点（含）", meta["start"]),
        ("统计终点（不含）", meta["end"]),
        ("ES 时间区", meta["es_time_zone"]),
        ("公会归属", meta["guild_basis"]),
        ("对象", "当前 proxy_id 属于配置公会的全部用户；平台分母为同期所有用户"),
        ("金额说明", "钻石入账合计＝不含55收益＋提现订单奖励；其他奖励不含55，勿与入账直接相加"),
    ]
    for row_no, (label, value) in enumerate(context, start=3):
        for col, content in ((1, label), (2, value)):
            cell = sheet.cell(row_no, col, content)
            cell.font = Font(name="Arial", size=10, bold=col == 1, color=NAVY if col == 1 else INK)
            cell.border = BORDER
            cell.alignment = Alignment(vertical="center")
        sheet.row_dimensions[row_no].height = 22
    header_row = 11
    for col, label in enumerate(("指标", "选定公会", "全平台", "平台占比"), start=1):
        sheet.cell(header_row, col, label)
    header(sheet, header_row, 4)
    selected, platform = raw_amounts(report["selected"]), raw_amounts(report["platform"])
    for row_no, key, label in ((12, "income", "钻石入账合计($)"),
                               (13, "income_excluding_55", "钻石收益不含55($)"),
                               (14, "withdraw_order_reward", "提现订单奖励55($)"),
                               (15, "withdraw", "成功提现($)"),
                               (16, "reward", "其他奖励($)"),
                               (17, "recharge", "成功充值($)")):
        sheet.cell(row_no, 1, label)
        sheet.cell(row_no, 2, usd(selected[key]))
        sheet.cell(row_no, 3, usd(platform[key]))
        sheet.cell(row_no, 4, share(selected[key], platform[key]))
        for col in (2, 3):
            sheet.cell(row_no, col).number_format = MONEY_FORMAT
        if isinstance(sheet.cell(row_no, 4).value, float):
            sheet.cell(row_no, 4).number_format = PERCENT_FORMAT
    body(sheet, 12, 17, 4)
    sheet["A19"] = "选定公会当前用户数"
    sheet["B19"] = report["selected"]["users"]
    sheet["B19"].number_format = TOKEN_FORMAT
    body(sheet, 19, 19, 2)
    for col, width in {"A": 25, "B": 72, "C": 20, "D": 18}.items():
        sheet.column_dimensions[col].width = width


def write_guilds(workbook: Workbook, report: dict[str, Any]) -> None:
    sheet = workbook.create_sheet("公会汇总")
    title(sheet, "各公会金额与平台占比")
    for column, value in enumerate(GUILD_HEADERS, start=1):
        sheet.cell(3, column, value)
    header(sheet, 3, len(GUILD_HEADERS))
    platform = raw_amounts(report["platform"])
    for proxy_id in report["meta"]["proxy_ids"]:
        group = report["groups"][proxy_id]
        amounts = raw_amounts(group)
        sheet.append([proxy_id, group["users"], group["incomeDiamondTokens"],
                      usd(amounts["income"]), share(amounts["income"], platform["income"]),
                      usd(amounts["withdraw"]), share(amounts["withdraw"], platform["withdraw"]),
                      group["rewardCoinTokens"], group["rewardDiamondTokens"],
                      usd(amounts["reward"]), share(amounts["reward"], platform["reward"]),
                      usd(amounts["recharge"]), share(amounts["recharge"], platform["recharge"]),
                      group["withdrawOrderRewardDiamondTokens"],
                      usd(amounts["withdraw_order_reward"]),
                      usd(amounts["income_excluding_55"]),
                      share(amounts["income_excluding_55"], platform["income_excluding_55"])])
    body(sheet, 4, sheet.max_row, len(GUILD_HEADERS))
    for row in sheet.iter_rows(min_row=4):
        for col in (1,):
            row[col - 1].number_format = ID_FORMAT
        for col in (2, 3, 8, 9, 14):
            row[col - 1].number_format = TOKEN_FORMAT
        for col in (4, 6, 10, 12, 15, 16):
            row[col - 1].number_format = MONEY_FORMAT
        for col in (5, 7, 11, 13, 17):
            if isinstance(row[col - 1].value, float):
                row[col - 1].number_format = PERCENT_FORMAT
    sheet.freeze_panes = "C4"
    sheet.auto_filter.ref = f"A3:Q{max(4, sheet.max_row)}"
    for col in range(1, 18):
        sheet.column_dimensions[get_column_letter(col)].width = 21 if col not in (3, 8, 9, 14, 16) else 26


def write_users(workbook: Workbook, report: dict[str, Any]) -> None:
    sheet = workbook.create_sheet("用户明细")
    sheet.sheet_view.showGridLines = True
    sheet.append(USER_HEADERS)
    header(sheet, 1, len(USER_HEADERS))
    for user in report["users"]:
        amounts = raw_amounts(user)
        row_no = sheet.max_row + 1
        sheet.append([user["proxyId"], user["userId"], "", user["incomeDiamondTokens"],
                      usd(amounts["income"]),
                      *(usd(Decimal(user[field]) / DIAMOND_RATE) for field in INCOME_PARTS),
                      usd(amounts["withdraw"]),
                      user["rewardCoinTokens"], user["rewardDiamondTokens"],
                      usd(amounts["reward"]), usd(amounts["recharge"]),
                      user["withdrawOrderRewardDiamondTokens"],
                      usd(amounts["withdraw_order_reward"]),
                      usd(amounts["income_excluding_55"])])
        # 用户昵称是不可信文本，避免以 = 开头的值被解释为公式。
        nickname = sheet.cell(row_no, 3)
        nickname.value = str(user["nick"])
        nickname.data_type = "s"
    body(sheet, 2, sheet.max_row, len(USER_HEADERS))
    for row in sheet.iter_rows(min_row=2):
        for col in (1, 2):
            row[col - 1].number_format = ID_FORMAT
        for col in (4, 10, 11, 14):
            row[col - 1].number_format = TOKEN_FORMAT
        for col in (5, 6, 7, 8, 9, 12, 13, 15, 16):
            row[col - 1].number_format = MONEY_FORMAT
    sheet.freeze_panes = "C2"
    sheet.auto_filter.ref = f"A1:P{max(2, sheet.max_row)}"
    for index, width in enumerate((15, 15, 25, 25, 25, 22, 22, 19, 19, 25, 25, 19, 19, 26, 25, 25), start=1):
        sheet.column_dimensions[get_column_letter(index)].width = width


def create_workbook(data: dict[str, Any], output: Path) -> None:
    report = aggregate(data)
    workbook = Workbook()
    write_summary(workbook, report)
    write_guilds(workbook, report)
    write_users(workbook, report)
    temporary = output.with_name(f".{output.name}.part.xlsx")
    try:
        workbook.save(temporary)
        verified = load_workbook(temporary, read_only=True, data_only=False)
        try:
            if verified.sheetnames != ["总体对比", "公会汇总", "用户明细"]:
                raise ValueError("工作表缺失或顺序错误")
            if verified["公会汇总"].max_row != len(report["meta"]["proxy_ids"]) + 3:
                raise ValueError("公会汇总行数不匹配")
            if verified["用户明细"].max_row != len(report["users"]) + 1:
                raise ValueError("用户明细行数不匹配")
        finally:
            verified.close()
        replace_with_retry(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()


def rebuild_from_checkpoints(manifest_path: Path,
                             mysql_reader: MySQLReader | None = None) -> Path:
    """复用已保存的 ES 桶；旧快照可只补查 MySQL 成功充值。"""
    manifest_path = manifest_path.resolve()
    prefix, suffix = "guild-income-manifest-", ".json"
    if not manifest_path.name.startswith(prefix) or not manifest_path.name.endswith(suffix):
        raise ValueError("请指定本工具生成的 guild-income-manifest-*.json")
    tag = manifest_path.name[len(prefix):-len(suffix)]
    paths = output_paths(tag, manifest_path.parent)
    manifest = read_json(manifest_path)
    if manifest.get("stage") not in {"json_complete", "report_complete"}:
        raise ValueError("采集尚未完成，不能重建报表")
    identity = manifest.get("identity")
    if not isinstance(identity, dict) or identity.get("schema_version") not in {1, 2, 3, 4}:
        raise ValueError("不支持该检查点版本")
    analysis = identity.get("analysis")
    if not isinstance(analysis, dict):
        raise ValueError("检查点缺少统计配置")
    if (digest(paths["mysql"]) != manifest.get("mysql_sha256")
            or digest(paths["data"]) != manifest.get("data_sha256")):
        raise ValueError("MySQL 快照或聚合 JSON 校验失败")
    snapshot = read_json(paths["mysql"])
    needs_recharge = ("platformRechargeUsd" not in snapshot
                      or any("rechargeUsd" not in user for user in snapshot.get("users", [])))
    output = paths["report"].with_name(f"{paths['report'].stem}-提现订单奖励单列.xlsx")
    enriched_json = paths["data"].with_name(f"{paths['data'].stem}-提现订单奖励单列.json")
    if output.exists() or (needs_recharge and enriched_json.exists()):
        raise ValueError(f"报表或增强 JSON 已存在，拒绝覆盖：{output}")
    if needs_recharge:
        if mysql_reader is None:
            raise ValueError("旧批次缺少充值数据；使用 --fetch-missing-recharge 只补查 MySQL")
        snapshot["platformRechargeUsd"] = str(
            fetch_recharge_snapshot(mysql_reader, snapshot["users"], analysis))
    original = read_json(paths["data"])
    jobs = es_jobs(snapshot["users"])
    totals = consolidate(snapshot, jobs, manifest.get("completed_jobs", {}),
                         paths["parts"], analysis)
    data = {**original, **totals}
    if needs_recharge:
        save_json(enriched_json, data)
    create_workbook(data, output)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="查询指定公会用户并输出收益、提现、奖励、充值占比报表")
    parser.add_argument("--mysql-config", default=str(ROOT / "mysql-config.json"))
    parser.add_argument("--es-config", default=str(ROOT / "es-config.json"))
    parser.add_argument("--analysis-config", default=str(ROOT / "analysis-config.json"))
    parser.add_argument("--resume", action="store_true", help="续跑同一配置的未完成批次")
    parser.add_argument("--rebuild-from-checkpoints", type=Path,
                        help="复用已完成的清单与 ES 检查点重建报表")
    parser.add_argument("--fetch-missing-recharge", action="store_true",
                        help="旧快照缺充值时仅查询 MySQL 充值，不重查 ES")
    args = parser.parse_args()
    if args.rebuild_from_checkpoints:
        if args.resume:
            parser.error("--resume 与 --rebuild-from-checkpoints 不能同时使用")
        if args.fetch_missing_recharge:
            config = load_config(Path(args.mysql_config), Path(args.es_config),
                                 Path(args.analysis_config))
            old_identity = read_json(args.rebuild_from_checkpoints).get("identity", {})
            mysql = config["mysql"]
            if (old_identity.get("analysis") != config["analysis"]
                    or old_identity.get("mysql_host") != mysql["host"]
                    or old_identity.get("mysql_port") != mysql.get("port", 3306)
                    or old_identity.get("mysql_database") != mysql["database"]):
                raise ValueError("当前 MySQL/统计配置与检查点不一致，拒绝混用批次")
            database = MySQLReader(mysql)
            try:
                output = rebuild_from_checkpoints(args.rebuild_from_checkpoints, database)
            finally:
                database.close()
        else:
            output = rebuild_from_checkpoints(args.rebuild_from_checkpoints)
        print(f"重建报表：{output}")
        return
    if args.fetch_missing_recharge:
        parser.error("--fetch-missing-recharge 只能与 --rebuild-from-checkpoints 一起使用")
    config = load_config(Path(args.mysql_config), Path(args.es_config), Path(args.analysis_config))
    if args.resume:
        paths = find_resume_paths(config)
    else:
        tag = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        paths = output_paths(tag)
    data_path = run_collection(config, paths, resume=args.resume)
    create_workbook(read_json(data_path), paths["report"])
    mark_report_complete(paths)
    print(f"已生成分析报表：{paths['report'].resolve()}")


if __name__ == "__main__":
    main()

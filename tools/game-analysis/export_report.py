"""游戏消费、普通返奖、用户 Jackpot 奖励及固定比例利润的五表分析。"""

from __future__ import annotations

import argparse
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any, Iterable

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

from fetch_data import (FIELDS, ROOT, collect, consolidate, digest, empty_metrics,
                        find_resume_paths, jobs, load_config, mark_report_complete,
                        output_paths, parse_time, read_json, replace_with_retry,
                        validate_analysis)


NAVY, INK = "17324D", "263746"
LINE = Side(style="thin", color="AAB9C5")
BORDER = Border(left=LINE, right=LINE, top=LINE, bottom=LINE)
MONEY_FORMAT = '#,##0.000;[Red](#,##0.000)'
FORMATS = {"money": MONEY_FORMAT, "percent": "0.00%", "count": "#,##0",
           "id": "0", "date": "yyyy-mm-dd", "time": "yyyy-mm-dd hh:mm:ss", "text": "@"}
SHEETS = ["分析汇总", "游戏汇总", "国家汇总", "国家游戏汇总", "每日游戏趋势"]


def amounts(row: dict[str, Any], analysis: dict[str, Any]) -> dict[str, Decimal]:
    rate = Decimal(analysis["coin_tokens_per_usd"])
    consume = Decimal(row["consumeTokens"]) / rate
    return {"consumeUsd": consume, "rewardUsd": Decimal(row["rewardTokens"]) / rate,
            "jackpotUsd": Decimal(row["jackpotTokens"]) / rate,
            "anchorUsd": consume * Decimal(analysis["anchor_rate"]),
            "poolUsd": consume * Decimal(analysis["jackpot_pool_rate"]),
            "feeUsd": consume * Decimal(analysis["service_fee_rate"]),
            "profitUsd": consume * Decimal(analysis["platform_profit_rate"])}


def usd(value: Decimal) -> float:
    # 只在最终写 Excel 时转为数值；原始整数与金额计算不经过 float。
    return float(value.quantize(Decimal("0.001"), rounding=ROUND_HALF_UP))


def share(value: int, total: int) -> float | str:
    return float(Decimal(value) / Decimal(total)) if total else "无分母"


def display_metrics(row: dict[str, Any], analysis: dict[str, Any], denominator: int) -> dict[str, Any]:
    money = amounts(row, analysis)
    average = (usd(money["consumeUsd"] / row["consumerCount"])
               if row["consumerCount"] else "无分母")
    missing_user = row.get("missingUserConsumeRecords", 0) > 0
    return {**row, **{name: usd(value) for name, value in money.items()},
            "consumeShare": share(row["consumeTokens"], denominator),
            "averageUsd": "用户ID缺失" if missing_user else average,
            "rewardRate": share(row["rewardTokens"], row["consumeTokens"]),
            "jackpotRate": share(row["jackpotTokens"], row["consumeTokens"]),
            "maxUserShare": "用户ID缺失" if missing_user else share(row["maxConsumerTokens"], row["consumeTokens"])}


def profit_label(analysis: dict[str, Any]) -> str:
    rate = (Decimal(analysis["platform_profit_rate"]) * 100).normalize()
    return f"平台利润(固定{rate:f}%)($)"


def common_columns(analysis: dict[str, Any]) -> list[tuple[str, str, int, str]]:
    return [("consumerCount", "消费人数", 15, "count"),
            ("consumeRecords", "消费记录数", 17, "count"),
            ("consumeUsd", "消费金额($)", 22, "money"),
            ("consumeShare", "占可用游戏消费", 22, "percent"),
            ("averageUsd", "人均消费($)", 20, "money"),
            ("rewardUsd", "普通游戏返奖($)", 23, "money"),
            ("rewardRate", "普通返奖率", 19, "percent"),
            ("jackpotUsd", "用户游戏Jackpot奖励($)", 30, "money"),
            ("jackpotRate", "用户Jackpot奖励率", 25, "percent"),
            ("anchorUsd", "主播利润($)", 21, "money"),
            ("poolUsd", "Jackpot奖励池计提($)", 29, "money"),
            ("feeUsd", "游戏服务费($)", 22, "money"),
            ("profitUsd", profit_label(analysis), 33, "money")]


def write_cell(sheet, row: int, column: int, value: Any, kind: str = "text", header: bool = False) -> None:
    cell = sheet.cell(row, column, value)
    # 游戏名称、厂商及国家名称均按不可信文本处理，防止公式注入。
    if isinstance(value, str):
        cell.data_type = "s"
    cell.font = Font(name="Arial", size=10, bold=header, color="FFFFFF" if header else INK)
    cell.border = BORDER
    cell.alignment = Alignment(horizontal="center" if header else ("right" if kind != "text" else "left"),
                               vertical="center", wrap_text=header)
    if header:
        cell.fill = PatternFill("solid", fgColor=NAVY)
    else:
        cell.number_format = FORMATS[kind]


def table(sheet, columns: list[tuple[str, str, int, str]], rows: Iterable[dict[str, Any]],
          start: int = 1, filtering: bool = True) -> int:
    sheet.sheet_view.showGridLines = True
    for col, (_, label, width, _) in enumerate(columns, start=1):
        write_cell(sheet, start, col, label, header=True)
        sheet.column_dimensions[get_column_letter(col)].width = width
    sheet.row_dimensions[start].height = 34
    row_no = start
    for values in rows:
        row_no += 1
        if row_no > 1_048_576:
            raise ValueError("明细超过 Excel 单表行数限制，请缩短统计时间段")
        for col, (name, _, _, kind) in enumerate(columns, start=1):
            write_cell(sheet, row_no, col, values.get(name), kind)
        sheet.row_dimensions[row_no].height = 22
    if filtering:
        sheet.auto_filter.ref = f"A{start}:{get_column_letter(len(columns))}{row_no}"
        sheet.freeze_panes = f"C{start + 1}"
    return row_no


def sorted_games(data: dict[str, Any]) -> list[dict[str, Any]]:
    return sorted(data["games"], key=lambda row: (-row["consumeTokens"], row["gameId"] is None,
                                                 row["gameId"] if row["gameId"] is not None else 0))


def game_info(game: dict[str, Any]) -> dict[str, Any]:
    return {**game, "gameId": game["gameId"] if game["gameId"] is not None else "缺失ID",
            "name": game["chName"] or game["enName"] or "未命名游戏",
            "usageStatus": "使用中" if game["status"] == 1 else "未启用",
            "createTime": parse_time(game["createTime"]) if game["createTime"] else None}


def write_summary(workbook: Workbook, data: dict[str, Any]) -> None:
    sheet = workbook.active
    sheet.title = SHEETS[0]
    sheet.sheet_properties.tabColor = NAVY
    sheet.sheet_view.showGridLines = True
    sheet["A1"] = "游戏消费与利润分析"
    sheet["A1"].font = Font(name="Arial", size=15, bold=True, color=NAVY)
    sheet.row_dimensions[1].height = 27
    analysis, totals = data["meta"], data["totals"]
    percent = lambda key: f"{(Decimal(analysis[key]) * 100).normalize():f}%"
    context = [("统计起点（含）", analysis["start"]), ("统计终点（不含）", analysis["end"]),
               ("ES 时间区", analysis["es_time_zone"]),
               ("游戏范围", analysis["gameScope"] + "；状态以字典采集时间为准"),
               ("国家口径", analysis["countryBasis"]),
               ("消费／普通返奖／用户Jackpot", "金币业务类型21(-1)、22(+1)、26(+1)，两种奖励分别展示"),
               ("金币美元换算", f"{analysis['coin_tokens_per_usd']} tokens = 1 美元"),
               ("主播／奖池／服务费比例", f"{percent('anchor_rate')} / {percent('jackpot_pool_rate')} / {percent('service_fee_rate')}"),
               ("平台利润计算", f"消费 × ({percent('game_margin_rate')} - {percent('anchor_rate')} - {percent('jackpot_pool_rate')} - {percent('service_fee_rate')}) = 消费 × {percent('platform_profit_rate')}"),
               ("数据来源", "ES sano_wallet_coin_record；MySQL sano_game_list / sano_user / sano_config_country"),
               ("字典采集时间", analysis["dictionaryCapturedAt"]),
               ("人数说明", "消费人数按用户精确去重；各游戏人数可重叠；每日趋势不统计去重人数")]
    for row, (label, value) in enumerate(context, start=3):
        write_cell(sheet, row, 1, label)
        write_cell(sheet, row, 2, value)
        sheet.row_dimensions[row].height = 23
    games = data["games"]
    money = amounts(totals, analysis)
    metrics = [("可用游戏数", len(games), "游戏快照 status=1，含期内零消费游戏", "count"),
               ("有消费游戏数", sum(row["consumeRecords"] > 0 for row in games), "按消费记录是否存在判断", "count"),
               ("零消费游戏数", sum(row["consumeRecords"] == 0 for row in games), "当前状态、创建时间见游戏汇总", "count"),
               ("消费人数", totals["consumerCount"], "期间可用游戏合并后去重", "count"),
               ("消费记录数", totals["consumeRecords"], "业务类型21且status=-1的记录数", "count"),
               ("消费金额($)", usd(money["consumeUsd"]), "可用游戏消费", "money"),
               ("普通游戏返奖($)", usd(money["rewardUsd"]), "业务类型22且status=1", "money"),
               ("普通返奖率", share(totals["rewardTokens"], totals["consumeTokens"]), "普通返奖 / 消费", "percent"),
               ("用户游戏Jackpot奖励($)", usd(money["jackpotUsd"]), "业务类型26且status=1", "money"),
               ("用户Jackpot奖励率", share(totals["jackpotTokens"], totals["consumeTokens"]), "用户Jackpot奖励 / 消费", "percent"),
               ("主播利润($)", usd(money["anchorUsd"]), f"消费 × {percent('anchor_rate')}", "money"),
               ("Jackpot奖励池计提($)", usd(money["poolUsd"]), f"消费 × {percent('jackpot_pool_rate')}，与用户实得奖励分别展示", "money"),
               ("游戏服务费($)", usd(money["feeUsd"]), f"消费 × {percent('game_margin_rate')} × {percent('service_fee_share')}", "money"),
               (profit_label(analysis), usd(money["profitUsd"]), "按配置固定比例计算", "money"),
               ("最大消费用户占比", display_metrics(totals, analysis, totals["consumeTokens"])["maxUserShare"], "同一用户可用游戏消费合并", "percent")]
    for col, value in enumerate(("指标", "数值", "口径"), start=1):
        write_cell(sheet, 15, col, value, header=True)
    sheet.row_dimensions[15].height = 30
    for row, (label, value, note, kind) in enumerate(metrics, start=16):
        write_cell(sheet, row, 1, label)
        write_cell(sheet, row, 2, value, kind)
        write_cell(sheet, row, 3, note)
        sheet.row_dimensions[row].height = 23
    warning_labels = {"missingUserCount": "资料未匹配用户数", "unknownCountryUserCount": "国家为空用户数",
                      "missingUserIdRecords": "用户ID缺失记录数", "unmatchedGameCount": "未匹配配置游戏数"}
    row = 32
    for key, label in warning_labels.items():
        write_cell(sheet, row, 1, label)
        write_cell(sheet, row, 2, data["warnings"][key], "count")
        sheet.row_dimensions[row].height = 23
        row += 1
    ranks = [("消费最高游戏（按消费金额降序）", sorted_games(data)[:10]),
             ("消费最低游戏（按消费金额升序，含零消费）", sorted_games(data)[-10:][::-1])]
    columns = [("rank", "排名", 35, "count"), ("gameId", "游戏ID", 30, "id"),
               ("name", "游戏名称", 57, "text"), ("consumeUsd", "消费金额($)", 23, "money"),
               ("consumerCount", "消费人数", 17, "count"), ("consumeShare", "占可用游戏消费", 22, "percent")]
    for label, selected in ranks:
        row += 2
        sheet.cell(row, 1, label).font = Font(name="Arial", size=12, bold=True, color=NAVY)
        row = table(sheet, columns,
                    ({**display_metrics(item, analysis, totals["consumeTokens"]),
                      **game_info(item), "rank": rank} for rank, item in enumerate(selected, start=1)),
                    start=row + 1, filtering=False)


def write_details(workbook: Workbook, data: dict[str, Any]) -> None:
    analysis, total = data["meta"], data["totals"]["consumeTokens"]
    games = {row["gameId"]: row for row in data["games"]}
    countries = {row["countryCode"]: row for row in data["countries"]}
    common = common_columns(analysis)
    game_columns = [("rank", "消费金额排名", 18, "count"), ("gameId", "游戏ID", 16, "id"),
                    ("name", "游戏名称", 29, "text"), ("enName", "英文名称", 27, "text"),
                    ("producerCode", "厂商", 20, "text"), ("usageStatus", "使用状态", 18, "text"),
                    ("deleteFlag", "删除标记(原值)", 20, "id"), ("createTime", "配置创建时间", 24, "time")]
    sheet = workbook.create_sheet(SHEETS[1])
    table(sheet, game_columns + common + [("activeDays", "有消费天数", 18, "count"),
                                        ("maxUserShare", "最大消费用户占比", 25, "percent")],
          ({**display_metrics(row, analysis, total), **game_info(row), "rank": rank}
           for rank, row in enumerate(sorted_games(data), start=1)))
    sheet.freeze_panes = "D2"
    sorted_countries = sorted(data["countries"], key=lambda row: (-row["consumeTokens"], row["countryCode"]))
    country_columns = [("countryCode", "国家代码", 16, "text"), ("countryName", "国家名称", 28, "text")]
    sheet = workbook.create_sheet(SHEETS[2])
    table(sheet, [("rank", "消费金额排名", 18, "count")] + country_columns + common,
          ({**display_metrics(row, analysis, total), "rank": rank}
           for rank, row in enumerate(sorted_countries, start=1)))
    order = {row["countryCode"]: i for i, row in enumerate(sorted_countries)}
    groups = sorted(data["countryGames"], key=lambda row: (order[row["countryCode"]],
                    -row["consumeTokens"], row["gameId"] is None, row["gameId"] or 0))

    def country_rows():
        current, rank = None, 0
        for row in groups:
            if row["countryCode"] != current:
                current, rank = row["countryCode"], 0
            rank += 1
            yield {**game_info(games[row["gameId"]]),
                   **display_metrics(row, analysis, countries[current]["consumeTokens"]),
                   "gameId": row["gameId"] if row["gameId"] is not None else "缺失ID",
                   "countryName": countries[current]["countryName"], "rank": rank}

    sheet = workbook.create_sheet(SHEETS[3])
    group_common = [(key, "占本国可用游戏消费" if key == "consumeShare" else label,
                     27 if key == "consumeShare" else width, kind)
                    for key, label, width, kind in common]
    table(sheet, country_columns + [("rank", "国家内金额排名", 20, "count")]
          + game_columns[1:6] + group_common, country_rows())
    sheet.freeze_panes = "D2"
    observed = {(row["day"], row["gameId"]): row for row in data["dailyGames"]}

    def day_rows():
        for job in jobs(analysis)[1:]:
            for game in sorted_games(data):
                raw = observed.get((job["day"], game["gameId"]))
                if raw is None and game["createTime"] and parse_time(game["createTime"]) >= parse_time(job["end"]):
                    continue
                row = raw or empty_metrics()
                yield {**game_info(game), **{key: usd(value) for key, value in amounts(row, analysis).items()},
                       "day": datetime.strptime(job["day"], "%Y-%m-%d"),
                       "consumeRecords": row["consumeRecords"]}

    sheet = workbook.create_sheet(SHEETS[4])
    daily_columns = [("day", "日期", 16, "date")] + game_columns[1:6]
    daily_columns += [column for column in common if column[0] in {
        "consumeRecords", "consumeUsd", "rewardUsd", "jackpotUsd", "anchorUsd", "poolUsd", "feeUsd", "profitUsd"}]
    table(sheet, daily_columns, day_rows())
    sheet.freeze_panes = "D2"


def create_workbook(data: dict[str, Any], output: Path) -> None:
    if data.get("schemaVersion") != 2:
        raise ValueError("请使用新版可用游戏聚合 JSON；旧批次请用 --rebuild-from-checkpoints 重建")
    allowed = set(data["meta"]["availableGameIds"])
    game_ids = {game["gameId"] for game in data["games"]}
    if (any(game["status"] != 1 for game in data["games"]) or not game_ids <= allowed
            or any(row["gameId"] not in game_ids for name in ("countryGames", "dailyGames") for row in data[name])):
        raise ValueError("报表数据包含可用游戏列表以外的游戏")
    validate_analysis(data["meta"])
    # 校验使用原始整数，而不是把已显示三位小数的 Excel 金额相加。
    for collection in ("games", "countries", "countryGames", "dailyGames"):
        if any(sum(row[field] for row in data[collection]) != data["totals"][field] for field in FIELDS):
            raise ValueError(f"{collection} 与总体金额或记录数不一致")
    if output.exists():
        raise ValueError(f"报表已存在，拒绝覆盖：{output}")
    workbook = Workbook()
    temporary = output.with_name(f".{output.name}.part.xlsx")
    try:
        write_summary(workbook, data)
        write_details(workbook, data)
        workbook.save(temporary)
        checked = load_workbook(temporary, read_only=True, data_only=False)
        try:
            if checked.sheetnames != SHEETS:
                raise ValueError("工作表缺失或顺序错误")
            if checked[SHEETS[1]].max_row != len(data["games"]) + 1:
                raise ValueError("游戏汇总行数不一致")
        finally:
            checked.close()
        replace_with_retry(temporary, output)
    finally:
        workbook.close()
        if temporary.exists():
            temporary.unlink()


def rebuild_from_checkpoints(manifest_path: Path) -> Path:
    manifest_path = manifest_path.resolve()
    name = manifest_path.name
    if not name.startswith("game-analysis-manifest-") or not name.endswith(".json"):
        raise ValueError("请指定 game-analysis-manifest-批次.json")
    paths = output_paths(name.removeprefix("game-analysis-manifest-").removesuffix(".json"), manifest_path.parent)
    manifest = read_json(manifest_path)
    if manifest.get("stage") not in {"json_complete", "report_complete"}:
        raise ValueError("该批次尚未采集完成")
    if digest(paths["data"]) != manifest.get("data_sha256"):
        raise ValueError("最终 JSON 校验失败")
    data = consolidate(paths, manifest)
    if data != read_json(paths["data"]):
        raise ValueError("检查点重算与最终 JSON 不一致")
    if manifest["identity"]["schema_version"] == 1:
        # 旧版采集了所有游戏，必须从用户游戏桶重新计人数，不能只删旧汇总行。
        # 使用原游戏状态快照，离线重建并不代表重新查询今天的启用状态。
        data = consolidate(paths, manifest, only_available=True)
    output = paths["report"].with_name(paths["report"].stem + "-可用游戏-重建-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".xlsx")
    create_workbook(data, output)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="只读采集游戏金币聚合，输出消费、两类奖励及固定比例利润分析")
    parser.add_argument("--mysql-config", type=Path, default=ROOT / "mysql-config.json")
    parser.add_argument("--es-config", type=Path, default=ROOT / "es-config.json")
    parser.add_argument("--analysis-config", type=Path, default=ROOT / "analysis-config.json")
    parser.add_argument("--resume", action="store_true", help="续跑相同配置的未完成批次")
    parser.add_argument("--rebuild-from-checkpoints", type=Path, help="从已完成批次离线重建，无需连接 MySQL/ES")
    args = parser.parse_args()
    if args.rebuild_from_checkpoints:
        if args.resume:
            parser.error("--resume 不能与离线重建同时使用")
        print(f"已重建：{rebuild_from_checkpoints(args.rebuild_from_checkpoints)}")
        return
    config = load_config(args.mysql_config, args.es_config, args.analysis_config)
    paths = (find_resume_paths(config) if args.resume else
             output_paths(datetime.now().strftime("%Y%m%d-%H%M%S-%f")))
    path = collect(config, paths, resume=args.resume)
    create_workbook(read_json(path), paths["report"])
    mark_report_complete(paths)
    print(f"聚合 JSON：{path}")
    print(f"执行 SQL：{paths['sql']}")
    print(f"分析报表：{paths['report']}")


if __name__ == "__main__":
    main()

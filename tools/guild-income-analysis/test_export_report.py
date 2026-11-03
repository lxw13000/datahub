"""离线验证 MySQL 只读、ES 聚合、续跑和 Excel 结果。"""

from __future__ import annotations

import json
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from openpyxl import load_workbook

from export_report import aggregate, create_workbook, raw_amounts, rebuild_from_checkpoints
from fetch_data import (COIN_INDEX, DIAMOND_INDEX, ROOT, MySQLReader, es_query,
                        exact_nonnegative, export_es_job, fetch_mysql_snapshot, load_config,
                        output_paths, read_json, run_collection, save_json)


ANALYSIS = {
    "proxy_ids": [133430, 136474, 135937, 135110, 137229],
    "start": "2026-09-21 00:00:00",
    "end": "2026-10-21 00:00:00",
    "es_time_zone": "+08:00",
    "coin_reward_types": [62],
    "diamond_income_types": [2, 12, 55],
    "diamond_reward_types": [55],
}
CONFIG = {"mysql": {"host": "mock", "port": 3306, "database": "sano"},
          "es": {"es_url": "https://mock:9200"}, "analysis": ANALYSIS}


class FakeMySQL:
    def __init__(self):
        self.queries = []

    def query(self, sql, parameters):
        self.queries.append((sql, parameters))
        if "FROM sano_user" in sql:
            yield {"user_id": 101, "proxy_id": 133430, "nick": "=BAD()"}
            yield {"user_id": 102, "proxy_id": 133430, "nick": "零金额用户"}
            yield {"user_id": 201, "proxy_id": 136474, "nick": "另一公会"}
        elif "FROM sano_wallet_withdraw" in sql and "GROUP BY user_id" not in sql:
            yield {"usd": Decimal("100.000")}
        elif "FROM sano_wallet_withdraw" in sql:
            yield {"user_id": 101, "usd": Decimal("5.123")}
            yield {"user_id": 201, "usd": Decimal("9.000")}
        elif "FROM sano_wallet_recharge" in sql and "GROUP BY user_id" not in sql:
            yield {"usd": Decimal("200.00")}
        elif "FROM sano_wallet_recharge" in sql:
            yield {"user_id": 101, "usd": Decimal("3.50")}
            yield {"user_id": 201, "usd": Decimal("7.00")}
        else:
            raise AssertionError(sql)


class FakeES:
    def __init__(self):
        self.calls = []

    def search(self, index, body):
        self.calls.append((index, body))
        filters = body["query"]["bool"]["filter"]
        assert body["size"] == 0
        assert filters[0] == {"term": {"status": 1}}
        assert filters[2]["range"]["create_time"] == {
            "gte": ANALYSIS["start"], "lt": ANALYSIS["end"], "time_zone": "+08:00"}
        cohort = len(filters) == 4
        if cohort:
            assert filters[3]["terms"]["user_id"] == [101, 102, 201]
        if index == COIN_INDEX:
            rows = [(101, 62, 10000)] if cohort else [(None, 62, 20000)]
        elif index == DIAMOND_INDEX:
            rows = ([(101, 2, 10000000), (101, 55, 5000000), (201, 12, 5000000)]
                    if cohort else [(None, 2, 25000000), (None, 12, 5000000),
                                    (None, 55, 10000000)])
        else:
            raise AssertionError(index)
        buckets = []
        for user_id, business, tokens in rows:
            key = {"businessType": business}
            if user_id is not None:
                key["userId"] = user_id
            buckets.append({"key": key, "tokens": {"value": float(tokens)}, "doc_count": 1})
        return {"_shards": {"failed": 0}, "timed_out": False,
                "aggregations": {"groups": {"buckets": buckets}}}


class GuildAnalysisTests(unittest.TestCase):
    def test_manifest_replace_retries_transient_windows_lock(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            path = Path(directory) / "manifest.json"
            path.write_text('{"stage":"old"}', encoding="utf-8")
            original = Path.replace
            attempts = 0

            def flaky_replace(source, destination):
                nonlocal attempts
                attempts += 1
                if attempts < 3:
                    raise PermissionError(5, "模拟文件短暂占用")
                return original(source, destination)

            with patch.object(Path, "replace", flaky_replace), patch("fetch_data.time.sleep"):
                save_json(path, {"stage": "new"})
            self.assertEqual(attempts, 3)
            self.assertEqual(read_json(path)["stage"], "new")

    def test_mysql_queries_are_select_only_and_recharge_matches_supplied_sql(self):
        fake = FakeMySQL()
        snapshot = fetch_mysql_snapshot(fake, ANALYSIS)
        self.assertEqual(len(snapshot["users"]), 3)
        self.assertEqual(snapshot["platformWithdrawUsd"], "100.000")
        self.assertEqual(snapshot["platformRechargeUsd"], "200.00")
        self.assertEqual([u["withdrawUsd"] for u in snapshot["users"]],
                         ["5.123", "0", "9.000"])
        self.assertEqual([u["rechargeUsd"] for u in snapshot["users"]],
                         ["3.50", "0", "7.00"])
        self.assertEqual(len(fake.queries), 5)
        self.assertTrue(all(sql.lstrip().upper().startswith("SELECT ") and ";" not in sql
                            for sql, _ in fake.queries))
        self.assertNotIn("user_id IN", fake.queries[1][0])
        self.assertIn("user_id IN", fake.queries[2][0])
        self.assertEqual(fake.queries[1][1], (ANALYSIS["start"], ANALYSIS["end"]))
        self.assertNotIn("user_id IN", fake.queries[3][0])
        self.assertIn("user_id IN", fake.queries[4][0])
        self.assertIn("producer_type IN (0, 1)", fake.queries[3][0])
        self.assertIn("usdt_amount", fake.queries[3][0])
        self.assertIn("coin_business > 0", fake.queries[3][0])
        self.assertIn("status = 2", fake.queries[3][0])
        self.assertEqual(fake.queries[3][1], (ANALYSIS["start"], ANALYSIS["end"]))

    def test_mysql_reader_rejects_non_select_even_with_valid_connection(self):
        reader = object.__new__(MySQLReader)

        class Connection:
            def cursor(self):
                raise AssertionError("非 SELECT 不应打开数据库游标")

        reader.connection = Connection()
        with self.assertRaisesRegex(ValueError, "只允许执行单条 SELECT"):
            list(reader.query("UPDATE sano_user SET proxy_id=0", ()))
        with self.assertRaisesRegex(ValueError, "只允许执行单条 SELECT"):
            list(reader.query("SELECT 1; DELETE FROM sano_user", ()))

    def test_es_query_scope_and_precision_guard(self):
        platform = es_query(ANALYSIS, DIAMOND_INDEX, None)
        guild = es_query(ANALYSIS, DIAMOND_INDEX, [101])
        self.assertEqual(len(platform["query"]["bool"]["filter"]), 3)
        self.assertEqual(guild["query"]["bool"]["filter"][3],
                         {"terms": {"user_id": [101]}})
        self.assertEqual(guild["aggs"]["groups"]["composite"]["sources"][0],
                         {"userId": {"terms": {"field": "user_id"}}})
        with self.assertRaises(ValueError):
            exact_nonnegative(2**53, "tokens")
        with self.assertRaises(ValueError):
            exact_nonnegative(1.5, "tokens")

    def test_es_composite_pagination(self):
        class PagedES:
            def search(self, index, body):
                after = body["aggs"]["groups"]["composite"].get("after")
                business = 62 if after is None else 64
                result = {"_shards": {"failed": 0}, "timed_out": False,
                          "aggregations": {"groups": {"buckets": [
                              {"key": {"userId": 101, "businessType": business},
                               "tokens": {"value": 10000.0}, "doc_count": 1},
                          ]}}}
                if after is None:
                    result["aggregations"]["groups"]["after_key"] = {
                        "userId": 101, "businessType": 62}
                return result

        job = {"name": "paged", "index": COIN_INDEX, "ids": [101]}
        analysis = {**ANALYSIS, "coin_reward_types": [62, 64]}
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            destination = Path(directory) / "paged.jsonl"
            self.assertEqual(export_es_job(PagedES(), analysis, job, destination), 2)
            self.assertEqual(len(destination.read_text(encoding="utf-8").splitlines()), 3)

    def test_empty_guilds_and_zero_platform_denominator(self):
        data = {"meta": {"proxy_ids": ANALYSIS["proxy_ids"], "start": ANALYSIS["start"],
                         "end": ANALYSIS["end"], "es_time_zone": ANALYSIS["es_time_zone"],
                         "guild_basis": "sano_user.proxy_id（采集时当前值）"},
                "platform": {"incomeDiamondTokens": 0, "normalGiftIncomeDiamondTokens": 0,
                             "luckyGiftIncomeDiamondTokens": 0, "otherIncomeDiamondTokens": 0,
                             "withdrawOrderRewardDiamondTokens": 0,
                             "rewardDiamondTokens": 0, "rewardCoinTokens": 0,
                             "withdrawUsd": "0", "rechargeUsd": "0"}, "users": []}
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            output = Path(directory) / "empty.xlsx"
            create_workbook(data, output)
            book = load_workbook(output, read_only=True)
            try:
                self.assertEqual(book["总体对比"]["D12"].value, "无分母")
                self.assertEqual(book["公会汇总"]["B4"].value, 0)
                self.assertEqual(book["用户明细"].max_row, 1)
            finally:
                book.close()

    def test_config_examples_and_time_exclusive(self):
        example = read_json(ROOT / "analysis-config.example.json")
        self.assertEqual(example["proxy_ids"], ANALYSIS["proxy_ids"])
        self.assertEqual(example["start"], ANALYSIS["start"])
        self.assertEqual(example["end"], ANALYSIS["end"])
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            base = Path(directory)
            mysql = {"host": "mock", "database": "sano", "username": "report_reader",
                     "password": "secret"}
            es = {"es_url": "https://mock:9200", "username": "", "password": ""}
            for name, value in (("mysql", mysql), ("es", es), ("analysis", example)):
                (base / f"{name}.json").write_text(json.dumps(value), encoding="utf-8")
            loaded = load_config(base / "mysql.json", base / "es.json", base / "analysis.json")
            self.assertEqual(loaded["analysis"]["end"], "2026-10-21 00:00:00")
            self.assertEqual(loaded["analysis"]["proxy_ids"], ANALYSIS["proxy_ids"])

    def test_end_to_end_json_and_workbook(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("mock", Path(directory))
            es = FakeES()
            data_path = run_collection(CONFIG, paths, mysql_reader=FakeMySQL(), es_reader=es)
            data = read_json(data_path)
            report = aggregate(data)
            self.assertEqual(len(es.calls), 4)
            self.assertEqual(report["selected"]["users"], 3)
            self.assertEqual(report["selected"]["incomeDiamondTokens"], 20000000)
            self.assertEqual(report["users"][0]["normalGiftIncomeDiamondTokens"], 10000000)
            self.assertEqual(report["users"][0]["otherIncomeDiamondTokens"], 0)
            self.assertEqual(report["users"][0]["withdrawOrderRewardDiamondTokens"], 5000000)
            self.assertEqual(report["users"][2]["luckyGiftIncomeDiamondTokens"], 5000000)
            self.assertEqual(report["selected"]["rewardCoinTokens"], 10000)
            self.assertEqual(report["selected"]["rewardDiamondTokens"], 0)
            self.assertEqual(report["selected"]["withdrawOrderRewardDiamondTokens"], 5000000)
            self.assertEqual(raw_amounts(report["selected"])["income"], Decimal("2"))
            self.assertEqual(raw_amounts(report["selected"])["income_excluding_55"], Decimal("1.5"))
            self.assertEqual(raw_amounts(report["selected"])["withdraw_order_reward"], Decimal("0.5"))
            self.assertEqual(raw_amounts(report["selected"])["withdraw"], Decimal("14.123"))
            self.assertEqual(raw_amounts(report["selected"])["reward"], Decimal("1"))
            self.assertEqual(raw_amounts(report["selected"])["recharge"], Decimal("10.50"))
            self.assertEqual(raw_amounts(report["platform"])["recharge"], Decimal("200.00"))
            self.assertEqual(raw_amounts(report["platform"])["reward"], Decimal("2"))
            self.assertEqual(report["groups"][135937]["users"], 0)
            self.assertEqual(report["groups"][133430]["users"], 2)
            self.assertEqual(run_collection(CONFIG, paths, resume=True), data_path)
            create_workbook(data, paths["report"])
            book = load_workbook(paths["report"], read_only=False, data_only=False)
            try:
                self.assertEqual(book.sheetnames, ["总体对比", "公会汇总", "用户明细"])
                summary = book["总体对比"]
                self.assertEqual(summary["B12"].value, 2)
                self.assertEqual(summary["C12"].value, 4)
                self.assertEqual(summary["D12"].value, 0.5)
                self.assertEqual(summary["B13"].value, 1.5)
                self.assertEqual(summary["B14"].value, 0.5)
                self.assertEqual(summary["D14"].value, 0.5)
                self.assertEqual(summary["B15"].value, 14.123)
                self.assertAlmostEqual(summary["D15"].value, 0.14123)
                self.assertEqual(summary["B16"].value, 1)
                self.assertEqual(summary["D16"].value, 0.5)
                self.assertEqual(summary["B17"].value, 10.5)
                self.assertEqual(summary["C17"].value, 200)
                self.assertEqual(summary["D17"].value, 0.0525)
                guilds = book["公会汇总"]
                self.assertEqual(guilds.max_row, 8)
                self.assertEqual(guilds["A4"].value, 133430)
                self.assertEqual(guilds["B4"].value, 2)
                self.assertTrue(any(row[0].value == 135937 and row[1].value == 0
                                    for row in guilds.iter_rows(min_row=4, max_col=2)))
                users = book["用户明细"]
                self.assertEqual(users.max_row, 4)
                self.assertEqual(users["C2"].value, "=BAD()")
                self.assertEqual(users["C2"].data_type, "s")
                self.assertEqual(users["E2"].value, 1.5)
                self.assertEqual(users["F2"].value, 0)
                self.assertEqual(users["G2"].value, 1)
                self.assertEqual(users["H2"].value, 0)
                self.assertEqual(users["N2"].value, 5000000)
                self.assertEqual(users["O2"].value, 0.5)
                self.assertEqual(users["P2"].value, 1)
                self.assertEqual(users["F4"].value, 0.5)
                self.assertEqual(users["L3"].value, 0)
                self.assertEqual(users["M2"].value, 3.5)
                self.assertEqual(users["M3"].value, 0)
                self.assertEqual(users["M4"].value, 7)
                self.assertEqual(users.auto_filter.ref, "A1:P4")
                self.assertEqual(guilds["L4"].value, 3.5)
                self.assertEqual(guilds["M4"].value, 0.0175)
                self.assertEqual(guilds["N4"].value, 5000000)
                self.assertEqual(guilds["O4"].value, 0.5)
                self.assertEqual(guilds["P4"].value, 1)
                for sheet in book:
                    self.assertTrue(sheet.sheet_view.showGridLines)
                    self.assertEqual(sheet["A3"].border.left.style, "thin")
                    for row in sheet:
                        for cell in row:
                            self.assertNotEqual(cell.data_type, "f")
                            self.assertNotIn(cell.value, ("#REF!", "#DIV/0!", "#VALUE!"))
                self.assertEqual(guilds.freeze_panes, "C4")
                self.assertEqual(users.freeze_panes, "C2")
            finally:
                book.close()
            manifest = read_json(paths["manifest"])
            self.assertEqual(len(manifest["completed_jobs"]), 4)
            self.assertNotIn("secret", json.dumps(manifest))

    def test_resume_skips_completed_es_jobs(self):
        class InterruptES(FakeES):
            def search(self, index, body):
                if index == COIN_INDEX and len(body["query"]["bool"]["filter"]) == 4:
                    raise RuntimeError("模拟 ES 中断")
                return super().search(index, body)

        class NoMySQL:
            def query(self, sql, parameters):
                raise AssertionError("续跑不应再次查询 MySQL")

        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("resume", Path(directory))
            with self.assertRaisesRegex(RuntimeError, "模拟 ES 中断"):
                run_collection(CONFIG, paths, mysql_reader=FakeMySQL(), es_reader=InterruptES())
            manifest = read_json(paths["manifest"])
            self.assertEqual(len(manifest["completed_jobs"]), 2)
            resumed = FakeES()
            run_collection(CONFIG, paths, resume=True, mysql_reader=NoMySQL(), es_reader=resumed)
            self.assertEqual(len(resumed.calls), 2)
            self.assertTrue(all(len(body["query"]["bool"]["filter"]) == 4
                                for _, body in resumed.calls))

    def test_rebuild_new_checkpoints_and_reject_old_without_recharge(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("legacy", Path(directory))
            run_collection(CONFIG, paths, mysql_reader=FakeMySQL(), es_reader=FakeES())
            output = rebuild_from_checkpoints(paths["manifest"])
            book = load_workbook(output, read_only=True, data_only=False)
            try:
                users = book["用户明细"]
                self.assertEqual(users["F4"].value, 0.5)
                self.assertEqual(users["G2"].value, 1)
                self.assertEqual(users["H2"].value, 0)
                self.assertEqual(users["M2"].value, 3.5)
            finally:
                book.close()
            with self.assertRaisesRegex(ValueError, "拒绝覆盖"):
                rebuild_from_checkpoints(paths["manifest"])
            # 旧版快照的补查流程使用同名新报表；仅在临时测试目录移动先前产物。
            output.rename(output.with_name("earlier.xlsx"))
            manifest = read_json(paths["manifest"])
            manifest["identity"]["schema_version"] = 2
            save_json(paths["manifest"], manifest)
            snapshot = read_json(paths["mysql"])
            snapshot.pop("platformRechargeUsd")
            for user in snapshot["users"]:
                user.pop("rechargeUsd")
            save_json(paths["mysql"], snapshot)
            manifest = read_json(paths["manifest"])
            from fetch_data import digest
            manifest["mysql_sha256"] = digest(paths["mysql"])
            save_json(paths["manifest"], manifest)
            with self.assertRaisesRegex(ValueError, "旧批次缺少充值"):
                rebuild_from_checkpoints(paths["manifest"])
            recharge_only = FakeMySQL()
            upgraded = rebuild_from_checkpoints(paths["manifest"], recharge_only)
            self.assertTrue(upgraded.name.endswith("-提现订单奖励单列.xlsx"))
            self.assertEqual(len(recharge_only.queries), 2)
            self.assertTrue(all("FROM sano_wallet_recharge" in sql
                                for sql, _ in recharge_only.queries))
            self.assertTrue(upgraded.with_name("guild-income-legacy-提现订单奖励单列.json").is_file())
            enriched = load_workbook(upgraded, read_only=True, data_only=True)
            try:
                self.assertEqual(enriched["总体对比"]["B17"].value, 10.5)
            finally:
                enriched.close()


if __name__ == "__main__":
    unittest.main()

"""使用模拟 MySQL/ES 验证只读查询、精确人数、续跑及五表金额。"""

from __future__ import annotations

import json
from io import BytesIO
from http.client import IncompleteRead, RemoteDisconnected
import tempfile
import unittest
from unittest.mock import patch
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError

from openpyxl import load_workbook

from export_report import amounts, create_workbook, rebuild_from_checkpoints
from fetch_data import (ROOT, ESReader, MySQLReader, collect, consolidate, digest, es_query, exact_integer,
                        fetch_dictionary, fetch_user_countries, jobs, save_json,
                        find_resume_paths, identity, output_paths, parse_page, read_json,
                        validate_analysis)


ANALYSIS = validate_analysis({"start": "2026-09-01 12:00:00", "end": "2026-09-03 00:00:00",
                              "es_time_zone": "+08:00", "es_page_size": 2, "mysql_batch_size": 2})
CONFIG = {"mysql": {"host": "mock", "database": "sano", "username": "readonly"},
          "es": {"es_url": "https://mock:9200"}, "analysis": ANALYSIS}


class FakeMySQL:
    def __init__(self):
        self.calls = []

    def query(self, sql, parameters=()):
        assert sql.lstrip().upper().startswith("SELECT") and ";" not in sql
        self.calls.append((sql, parameters))
        if "FROM sano_game_list" in sql:
            for game_id, name, created in ((100, "阿尔法", "2026-08-01 00:00:00"),
                                           (101, "=BETA()", "2026-08-01 00:00:00"),
                                           (102, "零消费", "2026-08-01 00:00:00"),
                                           (103, "未来游戏", "2026-10-01 00:00:00"),
                                           (104, "=ACTIVE()", "2026-08-01 00:00:00")):
                if "WHERE status = 1" in sql and game_id == 101:
                    continue
                yield {"id": game_id, "producer_game_id": "p" + str(game_id),
                       "producer_code": "厂商", "ch_name": name, "en_name": "English",
                       "status": 0 if game_id == 101 else 1,
                       "delete_flag": 1 if game_id == 101 else 0,
                       "create_time": datetime.strptime(created, "%Y-%m-%d %H:%M:%S")}
        elif "FROM sano_config_country" in sql:
            yield {"country_code_small": "PH", "country_name_remark": "菲律宾"}
            yield {"country_code_small": "BR", "country_name_remark": "巴西"}
        elif "FROM sano_user" in sql:
            values = {1: "PH", 2: "BR", 3: "PH"}
            for user_id in parameters:
                if user_id in values:
                    yield {"id": user_id, "country_code": values[user_id]}
        else:
            raise AssertionError(sql)


def make_bucket(user_id, game_id, consume, records, reward=0, reward_records=0,
                jackpot=0, jackpot_records=0, *, users=True):
    key = {"gameId": game_id}
    if users:
        key = {"userId": user_id, **key}
    return {"key": key, "doc_count": records + reward_records + jackpot_records,
            "consume": {"doc_count": records, "tokens": {"value": float(consume)}},
            "reward": {"doc_count": reward_records, "tokens": {"value": float(reward)}},
            "jackpot": {"doc_count": jackpot_records, "tokens": {"value": float(jackpot)}}}


class FakeES:
    def __init__(self, *, interrupt_day=None):
        self.calls = []
        self.interrupt_day = interrupt_day
        self.users = [make_bucket(None, 100, 2000, 1), make_bucket(None, 999, 4000, 1),
                      make_bucket(1, 100, 20000, 2, 5000, 1, 1000, 1),
                      make_bucket(1, 101, 10000, 1),
                      make_bucket(1, 104, 3000, 1),
                      make_bucket(2, 100, 5000, 1, 1000, 1),
                      make_bucket(3, 101, 15000, 1, jackpot=2000, jackpot_records=1),
                      make_bucket(4, 100, 2500, 1)]
        self.day1 = [make_bucket(None, 100, 24500, 4, 5000, 1, 1000, 1, users=False),
                     make_bucket(None, 101, 10000, 1, users=False),
                     make_bucket(None, 104, 3000, 1, users=False),
                     make_bucket(None, 999, 4000, 1, users=False)]
        self.day2 = [make_bucket(None, 100, 5000, 1, 1000, 1, users=False),
                     make_bucket(None, 101, 15000, 1, jackpot=2000, jackpot_records=1,
                                 users=False)]

    def search(self, body):
        assert body["size"] == 0
        sources = body["aggs"]["groups"]["composite"]["sources"]
        assert sources[-1] == {"gameId": {"terms": {"field": "prop_id", "missing_bucket": True}}}
        span = body["query"]["bool"]["filter"][0]["range"]["create_time"]
        assert span["time_zone"] == "+08:00"
        self.calls.append((span["gte"], body))
        if span["gte"] == "2026-09-02 00:00:00" and self.interrupt_day:
            self.interrupt_day = None
            raise RuntimeError("模拟 ES 查询中断")
        rows = (self.users if len(sources) == 2 else
                self.day1 if span["gte"] == "2026-09-01 12:00:00" else self.day2)
        for clause in body["query"]["bool"]["filter"]:
            if "terms" in clause and "prop_id" in clause["terms"]:
                rows = [row for row in rows if row["key"]["gameId"] in clause["terms"]["prop_id"]]
            if "match_none" in clause:
                rows = []
        after = body["aggs"]["groups"]["composite"].get("after")
        first = next((index + 1 for index, row in enumerate(rows) if row["key"] == after), 0)
        end = first + body["aggs"]["groups"]["composite"]["size"]
        selected = rows[first:end]
        groups = {"buckets": selected}
        if end < len(rows):
            groups["after_key"] = selected[-1]["key"]
        return {"timed_out": False, "_shards": {"failed": 0},
                "aggregations": {"groups": groups}}


def make_legacy_batch(root):
    """构造旧版全游戏检查点，验证升级时能重新去重、且不改写旧批次。"""
    paths = output_paths("legacy", root)
    paths["parts"].mkdir()
    snapshot = fetch_dictionary(FakeMySQL())
    snapshot["games"].append({"gameId": 101, "chName": "停用游戏", "enName": "Disabled",
                              "producerGameId": "p101", "producerCode": "厂商",
                              "status": 0, "deleteFlag": 1, "createTime": "2026-08-01 00:00:00"})
    save_json(paths["snapshot"], snapshot)
    manifest = {"identity": {**identity(CONFIG), "schema_version": 1},
                "stage": "json_complete", "snapshot_sha256": digest(paths["snapshot"]), "jobs": {}}
    reader = FakeES()
    for job in jobs(ANALYSIS):
        state = {"pages": [], "after": None, "last_key": None, "done": False}
        manifest["jobs"][job["name"]] = state
        while not state["done"]:
            body = es_query(ANALYSIS, job, state["after"])
            rows, following = parse_page(reader.search(body), job, state["last_key"], state["after"])
            ids = sorted({row["userId"] for row in rows if row.get("userId") is not None})
            users = fetch_user_countries(FakeMySQL(), ids, 2) if job["kind"] == "users" else []
            stem = f"{job['name']}-{len(state['pages']) + 1:06d}"
            page, dsl = paths["parts"] / f"{stem}.json", paths["parts"] / f"{stem}.dsl.json"
            save_json(dsl, body)
            save_json(page, {"job": job["name"], "requestAfter": state["after"],
                             "afterKey": following, "rows": rows, "users": users})
            state["pages"].append({"sha256": digest(page), "dsl_sha256": digest(dsl), "rows": len(rows)})
            state["after"], state["done"] = following, following is None
            if rows:
                state["last_key"] = {name: rows[-1][name] for name in
                                     (("userId", "gameId") if job["kind"] == "users" else ("gameId",))}
    save_json(paths["data"], consolidate(paths, manifest))
    manifest["data_sha256"] = digest(paths["data"])
    save_json(paths["manifest"], manifest)
    return paths


class GameAnalysisTests(unittest.TestCase):
    def test_legacy_rebuild_filters_games_and_recounts_users_without_network(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = make_legacy_batch(Path(directory))
            original = read_json(paths["data"])
            self.assertIn(101, {game["gameId"] for game in original["games"]})
            self.assertEqual(original["totals"]["consumerCount"], 4)
            old_hash = digest(paths["data"])
            with patch("fetch_data.urlopen", side_effect=AssertionError("不能查询线上 ES")), \
                    patch("fetch_data.MySQLReader", side_effect=AssertionError("不能查询 MySQL")):
                output = rebuild_from_checkpoints(paths["manifest"])
            book = load_workbook(output, read_only=True, data_only=True)
            try:
                self.assertEqual(book["分析汇总"]["B21"].value, 3.25)
                self.assertEqual(book["分析汇总"]["B19"].value, 3)
                self.assertEqual({row[0].value for row in book["游戏汇总"].iter_rows(min_row=2, min_col=2, max_col=2)},
                                 {100, 102, 104})
            finally:
                book.close()
            self.assertEqual(digest(paths["data"]), old_hash)
            with self.assertRaisesRegex(ValueError, "旧批次为全游戏范围"):
                find_resume_paths(CONFIG, Path(directory))

    def test_empty_available_list_never_queries_all_games(self):
        class NoGames(FakeMySQL):
            def query(self, sql, parameters=()):
                if "FROM sano_game_list" in sql:
                    return
                yield from super().query(sql, parameters)

        class NoES:
            def search(self, body):
                raise AssertionError("没有可用游戏时不应查询 ES")

        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("empty", Path(directory))
            data = read_json(collect(CONFIG, paths, mysql_reader=NoGames(), es_reader=NoES()))
            self.assertEqual(data["totals"]["consumeTokens"], 0)
            self.assertEqual(data["games"], [])
            self.assertEqual(read_json(paths["parts"] / "users-000001.dsl.json")
                             ["query"]["bool"]["filter"][-1], {"match_none": {}})
            create_workbook(data, paths["report"])

    def test_es_scope_violation_is_not_committed(self):
        class WrongScope(FakeES):
            def search(self, body):
                response = super().search(body)
                response["aggregations"]["groups"]["buckets"][0]["key"]["gameId"] = 999
                return response

        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("scope", Path(directory))
            with self.assertRaisesRegex(ValueError, "可用游戏列表以外"):
                collect(CONFIG, paths, mysql_reader=FakeMySQL(), es_reader=WrongScope())
            self.assertEqual(read_json(paths["manifest"])["jobs"].get("users", {}).get("pages", []), [])

    def test_es_retries_reset_disconnect_and_incomplete_response(self):
        payload = b'{"_shards":{"failed":0},"timed_out":false,"aggregations":{"groups":{"buckets":[]}}}'
        body = {"size": 0, "after": {"userId": 123, "gameId": 100}}
        errors = (ConnectionResetError(10054, "模拟远端断开"), RemoteDisconnected("模拟无响应"),
                  IncompleteRead(b'{"_shards":', 100))
        for error in errors:
            with self.subTest(error=type(error).__name__):
                reader = ESReader({"es_url": "http://mock:9200"})
                with patch("fetch_data.urlopen", side_effect=[error, BytesIO(payload)]) as network, \
                        patch("fetch_data.time.sleep") as sleep:
                    result = reader.search(body)
                self.assertEqual(result["aggregations"]["groups"]["buckets"], [])
                self.assertEqual(network.call_count, 2)
                self.assertEqual(network.call_args_list[0].args[0].data,
                                 network.call_args_list[1].args[0].data)
                sleep.assert_called_once_with(1)

        # 连接已建立、但读取响应体时中断的情况也应重试。
        broken = BytesIO(payload)
        broken.read = lambda: (_ for _ in ()).throw(IncompleteRead(b"partial", 100))
        with patch("fetch_data.urlopen", side_effect=[broken, BytesIO(payload)]), \
                patch("fetch_data.time.sleep"):
            self.assertEqual(reader.search(body)["_shards"]["failed"], 0)

    def test_es_retry_limit_and_nonretryable_http_error(self):
        reader = ESReader({"es_url": "http://mock:9200"})
        with patch("fetch_data.urlopen", side_effect=ConnectionResetError(10054, "模拟断连")) as network, \
                patch("fetch_data.time.sleep") as sleep:
            with self.assertRaisesRegex(ValueError, "--resume"):
                reader.search({"size": 0})
            self.assertEqual(network.call_count, 3)
            self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2])
        with patch("fetch_data.urlopen", side_effect=HTTPError("http://mock", 401, "Unauthorized", None, None)) as network, \
                patch("fetch_data.time.sleep") as sleep:
            with self.assertRaisesRegex(ValueError, "HTTP 401"):
                reader.search({"size": 0})
            network.assert_called_once()
            sleep.assert_not_called()

    def test_rates_are_decimal_and_profit_is_derived(self):
        self.assertEqual(ANALYSIS["service_fee_rate"], "0.0060")
        self.assertEqual(ANALYSIS["platform_profit_rate"], "0.0240")
        self.assertEqual(amounts({"consumeTokens": 100000, "rewardTokens": 20000,
                                  "jackpotTokens": 5000}, ANALYSIS)["profitUsd"], Decimal("0.2400"))
        with self.assertRaisesRegex(ValueError, "浮点数"):
            validate_analysis({"start": ANALYSIS["start"], "end": ANALYSIS["end"], "anchor_rate": 0.005})

    def test_read_only_and_scoped_es_queries(self):
        query = es_query(ANALYSIS, {"name": "users", "kind": "users", "start": ANALYSIS["start"],
                                    "end": ANALYSIS["end"]}, game_ids=[100, 102, 103, 104])
        self.assertEqual(query["query"]["bool"]["filter"][2],
                         {"terms": {"prop_id": [100, 102, 103, 104]}})
        self.assertEqual(len(query["query"]["bool"]["filter"][1]["bool"]["should"]), 3)
        self.assertEqual(query["aggs"]["groups"]["composite"]["sources"][0],
                         {"userId": {"terms": {"field": "user_id", "missing_bucket": True}}})
        database = object.__new__(MySQLReader)
        with self.assertRaisesRegex(ValueError, "只读 SELECT"):
            list(database.query("DELETE FROM sano_user"))
        with self.assertRaisesRegex(ValueError, "只读 SELECT"):
            list(database.query("SELECT * FROM sano_user FOR UPDATE"))
        with self.assertRaises(ValueError):
            exact_integer(2**53, "tokens")
        with self.assertRaises(ValueError):
            exact_integer(Decimal("0.1"), "tokens")
        class StubCursor:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return None

            def mogrify(self, sql, parameters):
                return sql.replace("%s", str(parameters[0]))

            def execute(self, sql, parameters):
                assert parameters == (1,)

            def __iter__(self):
                return iter([{"id": 1}])

        class StubConnection:
            def cursor(self):
                return StubCursor()

        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            database.sql_path = Path(directory) / "executed.sql"
            database.connection = StubConnection()
            self.assertEqual(list(database.query("SELECT id FROM sano_user WHERE id=%s", (1,))),
                             [{"id": 1}])
            self.assertIn("SELECT id FROM sano_user WHERE id=1;",
                          database.sql_path.read_text(encoding="utf-8"))

    def test_incomplete_or_duplicate_es_page_fails(self):
        job = {"kind": "users"}
        fake = FakeES()
        response = fake.search(es_query(ANALYSIS, {"kind": "users", "start": ANALYSIS["start"],
                                                    "end": ANALYSIS["end"]}))
        response["aggregations"]["groups"]["buckets"][1]["key"] = response["aggregations"]["groups"]["buckets"][0]["key"]
        with self.assertRaisesRegex(ValueError, "重复"):
            parse_page(response, job, None, None)

    def test_full_flow_workbook_and_rebuild(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("test", Path(directory))
            database, es = FakeMySQL(), FakeES()
            data_path = collect(CONFIG, paths, mysql_reader=database, es_reader=es)
            data = read_json(data_path)
            self.assertEqual(data["totals"]["consumerCount"], 3)
            self.assertEqual(data["totals"]["consumeTokens"], 32500)
            self.assertEqual(data["totals"]["consumeRecords"], 6)
            self.assertEqual(data["totals"]["rewardTokens"], 6000)
            self.assertEqual(data["totals"]["jackpotTokens"], 1000)
            self.assertEqual(data["totals"]["maxConsumerTokens"], 23000)
            self.assertEqual(sum(item["consumerCount"] for item in data["games"]), 4)
            self.assertEqual({item["countryCode"]: item["consumerCount"] for item in data["countries"]},
                             {"": 1, "BR": 1, "PH": 1})
            self.assertEqual({item["gameId"] for item in data["games"]}, {100, 102, 104})
            self.assertTrue(all(item["status"] == 1 for item in data["games"]))
            self.assertEqual(next(item for item in data["games"] if item["gameId"] == 102)["consumeTokens"], 0)
            self.assertEqual(next(item for item in data["games"] if item["gameId"] == 100)["activeDays"], 2)
            self.assertEqual(data["warnings"]["missingUserIdRecords"], 1)
            self.assertEqual(data["warnings"]["missingUserCount"], 1)
            self.assertEqual(data["warnings"]["unmatchedGameCount"], 0)
            self.assertEqual(len(database.calls), 2 + 3)  # 字典2次；用户页涉及ID的3次。
            self.assertTrue(all(sql.lstrip().upper().startswith("SELECT") for sql, _ in database.calls))
            self.assertIn("WHERE status = 1", database.calls[0][0])
            self.assertTrue(all({"terms": {"prop_id": [100, 102, 103, 104]}}
                                in body["query"]["bool"]["filter"] for _, body in es.calls))
            self.assertEqual(es.calls[0][0], ANALYSIS["start"])
            self.assertEqual(es.calls[-1][0], "2026-09-02 00:00:00")
            self.assertEqual(es.calls[-1][1]["query"]["bool"]["filter"][0]
                             ["range"]["create_time"]["lt"], ANALYSIS["end"])
            create_workbook(data, paths["report"])
            book = load_workbook(paths["report"], data_only=False)
            try:
                self.assertEqual(book.sheetnames,
                                 ["分析汇总", "游戏汇总", "国家汇总", "国家游戏汇总", "每日游戏趋势"])
                summary = book["分析汇总"]
                self.assertEqual(summary["B21"].value, 3.25)
                self.assertEqual(summary["B22"].value, 0.6)
                self.assertEqual(summary["B24"].value, 0.1)
                self.assertEqual(summary["B26"].value, 0.016)
                self.assertEqual(summary["B28"].value, 0.02)
                self.assertEqual(summary["B29"].value, 0.078)
                self.assertIn("status=1", summary["B6"].value)
                game_sheet = book["游戏汇总"]
                self.assertEqual(game_sheet.max_row, 4)
                self.assertEqual(game_sheet["C3"].value, "=ACTIVE()")
                self.assertEqual(game_sheet["C3"].data_type, "s")
                self.assertEqual(game_sheet["F3"].value, "使用中")
                self.assertEqual(game_sheet["G3"].value, 0)
                self.assertEqual(game_sheet["B4"].value, 102)
                self.assertEqual(game_sheet.freeze_panes, "D2")
                self.assertTrue(game_sheet.auto_filter.ref.startswith("A1:"))
                self.assertTrue(any(cell.value == "用户ID缺失" for row in game_sheet for cell in row))
                self.assertTrue(all(sheet.sheet_view.showGridLines for sheet in book))
                self.assertEqual(book["每日游戏趋势"].max_row, 7)
                self.assertEqual(book["每日游戏趋势"]["F2"].value, "使用中")
                self.assertEqual(book["国家游戏汇总"]["H2"].value, "使用中")
                self.assertFalse(any(cell.data_type == "f" or cell.value in ("#REF!", "#DIV/0!", "#VALUE!")
                                     for sheet in book for row in sheet for cell in row))
            finally:
                book.close()
            self.assertEqual(consolidate(paths, read_json(paths["manifest"])), data)
            rebuilt = rebuild_from_checkpoints(paths["manifest"])
            self.assertTrue(rebuilt.is_file())
            self.assertNotEqual(rebuilt, paths["report"])

    def test_resume_validates_pages_and_skips_finished_jobs(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("resume", Path(directory))
            with self.assertRaisesRegex(RuntimeError, "中断"):
                collect(CONFIG, paths, mysql_reader=FakeMySQL(),
                        es_reader=FakeES(interrupt_day=True))
            manifest = read_json(paths["manifest"])
            self.assertTrue(manifest["jobs"]["users"]["done"])
            self.assertTrue(manifest["jobs"]["day-20260901"]["done"])
            resumed = FakeES()
            result = collect(CONFIG, paths, resume=True, mysql_reader=FakeMySQL(), es_reader=resumed)
            self.assertTrue(result.is_file())
            self.assertTrue(all(start == "2026-09-02 00:00:00" for start, _ in resumed.calls))
            first = paths["parts"] / "users-000001.json"
            first.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "校验失败"):
                consolidate(paths, read_json(paths["manifest"]))

    def test_same_config_resume_identity(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            paths = output_paths("id", Path(directory))
            paths["manifest"].write_text(json.dumps({"identity": identity(CONFIG), "stage": "in_progress"}),
                                         encoding="utf-8")
            self.assertEqual(find_resume_paths(CONFIG, Path(directory))["manifest"], paths["manifest"])


if __name__ == "__main__":
    unittest.main()

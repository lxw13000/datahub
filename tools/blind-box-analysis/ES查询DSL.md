# ES 聚合查询说明

自动方式：在 `config.json` 填写 ES 地址与账号密码后，运行 `fetch_es.py`；它会各执行一次下表四类聚合查询，并可直接生成 Excel。具体命令见 [README.md](README.md)。本页也保留手动执行 DSL 的说明。

运行 `python .\export_report.py queries --start "2026-09-01 00:00:00" --end "2026-09-30 23:59:59" --output-dir .\queries-202609`，可得到四份可直接执行的完整 JSON DSL。这里列出各查询的核心结构；统计时间、业务类型、礼物 ID 和聚合名称以程序生成文件为准。

查询全部历史时使用 `--all-history --end "固定截止时刻"`；生成的四份 DSL 都只保留 `create_time` 的 `lte`，不包含 `gte`。

## 先看文件对应关系

以你生成的 `queries-all-history-20260929` 目录为例，**这些 JSON 是发给 ES 的查询请求，不是报表输入数据**。把每个查询发到下表指定的索引，再将 ES 返回的完整 JSON 保存到“响应文件”列的位置：

| 分析内容 | 查询请求 JSON | ES 索引 | 保存 ES 响应为 |
| --- | --- | --- | --- |
| 盲盒消费汇总 | `queries-all-history-20260929/consumption.json` | `sano_wallet_coin_record` | `responses/consumption.json` |
| 金币返奖汇总 | `queries-all-history-20260929/coin-rewards.json` | `sano_wallet_coin_record` | `responses/coin-rewards.json` |
| 钻石返奖汇总 | `queries-all-history-20260929/diamond-rewards.json` | `sano_wallet_diamond_record` | `responses/diamond-rewards.json` |
| 每盒 Top 30、全盲盒 Top 100 及这 100 人各盒消费 | `queries-all-history-20260929/top-users.json` | `sano_wallet_coin_record` | `responses/top-users.json` |

也就是说，**消费先使用 `consumption.json` 查询**；生成报表时传入的是查询得到的 `responses/consumption.json`。四个查询各保存一份完整响应，不需要分页。执行方式是 `POST <上表ES索引>/_search`，请求正文使用对应的查询 JSON。以下各节解释每个查询的过滤与聚合内容。

## 1. 盲盒消费：`sano_wallet_coin_record`

```json
{
  "size": 0,
  "query": {"bool": {"filter": [
    {"terms": {"business_type": [1, 11]}},
    {"terms": {"prop_id": [161, 162, 163, 164, 165, 166, 167, 168]}},
    {"term": {"status": -1}},
    {"range": {"create_time": {"gte": "2026-09-01 00:00:00", "lte": "2026-09-30 23:59:59"}}}
  ]}},
  "aggs": {"by_box": {
    "terms": {"field": "prop_id", "size": 8, "order": {"_key": "asc"}},
    "aggs": {"sum_tokens": {"sum": {"field": "tokens"}}, "sum_amount": {"sum": {"field": "amount"}}}
  }}
}
```

`by_box.buckets[*].doc_count` 是消费记录条数，不是金额；两个 `sum` 分别为金币 tokens 和开盒次数（原始 `amount`）合计。每盒单价来自独立的 `box-prices.json`，只在 Python 报表中核对实际消费金币，不改变 ES 查询或利润口径。

## 2. 金币返奖：`sano_wallet_coin_record`

过滤 `business_type=16`、`status=1`、礼物 ID 在配置范围、`create_time` 在同一期间。按 `prop_id` 聚合（`by_gift`），每桶取 `doc_count` 和 `sum_tokens`。`business_type=17` jackpot 不查询。程序生成文件中包含完整礼物 ID 列表。

## 3. 钻石返奖：`sano_wallet_diamond_record`

过滤 `business_type in (2,3,4,5,12,13,14)`、`status=1`、礼物 ID 在配置范围、`create_time` 在同一期间。先按 `business_type` 聚合（`by_type`，最多 7 桶），再按 `prop_id` 聚合（`by_gift`，最多 20 桶）；每个礼物桶取 `doc_count`、`sum_tokens` 和 `sum_amount`。业务类型区分普通与幸运，礼物 ID 决定该类别内的具体盲盒。

## 4. 每盒及全盲盒用户消费排名：`sano_wallet_coin_record`

与第 1 项使用完全相同的过滤条件。`by_box` 先按盲盒聚合，再在每盒内按用户消费金币总额取前 30；并列的 `by_user_all` 将 161–168 的消费按用户合并后取前 100，在这 100 个用户桶下再按盲盒 ID 汇总：

```json
{
  "size": 0,
  "query": {"bool": {"filter": [
    {"terms": {"business_type": [1, 11]}},
    {"terms": {"prop_id": [161, 162, 163, 164, 165, 166, 167, 168]}},
    {"term": {"status": -1}},
    {"range": {"create_time": {"gte": "2026-09-01 00:00:00", "lte": "2026-09-30 23:59:59"}}}
  ]}},
  "aggs": {
    "by_box": {
      "terms": {"field": "prop_id", "size": 8, "order": {"_key": "asc"}},
      "aggs": {"by_user": {
        "terms": {
          "field": "user_id", "size": 30, "shard_size": 300,
          "order": [{"sum_tokens": "desc"}, {"_key": "asc"}]
        },
        "aggs": {"sum_tokens": {"sum": {"field": "tokens"}}}
      }}
    },
    "by_user_all": {
      "terms": {
        "field": "user_id", "size": 100, "shard_size": 1000,
        "order": [{"sum_tokens": "desc"}, {"_key": "asc"}]
      },
      "aggs": {
        "sum_tokens": {"sum": {"field": "tokens"}},
        "by_box": {
          "terms": {"field": "prop_id", "size": 8, "order": {"_key": "asc"}},
          "aggs": {"sum_tokens": {"sum": {"field": "tokens"}}}
        }
      }
    }
  }
}
```

一次响应最多包含 8×30 个每盒用户桶、100 个全局用户桶及其各盒消费桶，不需要 `after_key`。`doc_count` 是消费记录数，`sum_tokens` 是消费金币合计，报告按 10,000 金币 = 1 美元换算。Python 会检查每个全局 Top100 用户的八盒消费之和等于该用户合计，再汇总普通与幸运盲盒。`shard_size` 表示每个分片向协调节点提交的候选用户桶数量；这能减少漏选风险，但按 `sum(tokens)` 跨分片排序仍可能产生近似排名，不能保证严格的全局 Top 30／Top 100。查询大规模数据时请结合 ES 资源情况调整统计期。[Elastic 官方说明](https://www.elastic.co/docs/reference/aggregations/search-aggregations-bucket-terms-aggregation)。

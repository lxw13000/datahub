# 盲盒消费、开奖和平台利润分析

本地 Python 命令行工具。ES 先汇总千万级流水，工具只读取聚合响应 JSON 并生成 Excel，不读取逐笔金币或钻石记录，也不依赖 Spring Boot。完整 DSL 说明见 [ES查询DSL.md](ES查询DSL.md)。

## 统计口径

消费、金币返奖、钻石返奖分别按**各自流水的 `create_time`**落入同一个统计期。这是期间收支利润，不是按一批消费追踪其未来全部返奖。

| 盲盒 | 消费 | 金币返奖 | 钻石返奖及分账 |
| --- | --- | --- | --- |
| 161–164 普通礼物 | 金币 `business_type=1`，`status=-1`，`prop_id=盲盒ID` | 无 | `business_type=2,3,4,5`，`status=1` |
| 165–168 幸运礼物 | 金币 `business_type=11`，`status=-1`，`prop_id=盲盒ID` | `business_type=16`，`status=1` | `business_type=12,13,14`，`status=1` |

金币 `business_type=17` 是 jackpot，**不参与利润计算**。

```text
平台利润（美元）
  = 消费金币 tokens ÷ 10,000
  - 金币返奖 tokens ÷ 10,000
  - 钻石返奖及分账 tokens ÷ 10,000,000
```

礼物 `81,82,84,86,88` 对应普通盲盒 161 / 幸运盲盒 165；`174–178` 对应 162 / 166；`179–183` 对应 163 / 167；`169–173` 对应 164 / 168。返奖用**业务类型所属类别＋礼物 ID**归属盲盒，不使用 `common_id`。ES 汇总结果中的 tokens 先作为整数累加，写入美元列时才用 `Decimal` 换算，显示三位小数。

“开奖主记录数”只取钻石类型 `2`（普通）或 `12`（幸运）的记录条数；类型 `16` 的金币中奖记录数单列显示，避免一盒同时产生钻石和金币记录时重复计次。金币消费记录的 `amount` 已确认为开盒次数；它与 `doc_count`（消费记录数）分列展示。

## 安装

在 Anaconda Prompt 或 CMD 中运行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\blind-box-analysis
conda activate luckyEv
python --version
python -m pip install -r requirements.txt
```

如果提示没有 pip，先运行 `conda install -n luckyEv pip`。运行时唯一的第三方依赖是 `openpyxl>=3.1,<4`。

## 盲盒单价配置

独立的 [box-prices.json](box-prices.json) 配置每次开盒的金币单价：161／165 为 500，162／166 为 1,000，163／167 为 5,000，164／168 为 10,000。用户确认这些单价在所分析的全部历史期间一致。该文件不包含 ES 密码，自动与手动报表默认都会读取；若使用其他配置文件，生成 Excel 时传 `--box-prices 路径`。

普通、幸运盲盒汇总表会显示“开盒次数”“单价金币/次”“按单价应消费金币”和“消费金币差额”。差额 = 实际消费金币 tokens − 开盒次数 × 单价；非零标红，供核查。**消费、美元换算和利润仍使用 ES 实际 tokens**，不会被配置单价覆盖。

## 自动连接 ES 并生成报表（推荐）

复制示例配置，再编辑 `config.json`，填写 ES 服务地址、用户名和密码。配置文件是明文，已加入 `.gitignore`；不要把它发给其他人或提交到仓库。没有认证的 ES 可以把 `username` 和 `password` 都设为空字符串。HTTPS 如使用自签发 CA 证书，可添加 `"ca_cert": "证书文件路径"`；不要关闭证书校验。

```bat
copy .\config.example.json .\config.json
```

例如查询截至固定时刻的全部历史，自动执行四类查询、导出响应 JSON，并生成 Excel（CMD 单行命令）：

```bat
python .\fetch_es.py --config .\config.json --box-prices .\box-prices.json --all-history --end "2026-09-29 14:30:00" --output-dir .\responses\all-history-20260929 --report-output ".\盲盒全部历史分析.xlsx"
```

指定一个月的统计范围时，使用 `--start` 代替 `--all-history`：

```bat
python .\fetch_es.py --config .\config.json --box-prices .\box-prices.json --start "2026-09-01 00:00:00" --end "2026-09-30 23:59:59" --output-dir .\responses\202609 --report-output ".\盲盒消费开奖利润分析.xlsx"
```

`fetch_es.py` 会把四份查询 DSL 保存到输出目录的 `queries` 子目录，并执行四次 ES 查询。完整响应分别保存为 `consumption.json`、`coin-rewards.json`、`diamond-rewards.json`、`top-users.json`。第 4 次查询同时得到每盒 Top 30 和跨全部 8 个盲盒的用户 Top 100，**不做用户深度分页**。若网络中断，可用**完全相同的命令加 `--resume`**，跳过已保存的响应并继续未完成的查询；脚本会校验 ES 地址、查询时段和 DSL 未变。新批次请使用新的空目录。密码不会写入响应或清单文件。

如果你已用旧版生成 `top-users-first-page.json` 和多页用户响应，请重新生成 DSL，并使用新的空输出目录；旧版 `composite` 分页响应不适用于当前的 `--top-users` 输入格式。

如果已有旧版 `top-users.json`，也要重新执行第 4 份查询；新报表还需要 `aggregations.by_user_all.buckets[*].by_box`。消费和返奖的前三份响应在统计时间相同的情况下可以继续使用。

统计期内的消费、返奖、钻石分账分别查询同一固定截止时刻。`--all-history` 不等于动态“此刻”，请自行把 `--end` 换成实际需要的时间；固定截止避免四类查询先后执行时口径漂移。脚本只用 Python 标准库访问 ES，不需额外安装 ES 客户端。

## 手动查询：第一步，生成四份 ES 聚合 DSL

以下是 CMD 可直接复制的单行命令；按需要修改统计时间，起止时刻均包含在内：

```bat
python .\export_report.py queries --start "2026-09-01 00:00:00" --end "2026-09-30 23:59:59" --output-dir .\queries-202609
```

如果要查**全部历史截至当前**，先确定一个截止时间，再用 `--all-history` 代替 `--start`。例如截止到 `2026-09-29 14:30:00`：

```bat
python .\export_report.py queries --all-history --end "2026-09-29 14:30:00" --output-dir .\queries-all-history-20260929
```

该命令生成的四份 DSL 都不设开始时间，只保留相同的 `create_time <= 截止时间`。请把示例截止时间换成实际查询时刻，并在生成报表时使用同一个 `--end`；使用固定时刻可以避免先后执行四个查询时新增流水造成时间范围不一致。

生成的四个文件：

| DSL 文件 | 查询索引 | 聚合结果 |
| --- | --- | --- |
| `consumption.json` | `sano_wallet_coin_record` | 每个盲盒的消费记录数、`amount`、金币 tokens |
| `coin-rewards.json` | `sano_wallet_coin_record` | 按礼物 ID 汇总类型 16 的记录数和金币 tokens |
| `diamond-rewards.json` | `sano_wallet_diamond_record` | 按业务类型＋礼物 ID 汇总记录数、`amount` 和钻石 tokens |
| `top-users.json` | `sano_wallet_coin_record` | 每盒消费 Top 30、全盲盒合并消费 Top 100，及这 100 人各盒消费金币 |

四个查询使用**同一统计时间**。每份查询各保存一份完整 ES `_search` 响应 JSON，例如 `responses\consumption.json`。即使某类返奖为零，也保存该查询的空聚合响应。

### 手动方式的 ES 服务地址

`export_report.py queries` 命令只生成本地 DSL 文件，**不会连接 ES**；自动连接时使用前文 `config.json` 中的 `es_url`。手动执行查询时，把 ES 地址写在你使用的客户端中：Kibana Dev Tools 使用已连接的集群；`curl.exe` 则在请求 URL 中填写地址。例如在 CMD 中查询消费聚合：

```bat
mkdir responses
curl.exe -u your_user -H "Content-Type: application/json" -X POST "https://ES_HOST:9200/sano_wallet_coin_record/_search" --data-binary "@.\queries-202609\consumption.json" -o ".\responses\consumption.json"
```

把 `ES_HOST`、端口和用户名改为你的环境值。`-u your_user` 会交互提示输入密码；若 ES 不需要用户名密码，删掉这一段。金币返奖与 Top 用户查询也发往 `sano_wallet_coin_record`，钻石返奖查询发往 `sano_wallet_diamond_record`。使用 Kibana Dev Tools 时，在 DSL 前写 `POST sano_wallet_coin_record/_search`，粘贴对应 JSON 正文即可。

## 手动查询：第二步，导出 Top 用户聚合

第四个查询包含两个并列聚合：`by_box` 先取 8 个盲盒，再在每盒下按 `sum(tokens)` 降序、用户 ID 升序取 30 人；`by_user_all` 把 161–168 的消费跨盒合并，按同样规则取前 100 人，并在这 100 人每人的桶下按盲盒 ID 再汇总消费金币。普通／幸运消费由 Python 按对应 4 个盲盒相加。两组排名都使用实际金币消费总额，不扣返奖。对应的 `shard_size` 分别为 300、1,000，是每个分片送回的候选桶数量，不是分页大小。保存一次完整 ES 响应为 `responses\top-users.json`，不需要 `after_key` 或后续请求。

取舍：此方式只返回至多 8×30 个每盒用户桶、100 个全局用户桶及其各盒消费桶，避免遍历所有“盲盒×用户”组合，但 ES 仍需扫描统计期内的相关记录。跨分片按 `sum(tokens)` 排序时，`terms` 聚合**不保证严格准确的全局排名**；增大 `shard_size` 只能改善，不能消除这个风险。Top 30 和 Top 100 都应理解为“ES 聚合排名”，不能用于要求绝对精确的对账。[Elastic 官方文档](https://www.elastic.co/docs/reference/aggregations/search-aggregations-bucket-terms-aggregation)说明了这种排序的局限。

## 手动查询：第三步，生成 Excel

CMD 单行命令：

```bat
python .\export_report.py report --consumption .\responses\consumption.json --coin-rewards .\responses\coin-rewards.json --diamond-rewards .\responses\diamond-rewards.json --top-users .\responses\top-users.json --box-prices .\box-prices.json --start "2026-09-01 00:00:00" --end "2026-09-30 23:59:59" --output ".\盲盒消费开奖利润分析.xlsx"
```

对应全部历史查询时，报表命令也把 `--start` 改为 `--all-history`，并保持同一截止时间：

```bat
python .\export_report.py report --consumption .\responses\consumption.json --coin-rewards .\responses\coin-rewards.json --diamond-rewards .\responses\diamond-rewards.json --top-users .\responses\top-users.json --box-prices .\box-prices.json --all-history --end "2026-09-29 14:30:00" --output ".\盲盒全部历史分析.xlsx"
```

`--start`、`--end` 会写入报表顶部；Python 不会对 ES 聚合响应再次筛选时间，因此必须与四份 DSL 的统计时间一致。JSON 输入必须是包含 `aggregations` 的完整 ES 响应，不能使用旧版本工具的逐笔 `hits.hits` JSON。

生成六张工作表：

1. **分析总览**：全部盲盒、普通盲盒、幸运盲盒的消费、开奖主记录数、返奖、利润和利润率。
2. **普通礼物盲盒**：161–164 各盒及合计，附单价核对、每盒各礼物的开奖和钻石返奖分布。
3. **幸运礼物盲盒**：165–168 各盒及合计，附单价核对、每盒各礼物的开奖、金币返奖和钻石返奖分布。
4. **全盲盒消费Top100**：161–168 跨盒合计，列出排名、用户 ID、消费金币、折合美元、消费记录数，以及同一用户的普通／幸运消费美元和 161–168 各盒消费美元。
5. **普通消费Top30**：每个普通盲盒消费金币最高的 30 个用户 ID。
6. **幸运消费Top30**：每个幸运盲盒消费金币最高的 30 个用户 ID。

用户排名按 ES 返回的消费金币排序，同额按用户 ID 升序；报表表头注明跨分片可能存在排序误差。用户 ID 不显示千分位；tokens 显示千分位，美元显示三位小数，负利润标红。六张表保留网格线，便于查看记录。报表没有房间汇总。

报表中的“消费记录数”是匹配条件的金币记录条数，即 ES 聚合桶的 `doc_count`；它不是消费金额，也不等于 `amount` 字段之和。“开盒次数”展示金币消费记录的 `amount` 之和。

## 大数值与分段查询

ES `sum` 会以浮点数返回数值；工具只接受可安全还原为整数、且不超过 `2^53-1` 的单个汇总桶。如果某桶超过上限，改用**互不重叠的较短时间段**分别执行前三份汇总查询，并把每类响应 JSON 放入各自目录。报表参数可传目录，由 Python 按原始整数合并，例如 `--consumption .\responses\consumption-parts`。Top 用户查询仍需覆盖整个报表统计期；其结果只包含每盒 30 人及全盲盒 100 人，程序不会拿它与全量消费合计做相等校验。不要把相同时间段的响应重复放入目录。

## 验证

```bat
python -m unittest discover -s . -p "test_*.py" -v
```

测试使用与提供的真实普通、幸运盲盒样例相同的金额和业务类型，核对利润分别为 `0.03684`、`0.0302` 美元，且金币 `16` 与钻石 `12` 同时出现时只计算一次开奖主记录；还用模拟 ES 检查四次自动查询、Top 30／Top 100 响应、恢复导出及 Excel 生成。真实 ES 的连接和查询需在填写你的 `config.json` 后执行。

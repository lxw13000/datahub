# 首次充值分析工具

这是一个独立的本地 Python 命令行工具。工具读取一个或多个首充查询结果 Excel，按用户、日期、
Google/Apple 渠道、首充档位和充值时国家生成管理分析报告。

工具不会连接数据库，也不依赖 Spring Boot。SQL 由人工执行，查询结果导出为 `.xlsx` 后再交给
工具分析。

## 统计口径

- 每位用户只计入最早一条有效首充记录。
- 同一用户在多个输入文件中重复出现时，工具会合并后重新判断最早记录。
- 正式金额使用档位配置的 `standard_amount_usd`，单位为美元。
- `actual_money` 和 `actual_coin` 仅用于对账，不参与正式金额统计。
- 国家使用充值记录中的 `country_code`，再关联国家配置取得国家名称。
- 国家代码或名称缺失时归入“未知国家”，同时写入“数据异常”。
- “涉及国家数”只统计有明确国家代码和名称的国家，未知国家用户数单独展示。
- 注册至首充耗时由 `register_time` 和 `recharge_time` 重新计算，不直接依赖 SQL 中预计算的小时数。
- 金额使用 Python `Decimal` 汇总，不使用浮点数进行业务计算。

## Conda 环境与安装

在 Anaconda Prompt 或 CMD 中执行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\first-recharge-analysis
conda activate luckyEv
python -c "import openpyxl; print(openpyxl.__version__)"
python -m pip install -r requirements.txt
```

在已初始化 Conda 的 PowerShell 中执行：

```powershell
cd C:\work\opts\sano\code\datahub\tools\first-recharge-analysis
conda activate luckyEv
python -c "import openpyxl; print(openpyxl.__version__)"
python -m pip install -r requirements.txt
```

如果 `openpyxl` 已显示 `3.1.x`，则已经满足 `openpyxl>=3.1,<4`，无需重复安装。

如果提示 `No module named pip`，先执行：

```bat
conda install -n luckyEv pip
python -m pip install -r requirements.txt
```

## 导出查询数据

打开 [first_recharge_export.sql](./first_recharge_export.sql)，修改：

```sql
SET @statistics_start = '2026-09-14 11:00:00';
SET @statistics_end = '2026-10-01 00:00:00';
```

结束时间使用开区间。例如要统计到 `2026-09-30 23:59:59`，结束时间填写
`2026-10-01 00:00:00`。

执行 SQL 后，将查询结果直接导出为 `.xlsx`。查询会同时返回 Google 和 Apple 数据。如果需要
分渠道导出，可在 SQL 的 `WHERE` 中增加：

```sql
AND p.channel_code = 'google'
```

或者：

```sql
AND p.channel_code = 'apple'
```

Apple 的 `price_id` 超过 Excel 的 15 位数字精度，因此 SQL 已使用
`CAST(r.price_id AS CHAR)`，不要移除该转换。

## 输入 Excel 字段

必填字段：

| 字段 | 说明 |
| --- | --- |
| `user_id` | 用户 ID |
| `channel_code` | `google` 或 `apple` |
| `price_id` | 档位/商品 ID，必须以文本导出 |
| `tier_coin` | 档位配置金币数 |
| `standard_amount_usd` | 档位标准美元金额 |
| `recharge_time` | 首充成功时间 |

推荐字段：

| 字段 | 说明 |
| --- | --- |
| `nick` | 用户昵称 |
| `actual_money` | 订单实际金额，仅用于对账 |
| `actual_coin` | 订单实际金币，仅用于对账 |
| `register_time` | 用户注册时间 |
| `country_code` | 充值记录中的国家代码 |
| `country_name` | 国家名称 |
| `real_person` | 真人状态 |
| `proxy_id` | 当前代理 ID |
| `first_proxy_id` | 首个代理 ID |
| `first_join_time` | 首次加入时间 |
| `successful_first_recharge_count` | SQL 检测到的成功首充记录数 |
| `recharge_sequence` | SQL 内按用户排列的首充序号 |

工具会扫描每个输入工作簿的所有工作表，并自动找到包含必填字段的表头。没有必填字段的其他
工作表会被忽略。

## 执行命令

单个输入文件：

```bat
python .\export_report.py --input .\first-recharge.xlsx --output ".\首次充值分析.xlsx"
```

Google、Apple 分别导出两个文件：

```bat
python .\export_report.py --input .\google-first-recharge.xlsx .\apple-first-recharge.xlsx --output ".\首次充值分析.xlsx"
```

限制报告日期范围：

```bat
python .\export_report.py --input .\google-first-recharge.xlsx .\apple-first-recharge.xlsx --output ".\首次充值分析.xlsx" --start-date "2026-09-14" --end-date "2026-09-30"
```

`--start-date` 和 `--end-date` 均为可选参数。不传时，工具使用正式首充记录中的最早和最晚日期。
日期范围为闭区间。

## 输出工作表

### 分析汇总

展示首充用户数、首充总金额、人均金额、涉及国家数、注册时间覆盖率、注册至首充中位时长、
24 小时内首充占比、渠道汇总和按首充金额从高到低排列的渠道档位排名。

### 每天汇总

按连续日期展示首充用户数、首充金额、人均金额、Google/Apple/其他渠道金额和涉及国家数，并
附带每日首充金额趋势图。无首充日期按零保留。

### 渠道档位汇总

按渠道和档位展示用户数、金额、占比、涉及国家数、平均注册至首充耗时和 24 小时内首充占比。

### 国家汇总

按充值时国家展示用户数、金额、人均金额、占比和 Google/Apple 渠道拆分。

### 国家档位汇总

展示国家、渠道和档位交叉结果，包括国家内占比和整个周期占比。

### 每天档位汇总

展示每天各渠道、各档位的首充用户数、金额、当日占比和周期占比。

### 注册至首充

将用户按 `0-10 分钟`、`10-30 分钟`、`30-60 分钟`、`1-6 小时`、`6-24 小时`、`1-3 天`、
`3-7 天`、`7 天以上`分组。注册时间缺失和时间异常单独展示。

### 首充明细

保留每位用户最终计入统计的首充明细、注册时间、计算后的耗时、国家、档位及来源文件位置。

### 数据异常

展示重复首充、必填字段错误、国家缺失、注册时间缺失、时间倒置、未知渠道和长数字标识符精度
风险。每条异常明确标记是否仍计入正式统计。

所有数据表均冻结表头、启用筛选并设置数字格式。用户 ID 和 `price_id` 作为文本显示，美元金额
显示两位小数。

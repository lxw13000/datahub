# 用户金币消费习惯分析

独立的本地 Python 工具。输入一批用户 ID，按指定时间段查询 ES 中的金币消费聚合，生成聚合 JSON 和 Excel 报告。报表同时展示这批用户与同期全平台、相同消费业务口径的总量及占比。无需修改 `es-server`，不连接 MySQL，也不下载逐条流水。

## 安装

在 Anaconda Prompt 或 CMD 中执行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\user-consumption-analysis
conda activate luckyEv
python -m pip install -r requirements.txt
```

如果使用已初始化 conda 的 PowerShell，首行改为 `Set-Location C:\work\opts\sano\code\datahub\tools\user-consumption-analysis`，其余命令相同。

如环境提示 `No module named pip`，先运行 `conda install -n luckyEv pip`。唯一第三方依赖是 `openpyxl>=3.1,<4`；ES 连接使用 Python 标准库。

## 用户名单与配置

把 `user-ids.example.csv` 复制为 `user-ids.csv`，替换占位 ID。文件为单列 CSV，可带 `userId` 表头：

```csv
userId
11019438
11025156
```

空行和重复 ID 会忽略；ID 必须是正整数，不能用科学计数法。无消费用户仍保留在“用户明细”中。名单只用于筛选用户，不代表全平台总体。

复制两个配置示例：

```bat
copy .\es-config.example.json .\es-config.json
copy .\analysis-config.example.json .\analysis-config.json
```

`es-config.json` 只放 ES 地址、账号、密码、超时。无认证时把账号和密码同时设为 `""`。HTTPS 默认校验证书；私有 CA 可增加 `"ca_cert": "ca.pem"`，相对路径以此配置文件为准。

`analysis-config.json` 只放名单和查询条件：

```json
{
  "user_ids_file": "user-ids.csv",
  "start": "2026-09-01 00:00:00",
  "end": "2026-09-30 23:59:59",
  "bucket_time_zone": "+00:00"
}
```

起止时刻均包含。`bucket_time_zone` 同时控制时间范围的解释和 ES 按天、按小时分桶；默认 `+00:00` 与 ES 中无时区的本地时间字符串保持相同钟点。如索引中的时间实际按 UTC 存储、报表需要上海时区，可改为 `Asia/Shanghai`。改变此参数会改变查询范围和日期／小时归属，因此不能与旧批次混用。相对用户文件路径以 `analysis-config.json` 所在目录为基准。

## 运行与续跑

激活 conda 后用单行命令运行：

```bat
python .\export_report.py --es-config .\es-config.json --analysis-config .\analysis-config.json
```

如查询或生成报表中断，保持两份配置和用户 CSV 不变，运行：

```bat
python .\export_report.py --es-config .\es-config.json --analysis-config .\analysis-config.json --resume
```

工具按独立查询任务保存检查点，已完成任务会被校验并跳过；中断的当前任务从头重查。续跑核对 ES 地址、时间、名单哈希和查询 DSL 哈希，不保存密码。相同口径必须恰好有一个未完成批次；正常完成的报表不会被覆盖。再次运行不带 `--resume` 会生成新时间戳文件。

结果直接写在脚本同级目录：

- `user-consumption-时间戳.json`：平台每日、平台每小时、名单用户每日业务、名单每小时的整数金币聚合数据。
- `user-consumption-manifest-时间戳.json`：查询口径、任务进度与校验哈希。
- `用户消费习惯分析-时间戳.xlsx`：五张工作表的报表。
- `.user-consumption-时间戳.parts/`：续跑检查点，报表完成前不要移动或删除。

真实 ES 配置、用户 CSV、响应 JSON 和工作簿都已列入 `.gitignore`，不要提交含账号或用户信息的文件。

## 统计口径

只查询 `sano_wallet_coin_record`，要求 `status=-1`，只计以下业务类型：

| 报表类别 | business_type | 说明 |
| --- | --- | --- |
| 普通礼物 | 1 | 枚举名为“赠送豪华礼物” |
| 幸运礼物 | 11 | 赠送幸运礼物 |
| 游戏 | 21 | 游戏消费 |
| 道具 | 41 | 购买装扮道具 |
| VIP | 42、105、106 | 购买 VIP、VIP 套餐补签卡、VIP 套餐 |

转账、运营调整、返奖、退款等均不在消费口径内。“消费记录数”指符合条件的 ES 文档数，不等同于 `amount` 字段的件数或开盒次数。金额是支出总额，不扣除后续返还。全平台分母使用**同一时间段、同一业务类型和同一 status**，不加用户 ID 筛选。

ES 聚合和 JSON 始终保留整数 `tokens`；Python 先用整数合并，再按 **10,000 金币 = 1 美元**换算，美元显示 3 位小数。单个 ES 聚合桶超出浮点安全整数范围会拒绝生成报告，需缩短查询时间。逐行美元值独立舍入，极小金额下逐行相加可能与先汇总后换算的总计相差 0.001 美元，应以总计为准。

## 工作表

1. **总体对比**：名单人数、活跃／零消费人数、名单和全平台消费、名单占比，以及五类消费结构。
2. **业务类型**：七个业务编号的名单／全平台金币、美元、记录数及占比。
3. **每日趋势**：每日总量与每日五类消费明细；名单每日活跃用户数。
4. **时段习惯**：按小时、按星期的消费分布及与全平台同期对应时段的对比。
5. **用户明细**：每位名单用户的消费额、记录数、活跃天数、活跃日均金额、主要消费类型及五类金额；包含零消费用户。

用户 ID 不显示千分位，金币显示千分位，美元显示三位小数；表头冻结、可筛选、保留清晰的表格边框。报表生成前会核对每日与每小时聚合的金币和记录数总量一致。

## 本地验证

```bat
python -m unittest discover -s . -p "test_*.py" -v
```

测试使用模拟 ES，不访问真实集群。

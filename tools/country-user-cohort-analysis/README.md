# 国别注册用户留存与30天金额分析

独立的本地 Python 工具。按注册国家及注册时间选取用户，查询 MySQL 的用户、邀请、认证、每日活跃、充值、提现汇总，以及 ES 中的金币消费、钻石收益和金币／钻石奖励聚合，生成 JSON 和 Excel。不会修改 `es-server`，不会导出 ES 逐条流水。

## 安装

在 Anaconda Prompt 或 CMD 中执行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\country-user-cohort-analysis
conda activate luckyEv
python -m pip install -r requirements.txt
```

如果 `luckyEv` 没有 pip，先执行 `conda install -n luckyEv pip`。PowerShell 中把首行换成 `Set-Location C:\work\opts\sano\code\datahub\tools\country-user-cohort-analysis`。仅依赖 `PyMySQL` 和 `openpyxl`；ES 请求使用 Python 标准库。

## 配置

先复制示例文件：

```bat
copy .\mysql-config.example.json .\mysql-config.json
copy .\es-config.example.json .\es-config.json
copy .\analysis-config.example.json .\analysis-config.json
```

- `mysql-config.json`：MySQL 地址、库名、**只读账号**及密码。程序内固定的 SQL 全部是单条参数化 `SELECT`，不执行 `INSERT`、`UPDATE`、`DELETE` 或 DDL。仍强烈建议用仅有 `SELECT` 权限的数据库账号做第二道保护。
- `es-config.json`：ES URL、账号密码、超时。无认证时把账号、密码同时设为 `""`。HTTPS 默认验证证书；私有 CA 可增加 `"ca_cert": "ca.pem"`，相对路径按配置文件所在目录解析。程序只调用两个钱包索引的 `_search`。
- `analysis-config.json`：`country_code`、注册起止、观察截止、ES 时间区和业务类型名单。`register_start` 包含，`register_end` 不包含；`observation_end` 也不包含。例如 `2026-10-31 00:00:00` 表示数据完整观察至 10 月 30 日 23:59:59。日期均为 `yyyy-MM-dd HH:mm:ss`。

正式统计时，观察截止时间应已到达且数据源已同步完成；若提前运行一个未来的截止时间，尚未产生的交易不会凭空出现，报表只能反映运行时已入库的阶段数据，且“完整观察30天人数”会按配置截止日判断。请到截止时间之后重新运行生成最终报表。

`es_time_zone` 必须与 ES 中钱包记录 `create_time` 的时区解释一致。示例 `+00:00` 沿用现有工具处理无时区时间字符串的方式；若 ES 真正按上海本地时区解释日期，应改为 `Asia/Shanghai`。错误的时间区会让 D0/D29 边界偏移。

> 配置中的业务类型是可调整的白名单。默认消费沿用普通礼物 1、幸运礼物 11、游戏 21、道具 41、VIP 42/105/106。钻石收益白名单包含礼物与游戏分账及平台奖励；提现退款、兑换、运营调整不算收益。金币／钻石奖励白名单取平台奖励入账类型，不把幸运礼物或游戏的普通中奖、返奖算作“奖励领取”。运行前应按线上业务类型枚举复核白名单。

## 执行

```bat
python .\export_report.py --mysql-config .\mysql-config.json --es-config .\es-config.json --analysis-config .\analysis-config.json
```

中断后保持三份配置及数据库数据口径不变，可续跑同一批次：

```bat
python .\export_report.py --mysql-config .\mysql-config.json --es-config .\es-config.json --analysis-config .\analysis-config.json --resume
```

续跑会校验 MySQL 快照和已完成 ES 查询检查点，只重新执行未完成的 ES 查询；若相同配置有多个未完成批次，会拒绝猜测。MySQL、ES 连接密码不会写入清单。报告生成后拒绝覆盖；不带 `--resume` 再次运行会创建新的时间戳文件。输出与脚本同级，主要有：

如果 Windows 偶发 `PermissionError: [WinError 5]`，且报错位置是保存清单或分片文件，程序会对原子替换作有限次重试。仍失败时，先确认没有编辑器、同步软件或安全软件持续占用该文件，再使用上面的 `--resume` 命令；不要删除已有清单、MySQL 快照或 `.parts` 目录。清单中已完成的 ES 作业会跳过，已写出但尚未记入清单的那个作业会重新执行。

- `country-user-cohort-mysql-时间戳.json`：MySQL 用户与按天汇总的快照，含用户 ID、昵称等敏感信息。
- `country-user-cohort-mysql-queries-时间戳.sql`：本批次实际执行的每条 MySQL `SELECT`，参数已按驱动转义规则展开，便于复制核对；包含用户 ID，请按敏感数据保存。续跑若复用已有 MySQL 快照，不会重复执行或追加 SQL。
- `country-user-cohort-时间戳.json`：全部用户的最终聚合结果；ES `tokens` 保留整数。
- `country-user-cohort-manifest-时间戳.json`：进度及文件校验哈希。
- `.country-user-cohort-时间戳.parts/`：ES 分批聚合检查点。
- `国别注册用户30天分析-时间戳.xlsx`：分析报表。

真实配置、JSON、SQL 日志、工作簿和检查点已在根目录 `.gitignore` 中排除。请按敏感数据管理，勿发送至公开仓库。

## 统计口径

- 每个 `sano_user` 用户一行。国别使用注册后不变的 `country_code`；没有国家名称表时在报表中展示代码。邀请人来自 `sano_invite_log.user_id`，按 `invite_user_id = sano_user.id` 关联。
- 实名状态是运行时 `sano_user.real_person = 1` 的当前状态。实名时间取 `sano_anchor_real_error` 中首次成功时间：`type=0` 用 `create_time`，人工处理 `handle_status=1` 用 `handle_time`。曾成功但当前未实名的用户可能仍显示历史首次成功时间；两列含义不同。
- D1/D3/D7 分别是注册日后第 1/3/7 个自然日，在 `sano_user_active` 中存在对应 `(user_id, day_int)` 记录。`day_int` 使用 `YYYYMMDD` 整数，例如 2026-10-01 对应 `20261001`；不再查询登录日志，也不要求活跃表其他金额或时长字段大于零。目标自然日尚未完整结束时显示“未到期”，不算未留存。
- 金额统计窗口是每个用户注册日 D0 至 D29 的自然日，并与观察截止时间（不含）取交集。五项金额对所有注册用户均输出数值：窗口内没有相应记录为 `0.000`；尚未完整观察 30 天的用户显示截至观察截止时间的**阶段金额**，也纳入金额用户数与总额。报表另列“完整观察30天人数”，不能把阶段金额误认为最终 30 天金额。D1/D3/D7 仍只有目标日整天结束才纳入留存率分母，未到期显示“未到期”。
- 充值以 `sano_wallet_recharge.status=2`、`create_time` 归属。Google/Apple 用 `money`；三方（`producer_type=2`）非 USDT 用 `money`，USDT 用 `usdt_amount`；包含用户及币商三方充值，不包含 `producer_type=4` 用户找币商充值，也不包含其他渠道。这与提供的 `total_recharge_include_proxy_amount` SQL 口径一致。
- 成功提现以 `sano_wallet_withdraw.status=4`、`pay_time` 归属，取实际到账美元 `reality_money`，包含 payroll。
- ES 金币消费：`status=-1` 且业务类型在消费白名单。ES 钻石收益：`status=1` 且业务类型在收益白名单，包含平台奖励。ES 奖励领取：金币和钻石 `status=1` 且业务类型在奖励白名单。**钻石奖励同时包含在“30天收益”和“30天奖励领取”中，两列不可相加。**
- ES 不做美元换算：金币 `10,000 tokens = $1`；钻石 `10,000,000 tokens = $1`。先在 Python 中用整数合并，再用 `Decimal` 换算并显示 3 位小数。ES `sum` 返回浮点值，若单个聚合桶超过 2⁵³−1 或非整数，程序拒绝静默使用该结果。单个用户金额各自四舍五入到 0.001 美元后，汇总表按这些展示值加总。

## 工作表

1. **分析汇总**：查询条件、金额统计窗口、注册人数、实名、D1/D3/D7 已到期分母及留存率、完整观察 30 天人数、五项已发生金额的用户数和总额；明确标注收益／奖励重叠。
2. **注册日汇总**：按注册日观察用户数、留存与 30 天金额，可比较不同注册批次。
3. **用户明细**：用户 ID、昵称、国别、邀请人、注册时间、实名状态／时间、D1/D3/D7、充值、消费、收益、成功提现、奖励，共 15 列。

用户 ID 不显示千分位；金额为美元、保留三位小数。各工作表保留网格线和可见边框；明细与注册日汇总冻结表头、设置筛选。昵称作为纯文本写入，避免数据库昵称被 Excel 当作公式执行。

## 本地测试

```bat
python -m unittest discover -s . -p "test_*.py" -v
```

测试使用模拟 MySQL 与 ES，不访问真实服务。报表只依据查询结果分析，不会向 MySQL 或 ES 写入数据。

旧版本产生的聚合 JSON 使用登录日志计算留存，不能只重导出 Excel 得到新的 D1/D3/D7 结果。更新工具后请重新完整运行查询，生成新批次；旧批次文件不覆盖。之前更早的版本也未查询未满 30 天用户的 ES 金额，不能把旧数据的“未到期”直接改成零。

# 公会用户收益、提现、奖励与充值分析

独立的本地 Python 工具。指定多个公会 ID 和统计时间，使用 MySQL **只读查询**当前公会用户、成功提现及成功充值，使用 ES 聚合查询钻石收益、金币与钻石奖励，输出 JSON 和 Excel。参考 `user-consumption-analysis` 的配置分离、检查点和 `--resume` 方式，不修改 `es-server`，不下载逐条 ES 流水。

## 安装与配置

在 Anaconda Prompt 或 CMD 中执行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\guild-income-analysis
conda activate luckyEv
python -m pip install -r requirements.txt
copy .\mysql-config.example.json .\mysql-config.json
copy .\es-config.example.json .\es-config.json
copy .\analysis-config.example.json .\analysis-config.json
```

若环境缺少 pip，先运行 `conda install -n luckyEv pip`。PowerShell 的切目录命令改为 `Set-Location C:\work\opts\sano\code\datahub\tools\guild-income-analysis`。程序只依赖 PyMySQL 和 openpyxl；ES HTTP 使用 Python 标准库。

- `mysql-config.json`：填写 MySQL 地址、库名及**仅有 SELECT 权限**的账号密码。代码仅使用固定的、参数化的单条 `SELECT`，不会执行写入、DDL、临时建表或数据库事务修改。
- `es-config.json`：填写 ES 地址和可选的 Basic Auth 账号密码。若无认证，用户名和密码都设为 `""`。HTTPS 默认验证证书；私有 CA 可添加 `"ca_cert": "ca.pem"`，相对路径以配置文件所在目录为准。
- `analysis-config.json`：`proxy_ids` 是当前公会 ID 列表；`start` 包含、`end` 不包含。示例覆盖 **2026-09-21 00:00:00 至 2026-10-20 23:59:59**。ES `create_time` 的解释使用 `es_time_zone`；它必须与 ES 的实际时间语义一致。业务类型白名单沿用现有国别用户分析工具的收益／奖励口径，运行前请核对线上业务类型枚举。

示例中的 5 个公会 ID 仅是配置样例，可自行替换。公会归属以采集时 `sano_user.proxy_id` 的当前值为准，不按 `first_proxy_id` 扩展下级公会。每个公会下的**所有用户**都会进入统计，包括没有任何收益、提现或奖励的用户。用户若在统计期间转过公会，整个时间段的金额都归入采集时的当前公会。

## 运行

在工具目录中执行单行命令：

```bat
python .\export_report.py --mysql-config .\mysql-config.json --es-config .\es-config.json --analysis-config .\analysis-config.json
```

中断后保持三份配置不变，执行：

```bat
python .\export_report.py --mysql-config .\mysql-config.json --es-config .\es-config.json --analysis-config .\analysis-config.json --resume
```

续跑复用并校验已保存的 MySQL 快照和 ES 检查点，只重查未完成的 ES 作业；如果相同配置有多个未完成批次，会拒绝猜测。密码不会写入清单。首次运行和续跑都只读 MySQL/ES，文件仅写到本工具目录。

**使用新版程序采集且 MySQL 快照含充值字段的批次**，可用已完成的检查点离线重建同批次报表，无需连接 MySQL/ES，也不会覆盖旧报表：

```bat
python .\export_report.py --rebuild-from-checkpoints .\guild-income-manifest-你的批次.json
```

这会生成同批次、文件名带 `-提现订单奖励单列.xlsx` 的新报表；若目标文件已存在则拒绝覆盖。必须保留该批次的 MySQL 快照、最终 JSON、清单和 `.parts` 检查点目录，重建会校验文件哈希与每个检查点。

**旧版快照没有充值数据，不能把缺失金额当作零。** 可使用现有批次的 ES 检查点，**只向 MySQL 补查充值**，无需重查 MySQL 用户/提现或 ES：

```bat
python .\export_report.py --rebuild-from-checkpoints .\guild-income-manifest-20261024-010710-595737.json --fetch-missing-recharge --mysql-config .\mysql-config.json --es-config .\es-config.json --analysis-config .\analysis-config.json
```

此命令要求当前配置与旧批次的 MySQL 地址、库名、统计配置一致；只执行成功充值的只读 `SELECT`，并另存 `guild-income-批次-提现订单奖励单列.json` 和 `公会用户收益提现奖励分析-批次-提现订单奖励单列.xlsx`，不覆盖旧文件。查询时刻与旧批次 ES/MySQL 用户快照可能不同；若源数据在两次查询之间发生变更，应重新完整采集以获得统一时间点的数据。

输出包括：

- `guild-income-mysql-时间戳.json`：当前公会用户、提现及充值汇总的 MySQL 快照。
- `guild-income-时间戳.json`：最终按用户及全平台汇总的 JSON，ES tokens 保留整数。
- `guild-income-manifest-时间戳.json`：进度与文件校验哈希。
- `.guild-income-时间戳.parts/`：ES 聚合检查点。
- `公会用户收益提现奖励分析-时间戳.xlsx`：三张工作表的分析报表。

真实配置、JSON、XLSX、检查点均属于敏感数据，已在根目录 `.gitignore` 排除。不要提交账号密码或用户明细。

## 查询与统计口径

MySQL 只执行以下五类 `SELECT`：

1. `sano_user`：按当前 `proxy_id IN (...)` 查询 `id`、`nick`、`proxy_id`；不附加主播状态、封禁或注销过滤。没有用户的配置公会仍在公会汇总中显示零。
2. `sano_wallet_withdraw`：`status=4` 且 `pay_time >= start AND pay_time < end`，按选定公会用户分批聚合 `reality_money`，得到用户实际到账美元。
3. 对同一提现条件、不加用户过滤，再求全平台所有用户成功提现美元总额；包括 payroll。
4. `sano_wallet_recharge`：`status=2` 且 `create_time >= start AND create_time < end`，按选定公会用户分批汇总成功充值美元。
5. 对同一充值条件、不加用户过滤，再求全平台所有用户成功充值美元总额。Google/Apple（`producer_type` 为 0/1）取 `money`；三方（2，`coin_business=0` 或 `>0`）非 USDT 取 `money`，USDT 取 `usdt_amount`。**不把 `producer_type=4` 用户找币商充值计入总充值**，也不计其他渠道。这与此前提供的 `total_recharge_include_proxy_amount` SQL 口径一致。

ES 只对 `sano_wallet_coin_record`、`sano_wallet_diamond_record` 执行 `_search` 聚合：

- 钻石收益：`status=1` 且 `business_type` 在 `diamond_income_types`。全平台不加用户 ID 筛选；选定公会按用户 ID 分批聚合。
- 钻石入账合计包含 `business_type=55` 的**提现订单奖励**。单列“不含55的钻石收益”和“提现订单奖励55”；两项之和等于入账合计。用户明细中的不含55收益再按业务类型拆为：**普通礼物** `2/3/4/5`（收到礼物及代理、子代理、房主分账）、**幸运礼物** `12/13/14`（收到礼物及代理、子代理分账）、**其他**（余下的收入类型，但不含55）。分类依据 `EBusinessType`，按原始整数 tokens 汇总；三类之和必须等于不含55收益。配置的 `diamond_income_types` 必须包含55。
- 金币奖励：`status=1` 且 `business_type` 在 `coin_reward_types`。
- 钻石奖励：钻石收益中属于 `diamond_reward_types` 且**不等于55**的部分。金币奖励与这部分钻石奖励合称“其他奖励”。55无论是否仍列在钻石奖励白名单，均只计入“提现订单奖励”，不会重复计入“其他奖励”。其他钻石奖励已经计入不含55的钻石收益，因此收益与奖励不可直接相加。
- 当前示例的金币奖励类型为 `62,64,66,68,70,72,76,78,80,82,84,86,88,89,92,97,99,103,104,107,108`；钻石奖励类型为 `55,61,63,65,67,69,71,73,74,75,77,79,81,83,85,87,90,91,93,94,95,96,98,100,101,109,110`。其中 `55` 是“提现订单奖励”，`103` 是“世界杯竞猜奖励”，`104` 是“房间奖池奖励”；`111/112` 当前**不在**白名单。业务名称以 `EBusinessType` 和实际线上配置核对为准。若只想统计某一类平台奖励，应先调整两种奖励白名单，再重新采集，不能拿不同白名单的数据库 SQL 直接比较。
- 各项占比均用同期、同口径的全平台**所有用户**金额作分母，不限公会或主播。分母为零时显示“无分母”，不伪装成 0%。

ES 和 JSON 中的 tokens 不提前换算或转为浮点。Python 用整数合并后，金币按 **10,000 tokens = $1**、钻石按 **10,000,000 tokens = $1**，用 `Decimal` 换算，Excel 美元显示三位小数；占比使用未舍入金额计算。ES `sum` 响应若不是整数或单个桶超过 2⁵³−1，则拒绝静默使用；需缩短统计段。平台和公会金额分别从原始整数换算，因此逐行已舍入美元相加偶有 0.001 美元差异，以原始整数汇总值为准。

## 工作表

1. **总体对比**：公会 ID、统计区间、归属口径，以及钻石入账合计、不含55收益、提现订单奖励55、成功提现、其他奖励和成功充值的金额与占比。
2. **公会汇总**：逐公会当前用户数、钻石入账合计、不含55收益、提现订单奖励55、成功提现、其他奖励、成功充值及相应的平台占比；保留无用户公会。
3. **用户明细**：每个当前公会用户的 ID、昵称、钻石入账合计、不含55收益、提现订单奖励55、幸运礼物收益、普通礼物收益、其他收益、实际成功提现、金币和钻石其他奖励、成功充值；保留零金额用户。分类美元金额分别保留三位小数，显示值相加可能与总额差 $0.001，以 JSON 中完全相等的原始 tokens 为准。

用户和公会 ID 不显示千分位，tokens 显示千分位，美元显示三位小数；明细和公会汇总冻结表头、可筛选，各工作表保留清晰边框。昵称强制写为文本，避免以 `=` 开头的昵称成为 Excel 公式。

## 离线测试

```bat
python -m unittest discover -s . -p "test_*.py" -v
```

测试注入模拟 MySQL 和 ES，不访问真实服务。验证读取范围、续跑、整数与美元换算、占比和报表结构。

# 游戏消费与利润分析

独立本地 Python 工具。按指定时间段分析**采集时游戏列表 `status=1` 的可用游戏**，统计各游戏、各注册国家及每日游戏消费。用户范围仍是全平台，不限制公会。ES 只查询 `sano_wallet_coin_record` 的聚合桶；MySQL 只读游戏名称、国家名称及用户固定注册国家。不查询钻石记录，不下载逐条金币流水，也不修改 `es-server`。

## 安装与配置

在 Anaconda Prompt 或 CMD 中运行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\game-analysis
conda activate luckyEv
python -m pip install -r requirements.txt
copy .\mysql-config.example.json .\mysql-config.json
copy .\es-config.example.json .\es-config.json
copy .\analysis-config.example.json .\analysis-config.json
```

PowerShell 中改用 `Set-Location C:\work\opts\sano\code\datahub\tools\game-analysis`，随后可运行同样的 `conda activate luckyEv` 和 Python 命令。如果 luckyEv 未安装 pip，先在 Anaconda Prompt 中运行 `conda install -n luckyEv pip`。

- `mysql-config.json` 填 MySQL 地址、数据库及**只有 SELECT 权限**的账号。程序仅运行固定的参数化单条 SELECT，实际执行 SQL 另存本地，供核对；不写 MySQL。
- `es-config.json` 填 ES 链接、Basic Auth 账号和密码；无认证时用户名、密码均设 `""`。私有 CA 可加 `"ca_cert": "ca.pem"`。ES 请求只读 `_search`。
- `analysis-config.json` 填起止时间与比率。`start` 包含、`end` 不包含；示例统计整个 2026 年 9 月。`es_time_zone` 须与 ES 的 `create_time` 时间语义一致，使用固定偏移如 `+08:00`。比率写十进制字符串，不使用 JSON 浮点数。`game_margin_rate=0.04`，`anchor_rate=0.005`，`jackpot_pool_rate=0.005`，`service_fee_share=0.15`，程序推导游戏服务费率 0.006 和平台利润率 0.024。

配置样例使用占位账号；运行前需要复制并填写真实只读连接。真实配置及所有生成的 JSON、SQL、XLSX 和检查点已被根目录 `.gitignore` 排除。

## 运行

在工具目录使用单行命令：

```bat
python .\export_report.py
```

中断后保持三份配置不变，执行 `python .\export_report.py --resume`。程序会校验已完成的字典和 ES 聚合分页，仅继续未完成查询。若找到多个同配置未完成批次则拒绝猜测。

**本版范围为可用游戏，与旧版全游戏批次不同。** 升级后首次运行请使用 `python .\export_report.py` 开始新批次；旧批次文件保留。不能把旧版全游戏分页直接续跑为新版可用游戏分页。新版续跑始终复用批次开始时的可用游戏快照，期间启用状态变化不会混入同一批次；如需今天最新状态，请开始新批次。

遇到连接重置（Windows `WinError 10054`）、远端断开、响应未读完整、网络超时或可重试 HTTP 错误时，程序会等待后重试**当前页**，每次请求最多尝试3次。只有当前页的完整 ES 响应和用户国家查询都成功后才提交检查点，已完成页不会因网络重试而重复计入。连续失败后可运行：

```bat
python .\export_report.py --resume
```

例如已显示 users 第1至3页完成，第4页断连，续跑会校验前三页并从第4页继续。续跑前保持 `analysis-config.json` 不变（包括 `es_page_size`），否则不能复用原批次；连接中断的具体原因仍需结合 ES、网络或代理日志排查。

使用已完成批次离线重建报表，不需要 MySQL/ES：

```bat
python .\export_report.py --rebuild-from-checkpoints .\game-analysis-manifest-你的批次.json
```

已完成的旧版全游戏批次也可离线重建：程序先校验原数据，再按**原字典快照中 `status=1` 的游戏**重算金额、消费人数及各项占比。结果另存 `-可用游戏-重建-时间戳.xlsx`，不覆盖旧报表。离线重建不会查询今天的启用状态，报表顶部会标明所用游戏状态快照的采集时间。

也可通过 `--mysql-config`、`--es-config`、`--analysis-config` 指定不同路径。首次运行输出文件名带批次时间，默认全部位于本工具目录：

- `game-analysis-mysql-批次.json`：游戏/国家字典快照；
- `game-analysis-sql-批次.sql`：实际执行的只读 SQL；
- `.game-analysis-批次.parts/`：每页 ES DSL、聚合响应提炼数据和用户注册国家检查点；
- `game-analysis-manifest-批次.json`：配置身份、进度和文件 SHA-256；
- `game-analysis-批次.json`：最终原始 tokens 整数聚合；
- `游戏分析-批次.xlsx`：五张工作表，离线重建另存新文件名。

## 查询与分析口径

ES 业务类型以 `EBusinessType` 为准，只取以下三类金币记录：

| 指标 | 业务类型 | status | ES 来源 |
| --- | ---: | ---: | --- |
| 游戏消费 | 21 | -1 | `tokens`、记录数、`user_id`、`prop_id` |
| 普通游戏返奖 | 22 | 1 | `tokens`、记录数、`user_id`、`prop_id` |
| 用户游戏 Jackpot 奖励 | 26 | 1 | `tokens`、记录数、`user_id`、`prop_id` |

`prop_id = sano_game_list.id`。`producer_game_id` 只是厂家编号。金币按 **10000 tokens = 1 美元**，Python 用整数聚合、`Decimal` 运算，直到写报表才显示三位小数。普通返奖、用户 Jackpot 奖励以及各自的奖励率分别展示，不设置返奖合计。`23/24/25` 是钻石分账，不在本工具查询范围；`78/99` 等活动奖励也不计入游戏普通返奖。

平台利润是**固定比例计算结果**，不是简单的“消费减同期两种奖励”。计算为：

```text
主播利润 = 消费金额 × 0.005
Jackpot 奖励池计提 = 消费金额 × 0.005
游戏服务费 = 消费金额 × 0.04 × 0.15 = 消费金额 × 0.006
平台利润 = 消费金额 × (0.04 - 0.005 - 0.005 - 0.006) = 消费金额 × 0.024
```

用户实际获得的 `26` 奖励与按消费计提的 Jackpot 奖励池是两个不同指标。每日实际派奖和当日计提可能因奖池跨期而不同，报表分别展示。

游戏列表只读查询如下（不额外添加删除标记条件，完全按 `status=1` 判断可用）：

```sql
SELECT id, producer_game_id, producer_code, ch_name, en_name,
       status, delete_flag, create_time
FROM sano_game_list
WHERE status = 1
ORDER BY id;
```

MySQL 还从 `sano_config_country` 查询国家名称，按 ES 聚合涉及的用户 ID 分批查询 `sano_user.country_code`。ES 的期间用户聚合和每日聚合都限制 `prop_id` 在上述可用游戏 ID 快照中；停用、未匹配配置的游戏不进入任何金额、人数或占比。没有可用游戏时产生零结果，不会退化为查询全部游戏。游戏/国家汇总的占比分母也仅包含可用游戏。

统计采用固定注册国家；用户 ID 或国家资料缺失时，范围内金额照计并标记。使用状态以采集时快照为准，**不代表该游戏在统计期间每一天都启用**。统计终点前创建的可用游戏即使零消费也会显示；`create_time` 不能证明正式上线日期。游戏汇总、国家游戏汇总及每日趋势显示中文“使用状态＝使用中”，同时在分析汇总顶部写明可用范围及字典采集时间。

ES 用户聚合以 `user_id → prop_id` 排序，用 composite 游标分页。页内记录已是“用户×游戏”的汇总；逐用户扫描即可精确统计总体、每游戏和每国家消费人数，内存不随用户总数线性增长。每日趋势按天分别查询“游戏”聚合，包含两个不同日期边界下的部分天；每日不计算去重人数。程序核对全期与每日的原始 tokens 和记录数，若查询期间 ES 数据变化造成不一致会报错，不输出误导性报表。ES 单桶金额超过精确整数上限会提示缩短时间段。

## 五张工作表

1. **分析汇总**：配置口径、各总金额和人数、消费高低游戏排名、资料异常计数。
2. **游戏汇总**：可用游戏的消费金额排名、使用状态、消费人数、消费记录数、金额占比、人均消费、普通返奖、用户 Jackpot 奖励、固定比例成本利润、有消费天数和最大用户占比，含合格零消费游戏。
3. **国家汇总**：各注册国家的去重消费人数、金额、两项奖励和平台利润。
4. **国家游戏汇总**：每个国家各可用游戏的使用状态、消费人数、金额、占本国可用游戏消费比例和国家内金额排名。
5. **每日游戏趋势**：每天每可用游戏的使用状态、消费记录数、消费/奖励金额及各项利润费用；无记录按零显示，但不会给晚于当天创建的游戏补齐先前零值。

同一用户玩多个游戏，各游戏消费人数会分别计入，因此不可加总当作总体人数。缺失用户 ID 的金额仍进入金额汇总，但该记录无法用于人数、人均及最大用户占比，这些位置明确显示资料不足。零分母时比例显示“无分母”。所有表有清晰边框；游戏/国家 ID 不显示千分位，美元显示三位小数，明细表冻结标识列和表头并设筛选。配置和名称文本强制作为 Excel 文本写入，避免公式注入。

## 离线测试

```bat
python -m unittest discover -s . -p "test_*.py" -v
```

测试模拟 MySQL/ES，不需要线上服务，覆盖可用游戏过滤及空列表、旧批次离线范围转换、国别去重、零消费、奖励分列、利润公式、部分日边界、分页续跑、检查点校验及表格结构。

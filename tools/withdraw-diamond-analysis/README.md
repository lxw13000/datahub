# 提现用户钻石来源、用途分析工具

独立的本地 Python 工具。你提供完整的提现用户 ID CSV；工具连接 ES，按用户、余额方向和业务类型汇总指定时间内的钻石流水，保存扁平 JSON，并生成五张工作表的 Excel 报告。不连接 MySQL，不依赖 Spring Boot，也不修改 `es-server`。

## 安装

在 Anaconda Prompt 或 CMD 中执行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\withdraw-diamond-analysis
conda activate luckyEv
python --version
python -m pip install -r requirements.txt
```

若提示 `No module named pip`，先执行 `conda install -n luckyEv pip`。唯一的第三方依赖是 `openpyxl>=3.1,<4`；连接 ES 使用 Python 标准库。

## 准备用户 ID 文件

用户 ID CSV 是本次分析的**完整提现用户名单**，包括没有钻石流水的用户。推荐单列格式：

```csv
userId
11019438
11025156
11041900
```

目录中已放置 [withdraw-user-ids.csv](withdraw-user-ids.csv) 示例，里面的 `10000001`、`10000002` 是占位 ID，运行前请替换为真实名单。也接受无表头的单列 CSV。空行、重复 ID 会忽略；第一列必须是完整整数 ID，不要使用科学计数法。若文件没有任何有效 ID，程序会报错并停止，不会查询 ES 或生成报表。工具不会根据提现时间自动查询 MySQL，因此你应先按提现时间范围导出正确名单。

## 配置与执行

ES 连接与分析口径分别配置。复制两个示例文件：

```bat
copy .\es-config.example.json .\es-config.json
copy .\analysis-config.example.json .\analysis-config.json
```

`es-config.json` 只填写连接信息：

```json
{
  "es_url": "https://your-es-host:9200",
  "username": "your_username",
  "password": "your_password",
  "timeout_seconds": 300
}
```

`analysis-config.json` 只填写本次分析的名单和时间口径：

```json
{
  "user_ids_file": "withdraw-user-ids.csv",
  "withdraw_start": "2026-07-20 00:00:00",
  "withdraw_end": "2026-07-31 23:59:59",
  "statistics_start": "2026-07-01 00:00:00",
  "statistics_end": "2026-07-31 23:59:59",
  "exclude_business_types": [74, 503]
}
```

`user_ids_file` 的相对路径以 `analysis-config.json` 所在目录为基准。`withdraw_start`、`withdraw_end` 仅说明 CSV 名单的来源范围并写入报表，**不会在 ES 中筛选用户**；`statistics_start`、`statistics_end` 用于 ES `create_time` 查询，起止时刻均包含。`exclude_business_types` 同时用于 ES 查询和报表口径。无认证的 ES 可将 `username`、`password` 都设为空字符串。HTTPS 默认校验证书；使用私有 CA 时可在 `es-config.json` 加 `"ca_cert": "ca.pem"`，路径相对于该配置文件，不建议关闭验证。

填写完成后，在 CMD 或 Anaconda Prompt 中运行单行命令：

```bat
python .\export_report.py --es-config .\es-config.json --analysis-config .\analysis-config.json
```

现在不需要 `--input`，也不需要手动调用 `/walletDiamond/withdrawAnalysis`。`es-config.json` 含明文密码，用户 CSV 和响应 JSON 含用户数据；这些文件已加入 `.gitignore`，请勿提交或分享。

若运行中断，确认两个配置文件及用户 ID CSV **未改变**，用原命令加 `--resume`：

```bat
python .\export_report.py --es-config .\es-config.json --analysis-config .\analysis-config.json --resume
```

工具按每 1,000 个用户保存一个完整批次检查点；续跑会跳过已完成的批次，未完成的当前批次会重新查询。续跑清单会核对 ES 地址、时间范围、排除业务类型、用户名单哈希及查询 DSL 哈希，不会混用不同口径，也不保存密码。`--resume` 要求同一口径恰好有一个未完成批次；如果存在多个未完成批次，先人工核对并移开不需要的批次文件。正常完成的报表不能用 `--resume` 覆盖；重新执行不加该参数会生成新的时间戳文件。

## 查询与输出口径

工具按 1,000 个用户一批查询 `sano_wallet_diamond_record` 索引别名，使用 ES composite 聚合完整翻页，聚合键为 `user_id + status + business_type`，汇总字段为整数 `tokens`。`status=1` 表示加余额，`status=-1` 表示减余额。原始流水不会逐条下载。查询超时、分片失败、聚合结果不完整或数值超出 ES 浮点安全整数范围时，工具报错且不生成新的报表；这时可以在相同口径下续跑，或缩短统计时段新开一次分析，不要把异常结果当作完整数据。

**美元换算只在最终写 Excel 时进行**：`10,000,000 tokens = 1 美元`，先按整数 tokens 完成所有汇总，再用十进制算法四舍五入到 3 位小数。ES 聚合和导出的 JSON 都不换算；报表中的美元列以 3 位小数显示。各行美元显示值经过独立舍入，极小金额下相加可能与总计美元相差千分之一美元；应以先汇总 tokens 再换算的总计为准。

运行结果**直接保存在 `export_report.py` 同级目录**，无需配置输出路径。每次运行自动附加时间戳，避免覆盖已有文件：

- `withdraw-analysis-时间戳.json`：与原接口兼容的 `{"code":200,"data":[...],"success":true}` 扁平汇总 JSON。每条含 `userId`、`status`、`businessType`、`tokens`。
- `withdraw-analysis-manifest-时间戳.json`：ES 地址（不含密码）、查询时间、排除类型、用户数、用户 ID 哈希和聚合记录数，用于核对批次口径。
- `提现用户钻石来源用途分析-时间戳.xlsx`：Excel 分析报告。
- `.withdraw-analysis-时间戳.parts`：续跑检查点目录，每个文件对应一个完整的用户批次；请在报表生成前保留。

Excel 保留原来的五张工作表：

1. **分析汇总**：查询条件、核心指标、按方向和业务类型的用户数／Tokens／美元／占比、三组 Top 20、最大用户占比及剔除影响。
2. **用户汇总**：每个用户的加余额、减余额、净额（均有 Tokens 和美元）、业务数和业务明细；零流水用户也在其中。
3. **加余额明细**：按 `userId + businessType` 汇总 Tokens 与美元。
4. **减余额明细**：按 `userId + businessType` 汇总 Tokens 与美元。
5. **业务类型汇总**：方向、业务类型、涉及用户数、Tokens、美元及方向内占比。

用户 ID 不显示千分位，Tokens 显示千分位，净额负数标红、正数标绿。工作表保留可见网格线，表头及数据区使用清晰的浅灰边框，冻结表头并设置筛选。业务类型中文名称由工具内的映射表提供；未知编号显示“未知业务类型”。

## 本地验证

```bat
python -m unittest discover -s . -p "test_*.py" -v
```

测试使用模拟 ES 响应，不需要真实 ES 账号。实际连接和查询需在填写自己的两个配置文件后执行。

# 提现用户钻石来源、用途分析工具

这是一个独立的本地 Python 命令行工具。它读取
`/walletDiamond/withdrawAnalysis` 接口返回的扁平 JSON，按用户、余额变化方向和业务类型汇总，
再生成结构化 Excel 分析报告。工具不依赖 Spring Boot，也不会修改 `es-server`。

## 环境与安装

建议使用 Python 3.10 或更高版本。

### 使用现有 Conda 环境（推荐）

在 Anaconda Prompt 或 CMD 中进入工具目录并激活 `luckyEv`：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\withdraw-diamond-analysis
conda activate luckyEv
python --version
python -m pip install -r requirements.txt
```

如果安装依赖时提示 `No module named pip`，先执行：

```bat
conda install -n luckyEv pip
python -m pip install -r requirements.txt
```

安装完成后可以检查实际使用的 Python 和 `openpyxl`：

```bat
where python
python -c "import openpyxl; print(openpyxl.__version__)"
```

### 使用独立 venv（可选）

```powershell
cd C:\work\opts\sano\code\datahub\tools\withdraw-diamond-analysis
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

运行时唯一的第三方依赖是 `openpyxl`。

## 准备接口 JSON

把接口完整响应保存为 UTF-8 JSON 文件，例如 `withdraw-analysis.json`：

```json
{
  "code": 200,
  "data": [
    {
      "userId": 11019438,
      "status": 1,
      "businessType": 2,
      "tokens": 20650000
    },
    {
      "userId": 11019438,
      "status": -1,
      "businessType": 53,
      "tokens": 100000000
    }
  ],
  "success": true
}
```

也支持直接使用数组作为根节点：

```json
[
  {
    "userId": 11019438,
    "status": 1,
    "businessType": 2,
    "tokens": 20650000
  }
]
```

`userId`、`status`、`businessType` 和 `tokens` 必须是整数或整数字符串。工具会拒绝 JSON
小数，并始终使用 Python 整数参与汇总，不会先转换为浮点数。`status=1` 表示加余额，
`status=-1` 表示减余额。输入中重复的 `userId + status + businessType` 会自动合并。

## 可选的提现用户 ID 文件

接口只返回存在钻石流水的组合。若还需要在“用户汇总”中保留没有钻石流水的提现用户，
请准备 CSV 文件并通过 `--user-ids` 传入。

推荐格式：

```csv
userId
11019438
11025156
11041900
```

也支持没有表头的单列 CSV。第一列必须是完整整数 ID，不要使用科学计数法。空行和重复 ID
会被忽略。

## 完整执行命令

### Anaconda Prompt / CMD 单行命令

激活 Conda 环境后，可以直接复制以下整行执行：

```bat
python .\export_report.py --input .\withdraw-analysis.json --output ".\提现用户钻石来源用途分析2.xlsx" --user-ids .\withdraw-user-ids.csv --withdraw-start "2026-07-20 00:00:00" --withdraw-end "2026-07-31 23:59:59" --statistics-start "2026-07-01 00:00:00" --statistics-end "2026-07-31 23:59:59"
```

包含业务类型排除参数的单行示例：

```bat
python .\export_report.py --input .\withdraw-analysis.json --output ".\提现用户钻石来源用途分析2.xlsx" --user-ids .\withdraw-user-ids.csv --withdraw-start "2026-07-20 00:00:00" --withdraw-end "2026-07-31 23:59:59" --statistics-start "2026-07-01 00:00:00" --statistics-end "2026-07-31 23:59:59" --exclude-business-types 74 503
```

Anaconda Prompt 默认使用 CMD 语法。若确实需要多行命令，续行符是 `^`；PowerShell 的反引号
`` ` `` 在 CMD 中不会续行，因此最稳妥的方式是使用上面的单行命令。

### PowerShell 多行命令

以下 PowerShell 命令同时演示用户 ID 文件和业务类型排除参数：

```powershell
python .\export_report.py `
  --input .\withdraw-analysis.json `
  --output .\提现用户钻石来源用途分析.xlsx `
  --user-ids .\withdraw-user-ids.csv `
  --withdraw-start "2026-07-01 00:00:00" `
  --withdraw-end "2026-07-31 23:59:59" `
  --statistics-start "2026-01-01 00:00:00" `
  --statistics-end "2026-07-31 23:59:59" `
  --exclude-business-types 74 503
```

不需要保留零流水用户时，省略 `--user-ids`。不需要在本地再次排除业务类型时，省略
`--exclude-business-types`。排除编号也可以使用逗号分隔，例如
`--exclude-business-types 74,503`。

四个时间参数会原样写入报告顶部，并按接口使用的 `yyyy-MM-dd HH:mm:ss` 格式校验。
它们用于记录本次查询口径；本地工具不会根据时间二次过滤，因为接口扁平结果中不含流水时间。
`--exclude-business-types` 会在本地再次过滤输入，因此即使接口请求时已经排除，也可以把同一组
编号写入报告口径。

如果目标文件已经存在，工具会用新报告替换它。

## 输出工作表

- **分析汇总**：查询条件、核心指标、按方向和业务类型的用户数/Tokens/占比、三组 Top 20、
  最大用户占比，以及剔除该用户后的加余额、减余额和净额。
- **用户汇总**：每个用户的加余额、减余额、净额、业务数和业务明细；通过 ID 文件补入的零流水
  用户也会出现在这里。
- **加余额明细**：按 `userId + businessType` 合并后的加余额记录。
- **减余额明细**：按 `userId + businessType` 合并后的减余额记录。
- **业务类型汇总**：方向、业务类型、中文备注、涉及用户数、Tokens 总数和方向内占比。

用户 ID 使用普通整数格式，不显示千分位；Tokens 使用千分位。净额负数显示为红色，正数显示为
绿色。数据表均冻结表头、启用筛选，并设置了适合查看的列宽和数字格式。业务类型中文名称来自
当前 `EBusinessType` 枚举；遇到新编号时会显示“未知业务类型”，但不会阻止报告生成。

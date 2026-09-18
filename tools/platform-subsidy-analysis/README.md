# 平台补贴汇总工具

这是一个独立的本地 Python 命令行工具，用于合并分析以下两个接口返回的每日业务汇总 JSON：

- `/walletCoinAnalysis/platformSubsidyByType`
- `/walletDiamondAnalysis/platformSubsidyByType`

工具不会调用接口，也不依赖 Spring Boot。它读取已经保存到本地的两个接口响应，按日期和业务
类型重新汇总，生成适合管理者查看的 Excel 报告。

## 金额换算规则

- 金币：`10,000 Tokens = 1 USD`
- 钻石：`10,000,000 Tokens = 1 USD`
- 美元金额在 Excel 中显示三位小数。
- Tokens 始终使用 Python 整数读取和汇总，不会转换为浮点数。
- 金币和钻石 Tokens 单位不同，不直接相加；两者统一换算为美元后计算总补贴。

## Conda 环境与安装

在 Anaconda Prompt 或 CMD 中执行：

```bat
cd /d C:\work\opts\sano\code\datahub\tools\platform-subsidy-analysis
conda activate luckyEv
python -m pip install -r requirements.txt
```

在已初始化 Conda 的 PowerShell 中执行：

```powershell
cd C:\work\opts\sano\code\datahub\tools\platform-subsidy-analysis
conda activate luckyEv
python -m pip install -r requirements.txt
```

如果提示 `No module named pip`，先执行：

```bat
conda install -n luckyEv pip
python -m pip install -r requirements.txt
```

运行时唯一的第三方依赖是 `openpyxl`。

## JSON 文件准备

建议把两个接口响应分别保存为：

- `coin-platform-subsidy.json`
- `diamond-platform-subsidy.json`

当前接口返回按天嵌套的业务汇总：

```json
{
  "code": 200,
  "data": [
    {
      "dt": "2026-08-01",
      "stats": [
        {
          "businessType": 97,
          "tokens": 12000000
        },
        {
          "businessType": 99,
          "tokens": 3000000
        }
      ]
    },
    {
      "dt": "2026-08-02",
      "stats": [
        {
          "businessType": 97,
          "tokens": 0
        },
        {
          "businessType": 99,
          "tokens": 5000000
        }
      ]
    }
  ],
  "success": true
}
```

也支持直接以每日汇总数组作为 JSON 根节点：

```json
[
  {
    "dt": "2026-08-01",
    "stats": [
      {
        "businessType": 97,
        "tokens": 12000000
      }
    ]
  }
]
```

要求：

- `dt` 必须是 `yyyy-MM-dd`。
- `businessType` 和 `tokens` 必须是整数或整数字符串。
- JSON 中的日期不能超出命令行指定的统计范围。
- 重复的 `dt + businessType` 会自动合并。
- 缺失日期和接口固定业务类型会在本地补零。
- 输入出现固定集合之外的业务类型时，数据会保留，并在报告中以黄色底色标记为“未知业务类型”。

旧版不含 `dt` 的扁平 JSON 无法生成每日分析，因此不再支持。

## 完整单行执行命令

Anaconda Prompt、CMD 和 PowerShell 均可直接执行：

```bat
python .\export_report.py --coin-input .\coin-platform-subsidy.json --diamond-input .\diamond-platform-subsidy.json --output ".\平台补贴汇总.xlsx" --start-date "2026-08-01" --end-date "2026-08-31"
```

日期参数既用于记录报告口径，也用于检查输入日期和补齐缺失日期。结束日期不能早于开始日期。
如果目标文件已经存在，工具会用新报告替换它。

## 输出工作表

### 总汇总

展示统计范围、换算规则、补贴总额、金币和钻石补贴、日均补贴、有/无补贴天数、最高补贴日期、
最高单日金额和业务补贴金额排名。金币和钻石金额统一换算为美元后汇总。

### 按业务总汇总

按钱包和业务类型展示周期 Tokens、美元金额、钱包内占比、总补贴占比、有补贴天数、周期日均、
最高单日金额和最高日期，默认按美元金额从高到低排列。

### 每天汇总

按日期展示金币 Tokens/美元、钻石 Tokens/美元、当日补贴总额、周期占比和累计补贴金额。日期
连续，零补贴日期也会保留，并附带每日补贴金额趋势图。

### 每天按业务汇总

将接口嵌套数据展平为日期、钱包、业务类型、Tokens、美元金额和三种占比。固定业务类型的零值
记录完整保留，便于筛选任意业务查看每日变化。

所有数据表均冻结表头、启用筛选并设置适合查看的列宽。Tokens 显示千分位，美元显示三位小数，
占比显示两位小数。业务中文名称来自当前两个接口使用的 `EBusinessType` 固定集合。

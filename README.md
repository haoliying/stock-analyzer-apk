# 股票实时分析 Android 应用

完整的 Android 股票分析应用，支持实时行情、技术分析、智能推荐和持仓管理。

## 下载

### v3.0 最新版本

- **APK 安装包**: [StockAnalyzer-v3.0.apk](https://github.com/haoliying/stock-analyzer-apk/releases/download/v3.0.0/StockAnalyzer-v3.0.apk) (22MB)
- **源代码包**: [StockAnalyzer-v3.0-source.zip](https://github.com/haoliying/stock-analyzer-apk/releases/download/v3.0.0/StockAnalyzer-v3.0-source.zip) (6.6MB)

### 历史版本

- v2.0: [StockAnalyzer-v2.0.apk](https://github.com/haoliying/stock-analyzer-apk/releases/download/v2.0.0/StockAnalyzer-v2.0.apk)
- v1.0: [StockAnalyzer-v1.0.apk](https://github.com/haoliying/stock-analyzer-apk/releases/download/v1.0.0/StockAnalyzer-v1.0.apk)

## v3.0 更新内容

- ✅ 股票代码自动填充名称
- ✅ 拼音首字母搜索股票（如输入 "gzmt" 找到贵州茅台）
- ✅ 修复非交易时段数据，使用最近收盘价计算盈亏
- ✅ 加仓/卖出建议通过系统推送通知提醒
- ✅ 优化持仓管理界面

## 主要功能

### 1. 每日推荐
- 自动分析沪深股市，推荐最佳购买股票 Top 5
- 实时行情更新，随市场调整推荐

### 2. 我的持仓
- 添加股票自动填充名称
- 支持拼音首字母快速搜索
- 实时计算资产和盈亏
- 加仓/减仓价格区间建议
- 买卖建议系统推送通知

### 3. 行情列表
- 自选股实时行情
- 添加/删除自选股
- 点击查看详情

### 4. 技术分析
- K线图展示
- MA (5/10/20)、RSI (14)、MACD (12/26/9)、KDJ (9) 指标
- 综合评分推荐系统

## 技术栈

- **语言**: Kotlin 1.9.20
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM (Model-View-ViewModel)
- **网络**: Retrofit 2.9.0 + OkHttp 4.12.0
- **数据**: Gson + SharedPreferences
- **图表**: MPAndroidChart 3.1.0

## 数据源

- **实时行情**: 新浪财经 API (hq.sinajs.cn)
- **历史数据**: 东方财富 API (push2his.eastmoney.com)

## 使用说明

1. 下载 APK 安装到 Android 手机
2. 首次启动授予网络访问权限
3. 在"我的持仓"中添加股票（支持代码输入自动填充、拼音搜索）
4. 查看"每日推荐"获取智能推荐
5. 系统会在建议加仓/卖出时推送通知

## 注意事项

- 需要 Android 8.0 (API 26) 及以上版本
- 需要网络连接获取实时数据
- 交易时段（9:30-15:00）数据实时更新
- 非交易时段显示最近收盘价

## 项目结构

```
StockAnalyzer/
├── app/src/main/java/com/stock/analyzer/
│   ├── StockApplication.kt           # Application 入口
│   ├── data/
│   │   ├── api/                      # API 接口
│   │   ├── model/                    # 数据模型
│   │   └── repository/               # 数据仓库
│   ├── analysis/                     # 技术分析算法
│   ├── ui/                           # 界面
│   │   ├── MainActivity.kt          # 主 Activity
│   │   ├── market/                  # 每日推荐
│   │   ├── portfolio/               # 我的持仓
│   │   ├── list/                    # 行情列表
│   │   ├── detail/                  # 股票详情
│   │   └── theme/                   # 主题样式
│   └── util/                         # 工具类
└── README.md
```

## 开源协议

MIT License

## 免责声明

本应用仅供学习和参考，不构成投资建议。股市有风险，投资需谨慎。

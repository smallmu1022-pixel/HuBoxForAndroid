# HuBox for Android 更新日志 (Changelog)

# HuBox for Android v1.0.7

### 【界面与主题】
1. 统一首页、节点卡片、列表和设置页的间距与触控区域，优化卡片圆角和信息层级。
2. 保留蓝色默认主题、深色模式、跟随系统及现有自定义主题。

### 【智能分流】
1. 用户自定义路由和 DNS 规则优先于智能分流兜底规则。
2. 扩展 Google Play 域名覆盖；补齐多地区 GeoSite/GeoIP 规则验证。
3. 避免仅凭国家代码包名前缀误判应用来源，并尊重用户对浏览器的显式分应用代理选择。

### 【省电与稳定性】
1. 修复核心尚未初始化时流量统计循环可能忙等的问题。
2. 修正屏幕唤醒信号竞态，保留现有 VPN 保活行为以避免后台连接回归。

### 【备份恢复与安全】
1. WebDAV 默认恢复目录与备份目录统一为 OwnBox，并保留旧备份名称兼容。
2. 对备份读取和 ZIP 解压增加大小限制及路径校验；导入前先完整解码，并在数据库事务中替换数据。
3. WebView 禁止文件/内容访问，仅向精确匹配的受信任仪表盘域名注入 Clash API 凭据。

### 【构建验证】
1. 增加 v1.0.7 原生库、单元测试、Lint、Debug/Release APK 验证流程。

# HuBox for Android v1.0.6

### 【说明】
1. 备份导出、分享及 WebDAV 备份文件统一使用 HuBox 名称。
2. 保留旧版 OwnBox 备份文件的恢复兼容性。

# HuBox for Android v1.0.5

### 【说明】
1. BUG修复

# HuBox for Android v1.0.4

### 【说明】
1. BUG修复

# HuBox for Android v1.0.3

### 【说明】
1. BUG修复

# HuBox for Android v1.0.2

### 【说明】
1. BUG修复

# HuBox for Android v1.0.1

### 【说明】
1. BUG修复

# HuBox for Android v1.0.0

### 【说明】
1. HuBox 全新安装：包名 com.HuBox.app。
2. 极简线条风界面，桌面图标为猫猫头像。
3. 去除教程文档与官方频道；更新检查改指 HuBox 仓库。


# HuBox for Android 更新日志 (Changelog)

# HuBox for Android v1.0.7

### 【界面与主题】
1. 优化首页、节点卡片、列表及设置页的间距、圆角和信息层级。
2. 默认主题调整为 HuBox 蓝色，并完善浅色、深色及系统栏对比度。

### 【WebView 与凭据安全】
1. 禁止 HTTPS 仪表盘加载不安全混合内容。
2. 不再向第三方 HTTPS 仪表盘自动注入本地 Clash API 密钥，也不再把密钥拼入仪表盘 URL。
3. WebDAV 仅允许 HTTPS，并禁用 HTTP 重定向，避免 Basic Auth 凭据降级传输。

### 【备份与后台优化】
1. Android 云备份和设备迁移不再包含保存密码、API 密钥等敏感信息的配置数据库。
2. 调整电池优化模式下的后台流量统计轮询间隔；前台交互不受该后台间隔影响。

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

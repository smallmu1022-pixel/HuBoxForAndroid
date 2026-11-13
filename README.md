# HuBox for Android

<p align="center">
  <strong>简洁、轻量的 Android 代理客户端</strong><br>
  基于 sing-box 内核，采用清晰直观的卡片式界面与主题配色。
</p>

<p align="center">
  <a href="https://github.com/smallmu1022-pixel/HuBoxForAndroid/releases"><img src="https://img.shields.io/github/v/release/smallmu1022-pixel/HuBoxForAndroid?display_name=tag&label=最新版本" alt="Latest Release"></a>
  <a href="https://github.com/smallmu1022-pixel/HuBoxForAndroid/actions"><img src="https://img.shields.io/github/actions/workflow/status/smallmu1022-pixel/HuBoxForAndroid/validate-v1.0.7.yml?label=CI" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="GPL-3.0 License"></a>
</p>

**HuBox** 是一款基于 [sing-box](https://github.com/SagerNet/sing-box) 内核的 Android 代理客户端，注重简洁的操作体验、清晰的信息呈现与灵活的代理配置。

## 功能特点

- **简洁界面**：线条风格设计，卡片化布局，支持浅色、深色及 HuBox 蓝色主题。
- **代理管理**：管理节点与订阅，支持常用代理模式切换。
- **规则与分流**：围绕 sing-box 内核提供规则配置和流量分流能力。
- **连接与流量信息**：在应用内查看连接状态及流量统计。
- **电池优化选项**：提供性能、均衡和极限模式，调整部分后台统计轮询频率；实际耗电表现会因设备、系统和使用场景而异。
- **WebView 安全**：HTTPS 仪表盘禁止加载不安全的混合内容；不会自动向第三方仪表盘发送本地 API 密钥。
- **WebDAV 备份**：仅允许 HTTPS 连接，避免 Basic Auth 凭据通过明文 HTTP 传输。

> 支持的协议、配置格式和具体行为以当前版本内置的 sing-box 内核及应用实现为准。

## 下载与安装

前往 **[GitHub Releases](https://github.com/smallmu1022-pixel/HuBoxForAndroid/releases)** 下载正式版 APK。

1. 下载与设备架构相匹配的 APK（如发布页面提供多个变体）。
2. 按 Android 系统提示允许本次安装来源安装应用。
3. 安装后导入自己的节点或订阅，并根据需要配置代理模式。

请仅从本仓库的 Releases 页面获取官方构建。安装前请确认 APK 来源与版本。

## 安全与备份说明

- Android 系统云备份和设备迁移不会包含保存凭据与 API 密钥的配置数据库。
- 手动导出、分享或 WebDAV 备份若包含“设置”数据，仍可能包含订阅信息、WebDAV 凭据和 Clash API 密钥。请将备份文件视为敏感数据，仅保存或传输到可信位置。
- WebDAV 地址必须使用 HTTPS。第三方仪表盘不会再自动获得本地 API 密钥；如需手动配置，请先确认你信任该仪表盘的来源。

## 版本与更新

- 当前开发版本：**v1.0.7**（以 Releases 页面实际发布状态为准）。
- [查看更新日志](CHANGELOG.md)
- [查看正式版发布记录](https://github.com/smallmu1022-pixel/HuBoxForAndroid/releases)

## 问题反馈与安全报告

- 使用问题与功能建议：请通过本仓库的 [Issues](https://github.com/smallmu1022-pixel/HuBoxForAndroid/issues) 提交，并尽量附上应用版本、Android 版本、复现步骤和相关日志。
- 安全问题：请优先阅读 [SECURITY.md](SECURITY.md) 中的报告指引；不要在公开 Issue 中发布密码、订阅链接、访问令牌或其他敏感信息。

## 开源协议

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，详见 [LICENSE](LICENSE)。

---

<p align="center">
  HuBox for Android · 基于 sing-box
</p>

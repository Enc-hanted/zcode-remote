# Security Policy

## Supported versions

本项目处于早期开发阶段，仅维护最新版本的修复。

| Version | Supported |
|---------|-----------|
| 0.0.x   | ✅ 最新   |
| < 0.0.x | ❌        |

## 安全模型

ZCodeRemote 的安全设计围绕一个核心事实：**远控链接本身就是凭证（bearer-link）**——谁拿到链接，谁就能控制你桌面端的 ZCode。因此本项目在多个层面贯彻「凭证不离设备、数据不外传」：

### 1. 无内置凭据，无后端

- 应用 APK 不包含任何远控链接、token 或密钥
- 应用不连接任何自建后端；除加载 `zcode.z.ai` 页面本身外，不向第三方服务发请求

### 2. 数据不外传

- 远控链接只存储在设备本地的 `SharedPreferences`（`RecentStore` / `SettingsStore`）
- 不上报崩溃日志、不收集设备指纹、不做任何形式的数据回传
- 唯一的网络请求：页面加载（`zcode.z.ai`）与二维码扫描（相机，本地处理）

### 3. 域名隔离

- `z.ai` 域内链接在 WebView 内打开
- 其余域名一律跳转系统浏览器
- 非 `http(s)` scheme 的 URL 被拦截

### 4. SSL 严格策略

- SSL/TLS 证书错误一律调用 `handler.cancel()` 拒绝访问
- 不接受任何自签名证书，不做证书降级

### 5. 链接操作确认

- 非远控链接会先弹窗确认，避免误操作把凭证带到非预期页面

## 关于 keystore

- 本项目 debug 构建使用**公开默认参数**的 debug keystore（密码 `android`），仅用于侧载调试
- 该 keystore 由 GitHub Actions 在 CI 上用固定参数生成，保证签名一致、可覆盖安装
- **请勿将此 keystore 用于正式分发**；如需自行发布，请生成并使用你自己的签名

## 报告安全漏洞

如果你发现安全问题，**请不要公开提 Issue**。请通过以下方式私密报告：

- GitHub 的 [私密安全公告](https://github.com/Enc-hanted/zcode-remote/security/advisories/new)（Security → Report a vulnerability）
- 或直接在本仓库开一个脱敏后的 Issue（不包含真实链接、token、会话内容）

收到报告后会在合理时间内回复并评估修复。

## 免责声明

- 本项目**非智谱官方产品**，与 Z.ai / ZCode 官方无关
- ZCode 品牌与 logo 版权归智谱所有
- 使用本项目产生的任何风险（包括但不限于远控链接泄露、设备安全）由使用者自行承担

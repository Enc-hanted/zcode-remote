# ZCodeRemote

[English](README_EN.md) | 简体中文

![Version](https://img.shields.io/badge/version-0.0.2-00E676?style=flat-square)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-lightgrey?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-00E676?style=flat-square)

> 一句话：把智谱 [ZCode](https://zcode.z.ai) 桌面端的「手机远控」网页，变成一个**顺手的移动 App**。

扫描桌面端 ZCode 的远控二维码，就能在手机上继续控制你的 AI 编程智能体——但它本来只是个网页，直接用浏览器打开体验并不好。ZCodeRemote 用一整套注入式增强层，把它改造成真正像 App 的样子。

> ⚠️ 本应用**非智谱官方产品**，与 Z.ai / ZCode 官方无关，仅供个人使用与学习。ZCode 及 ZCode logo 为智谱旗下品牌，logo 素材取自 zcode.z.ai 官网。

---

## 为什么需要它

直接用手机浏览器打开 ZCode 远控链接时：

- 😫 输入框被键盘挤变形，长消息写到一半就滚出视野
- 📴 一切到别的 App，连接就断了，回来要重连
- 🔇 收到新回复完全没提示，得反复切回去看
- 🧭 想跳回某个老问题只能死命往上滑
- 🤚 网页归网页，没有手势、没有悬浮控件

ZCodeRemote 解决这些问题：**悬浮输入框**（键盘挡不住）、**后台保活**（切出不断连）、**回复通知**（系统级提醒）、**对话导航**（问题间快速跳转）、**手势控制**（悬浮 logo 一指操作）。

---

## 核心亮点

这不是一个简单的「WebView 套壳」。它包含一套约 **2000 行的注入式增强层**，在页面加载后用 `evaluateJavascript` 注入，并递归广播到所有同源 iframe 与 Shadow DOM，把网页改造成原生级体验：

- **🧩 注入式增强层**——8 个模块化 JS bundle（`dev/bundle/` 下 core / style / fix / theme / nav / composer / settings / boot），覆盖移动端布局修正、毛玻璃设计 token、浮动输入、问题导航、回复监听、诊断体系
- **🌉 原生桥 `zcodeBridge`**——JS 侧通过 `window.zcodeBridge` 直接回调原生：新回复推送、保活通知预览、主题取色、悬浮 logo 位置持久化、诊断埋点
- **_alive 保活机制**——对页面「谎报」可见性（`document.hidden` 恒为 false），让聊天页在切出 App 后不断开 WebSocket，配合前台服务 + 系统通知，回复照常到达
- **🎨 主题跟随**——就近取色页眉/页脚，系统栏颜色实时跟随页面深浅色，全程沉浸式
- **🛡️ 安全贯彻**——bearer-link 凭证模型、数据不外传、SSL 错误一律拒绝（详见 [安全](#安全) 与 [SECURITY.md](SECURITY.md)）
- **🔁 回复去重**——FNV-1a 64 位指纹 + 7 天窗口，防止重连后历史消息被当成新回复重复通知

---

## 功能

### 入口

- **扫码进入**：扫描桌面端 ZCode 左下角「手机图标」弹出的远控二维码
- **粘贴链接**：手动粘贴 `https://zcode.z.ai/remote/...` 链接；启动时剪贴板中若正好是远控链接，会自动预填
- **最近会话**：记录最近 10 条远控链接，点击进入，长按删除
- **自动恢复**：冷启动自动进入上次会话，按返回键回到入口页切换会话

### 使用体验

- **悬浮输入框**：底部输入框默认收起、不占位，通过悬浮按钮一键展开为全屏输入；右上角圆弧按钮收起
- **对话问题导航**：下拉唤出问题列表，拖动悬停可预览，点击条目直接跳转
- **回复通知**：切出应用后收到新回复，通过系统通知提醒（锁屏可见，带预览）
- **后台不断连**：切出应用后保持连接，回复照常到达
- **征询弹窗**：页面中的询问 / 确认 / 方案征询弹窗以底部卡片形式显示，选择后自动关闭
- **深色主题**：界面整体采用黑灰配色，与系统深色模式一致

---

## 手势

| 手势 | 功能 |
|---|---|
| 单击 logo | 唤起 / 收起悬浮输入框 |
| 双击 logo | 打开设置 |
| 长按拖动 logo | 调整位置（自动记忆） |
| 下拉 logo | 唤出 / 收起问题导航 |
| 上滑 logo | 跳到底部 |
| 左缘上滑 ×3 + 右缘下滑 ×3 | 显示诊断卡（复制诊断信息） |

---

## 下载安装

### 方式一：GitHub Releases（推荐）

前往 [Releases 页面](https://github.com/Enc-hanted/zcode-remote/releases)，下载最新的 `ZCodeRemote-vX.X.X.apk`，在手机上安装。

> 安装时系统会提示「未知来源应用」——这是 Android 对非 Play 商店应用的常规提示，允许安装即可。APK 由 GitHub Actions 在 CI 上构建，签名使用公开默认参数的 debug keystore，可直接覆盖升级。

### 方式二：自行构建

```bash
git clone https://github.com/Enc-hanted/zcode-remote.git
cd zcode-remote
# 重新拼接注入包并校验（CI 同款流程）
python3 _patch_web2.py
python3 _validate.py
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 和 Gradle 8.9。详细开发流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 技术架构

### 技术栈

Kotlin + AppCompat + Material Components + WebView，扫码基于 [zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded)。仅 5 个运行时依赖，极简。

- `minSdk 26`（Android 8.0+）· `targetSdk 35` · `versionName 0.0.2`

### 项目结构

| 层 | 说明 |
|---|---|
| **App 层** | `EntryActivity`（扫码 / 粘贴 / 最近会话）、`WebActivity`（WebView + 注入增强，核心）、`SettingsActivity`、`KeepAliveService`（前台保活）、`RecentStore` / `SettingsStore`（SharedPreferences + JSON） |
| **注入层** | `dev/bundle/` 下 8 个模块化 JS（01_core ~ 08_boot），由 `_split_bundle.py` 拆分维护、`_patch_web2.py` 拼接成 `_bundle.js`，内嵌进 `WebActivity.kt` 的 `BUNDLE_JS` 常量 |
| **原生桥** | `webView.addJavascriptInterface(AppBridge, "zcodeBridge")`，JS 侧通过 `window.zcodeBridge` 调用原生：`onReply` / `onProgress` / `onTheme` / `onBars` / `openSettings` / `saveLogoPos` / `getLogoPos` / `getTrace` |

### 关键机制

- **注入时机**：`onPageFinished` 触发注入，递归广播到同源 iframe 与 Shadow DOM
- **保活**：拦截 `visibilitychange` 事件使页面「以为」始终可见，WebSocket 不中断；前台服务维持进程存活，到点自动停止
- **回复去重**：`ReplyDedupe.kt`，FNV-1a 64 位指纹 + 7 天时间窗口
- **诊断体系**：注入层埋点 + 原生侧 `getTrace` 收集，可调出诊断卡复制链路信息

---

## 安全

这是本项目的设计核心——远控链接本身即是**凭证（bearer-link）**，获得链接即获得你桌面端 ZCode 的操作权限。因此：

- 🔒 **不内置任何链接**：应用本身不含任何凭据，不连接任何后端
- 🔒 **不上传任何数据**：链接只保存在你的手机本地（SharedPreferences）
- 🔒 **域名隔离**：`z.ai` 域内链接在 WebView 内打开，其余一律跳系统浏览器；非 http(s) scheme 拦截
- 🔒 **SSL 严格**：证书错误一律 `handler.cancel()` 拒绝访问，不接受任何自签证书
- 🔒 **链接确认**：非远控链接会先弹窗确认，避免误操作

**请勿将本应用及其中保存的链接分享给他人**——等同于分享你的桌面端操作权限。更多细节见 [SECURITY.md](SECURITY.md)。

---

## FAQ

**Q：这会和 ZCode 官方 App 冲突吗？**
A：本项目是独立的非官方客户端，与官方无关。它只是包装官方远控网页，不修改、不替代任何官方产品。

**Q：我的远控链接会被收集吗？**
A：不会。应用不内置任何后端，链接只存在你手机本地。网络请求只发往 `zcode.z.ai`（页面本身需要）和扫码所需的相机调用。

**Q：为什么是 debug 构建？**
A：本项目使用公开默认参数的 debug keystore 签名，仅用于侧载调试与学习。请勿用于正式分发场景。

---

## 截图

<!-- 截图待补：计划加入入口页、悬浮输入框、对话导航、深色主题等真机截图。
     注意：截图需脱敏处理（去除真实会话内容与远控链接）。 -->

---

## 路线图

- [ ] 真机截图 / 演示 GIF（脱敏后）
- [ ] 回复通知的交互优化（分组、免打扰时段）
- [ ] 多会话并行切换
- [ ] 国际化（i18n）

---

## 开源说明

- **许可证**：MIT（见 [LICENSE](LICENSE)）
- **debug 构建**使用公开默认密码（`android`）的 keystore，仅用于侧载调试，请勿用于发布
- **品牌归属**：ZCode 品牌与 logo 版权归智谱所有；本仓库不含智谱未公开的接口或凭据
- **贡献**：欢迎提 Issue 和 PR，详见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [行为准则](#)
- **变更记录**：见 [CHANGELOG.md](CHANGELOG.md)
- **安全问题**：请勿公开提 Issue，见 [SECURITY.md](SECURITY.md)

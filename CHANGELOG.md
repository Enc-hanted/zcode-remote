# Changelog

本项目的所有重要变更记录在此。版本号遵循语义化版本（受早期开发阶段约束，主版本固定为 0）。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [0.0.3] — 2026-10-08

- **安全加固**：诊断卡脱敏正则从 `sid|hash` 扩展为 `sid|hash|remote|token|ticket|mid`——新版页面存在 `?remote=<id>`（WebSocket 凭证）与 `/ws/remote-control/window/<token>` 凭证形态，`mid` 为设备稳定标识，粘贴诊断前全部打码（bundleVer 37）
- **修复悬浮 logo 消失**：① 拖动结束改用当下视口测量并把落位/保存值钳进视口（此前用 logo 创建时的旧视口尺寸，键盘开合/旋屏后可存出视口外坐标，logo 永久消失）；② 恢复时越界存档直接弃用回默认位；③ 1s 轮询自愈：logo 被页面重挂摘掉自动重建、跑出视口自动拉回
- **修复 logo 调暗不恢复**：亮度恢复收进 `hideNav()` 统一处理——此前点遮罩关闭导航、设置里关导航都会把 logo 留在 0.15 透明度（暗背景上近似消失）
- **最近会话按设备合并**：桌面端每次配对轮换链接（sid/hash/t 变）不再堆出多张同名卡片——`mid`（机器 ID）相同即同一设备，新链接覆盖旧链接；无 `mid` 的旧链接退回按机器名合并

## [0.0.2] — 首个 Release

- 首个正式 Release 版本（versionCode 43）
- 图标重新设计：黑底 + 终端绿 Z 字母（取自官方 mark，经视觉模型验证 figure-ground）
- 新增浅色主题 token（`zcode-ui-light`，跟随页面主题）+ hover/outline 变量迁移
- 开源准备：MIT LICENSE、README 重写、官方 logo 全套图标（自适应 + 5 密度）、`.gitignore` 收敛

## [0.0.1] — 开发期（v29 – v61）

开发期的内部版本号（bundleVer / vX）不计入对外语义化版本，这里按里程碑整理关键技术演进。

### 弹窗交互（v53 – v60）

- **v60**：修复弹窗点选后关不掉（移除复活 CSS `!important` + `findPopup` 只认开着的弹窗）
- **v59**：弹窗挂起时 logo 单击不再切换输入框（避免布局抖动）
- **v56 – v58**：ask / 确认弹窗可见性根治（弹窗渲染在 `v4-composer` 兄弟节点，隐藏规则收敛）
- **v53**：修复二级弹窗（Radix portal）被注入层盖住——body 弹窗检测 + z-index 压层

### 输入框 / composer（v31 – v52）

- **v52**：expand 圆弧按钮滞后根治（挂进胶囊作 absolute 子节点，零漂移）
- **v49 – v51**：expand 图标几何迭代（Qwen demo 圆弧、同心同弧度）
- **v35 – v37**：composer 渲染时序竞态修复（幂等 setup + 重试 + stale-node 处理）
- **v31**：毛玻璃 UI + composer 劫持 + 双击进设置

### 诊断与调试（v54 – v56）

- **v56**：调试双通道——`WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`（chrome://inspect 真机 F12）+ 诊断卡 `dockHtml`
- **v54 – v55**：诊断卡新增 `popupDiag` / `popupSnap` 字段，弹窗卸载后仍可读结构

### 工程化（v39 – v51）

- **v51**：注入 JS 源码拆分为 `dev/bundle/` 8 个模块（拼接产物逐字节一致），`_bundle.js` 转为生成物
- **v41**：全量审查优化（性能 / 崩溃防护 / 存储容错 / URL 白名单 / 可见性门控 / token 化）
- **v39b**：修复 `BUNDLE_JS` 超出 JVM 常量池上限（UTF-8 字符串过大）

### 其他

- **v43**：诊断手势改为组合手势（左缘上滑 ×3 + 右缘下滑 ×3）
- **v42**：expand 按钮展开漂移修复 + em 化随界面缩放 + 注入 UI/原生深色全黑灰化（去蓝调）
- **v34**：修复顶层 BODY 调用漏掉 floatInput（composer 劫持曾失效）
- **v30**：logo 死锁修复、手势与面板 UI 打磨

---

> 更早的细节见 git 历史：`git log --oneline`。

# usb_diag —— 实机 USB 取证通道

对着**真实手机上的真实 z.ai 页面**直接取证：Chrome DevTools 协议（CDP）直连 App 里的
WebView，外加 adb 物理屏截图与像素扫描。凡是"用户看到了、但 DOM 探针说没有"的视觉
问题（闪烁/空白/位移/帧断裂），用这套 5 分钟出证据。

## 为什么需要它（两桩破案实录）

1. **底部空白带（拖了 4 个版本）**：z.ai 给时间线挂了滚动跟随的底部渐隐 CSS mask。
   `elementFromPoint`/`innerText` **天生无视 mask**，所有 DOM 探针都报"有内容"，
   截屏还常抓到不同步的帧——"看起来健康"与"用户看到空白"长期并存。最后靠
   **物理屏像素扫描**（`screen`+`scan`，304px 空白带）与注入摘除后复测（113px）钉死。
2. **流式输出期每秒上下跳**：帧采样（`frames`）抓到 scrollTop 1Hz 方波 ±40px 且两种
   状态都钉在底部（= 内容高度在振荡）；变异监听（`mut`）点名攻防双方——自家 1s 轮询
   尾清写 `display:none`（相位 ~924ms），React 流式重渲染每秒抹掉（~921ms）。

**方法论**：DOM 探针只能证伪不能证实；视觉问题必须**物理屏像素级**取证；
周期性抖动先 `frames` 看周期，再 `mut` 点名写入方。

## 前置

- 手机：开发者选项 → USB 调试；数据线连电脑（PC 上首次会弹授权框，允许）。
- App：装 **debug** APK（`WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`，
  CI 产物 `ZCodeRemote-debug-apk` 即是），并打开到会话页。
- PC：Node ≥ 22（用原生 fetch/WebSocket）；adb（任一）：
  - 已装 Android SDK → 自动发现；
  - 未装 → 从 https://dl.google.com/android/repository/platform-tools-latest-windows.zip
    下载解压（绿色免安装），把 adb.exe 路径设进 `ADB_PATH`，或解压到
    `%LOCALAPPDATA%\Temp\platform-tools\`（默认探测位）。
- `scan`/`blank` 的像素扫描走 PowerShell（Windows 自带）。

## 快速上手（仓库根目录执行）

```bash
node tools/usb_diag/usb_diag.mjs status   # 一条龙：设备→pid→socket→端口转发→页面目标
node tools/usb_diag/usb_diag.mjs blank    # 物理屏截图+像素扫描，直接读底部空白带
node tools/usb_diag/usb_diag.mjs mask     # z.ai 底部渐隐 mask 状态与同步差
```

## 子命令速查

| 命令 | 作用 | 备注 |
|---|---|---|
| `status` | 设备/pid/socket/转发/页面目标 | 通道自检第一步 |
| `eval <expr 或 @file.js>` | 真实页面执行 JS | 输出自动脱敏（sid/hash/token 等） |
| `webshot <out.png>` | WebView 合成层截图 | **可能与物理屏不同帧，别单信** |
| `screen <out.png>` | 物理屏截图（adb screencap） | 视觉真相 |
| `scan <img.png>` | 像素扫描量化底部空白带 | 避开悬浮图标/圆钮的竖条测量 |
| `blank` | screen+scan 组合 | 日常最常用 |
| `frames [ms]` | 帧采样 scrollTop/sh 振荡检测 | 报标准差+主周期+变化序列 |
| `mut [ms]` | 变异监听（style/class/增删） | 按 t%1000 相位聚合点名写入方 |
| `mask` | 底部渐隐 mask 状态 | syncDelta≠0 = 脱同步 |
| `log [ms]` | 收集 console 报错/异常 | 排查红字 |
| `fling` | ⚠️ 真实触摸惯性滚动+采样+回位 | 操纵页面，复现滚动路径 bug 用 |

## 破案流程模板

**视觉空白/遮挡**：`blank` 量化 → `webshot` 对照（是否同帧）→ `eval` 查 DOM 几何
→ 三方对不上的，往"绘制层"想（mask/transform/合成层）。

**周期性抖动**：`frames` 看周期（1s → 查自家轮询；随滚动 → 查 z.ai 虚拟列表）→
`mut` 点名谁在写 style/class → 相位在整秒附近 = 1s 轮询参与的攻防战。

**滚动路径 bug**：`fling`（自动回位）复现 → `frames` 输出看停稳后是否塌缩
（scrollHeight==clientHeight = 整列表被卸载）。

## 安全注意

- `eval`/`log` 输出含 URL 凭据（sid/hash/remote/token/ticket/mid）时已自动打码，
  **贴出来之前再扫一眼**；`http://127.0.0.1:9222/json` 的原始输出不要直接外发。
- `fling` 会真实操纵手机上的页面（结束自动恢复滚动位置），演示前先打招呼。

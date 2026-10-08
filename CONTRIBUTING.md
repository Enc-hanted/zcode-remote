# Contributing to ZCodeRemote

感谢你考虑为 ZCodeRemote 贡献代码！🎉

不论是 bug 报告、功能建议、文档改进还是代码 PR，都非常欢迎。

## 前置了解

ZCodeRemote 是一个**混合应用**：Kotlin 原生壳 + 约 2000 行的注入式 JS 增强层。绝大部分「让网页像 App」的改动发生在 JS 注入层，而不是原生代码。理解这一点很重要：

- **改网页行为 / UI / 交互** → 改 `dev/bundle/` 下的 JS 模块
- **改原生能力 / 权限 / 服务** → 改 `app/src/main/java/com/zcode/remote/` 下的 Kotlin

## 开发环境

- **JDK 17**
- **Gradle 8.9**（项目自带 wrapper 可用 `./gradlew`，或全局安装）
- **Android SDK**（compileSdk 35）
- **Python 3**（用于拼接注入包与校验，CI 同款流程）
- 推荐用 Android Studio 或 IntelliJ IDEA

## 项目结构速览

```
app/src/main/java/com/zcode/remote/   # 原生 Kotlin
  ├── EntryActivity.kt          # 入口页：扫码/粘贴/最近会话
  ├── WebActivity.kt            # 核心：WebView + 注入增强 + 原生桥
  ├── KeepAliveService.kt       # 前台保活
  ├── SettingsActivity.kt       # 设置页
  ├── RecentStore / SettingsStore # 本地存储
  └── ...
dev/bundle/                            # 注入 JS 源码（改这里！）
  ├── 01_core.js ~ 08_boot.js   # 8 个模块
_split_bundle.py / _patch_web2.py     # 切分 / 拼接工具
_validate.py                           # CI 校验脚本（语法 + 资源引用）
_bundle.js                             # 拼接产物（gitignored，勿手改）
```

## 改注入 JS 的流程

注入层是本项目的核心，改动有专门的流程：

1. **修改模块文件**：编辑 `dev/bundle/0X_xxx.js`（**不要**改 `_bundle.js`，它是生成物）
2. **重新拼接**：`python3 _patch_web2.py` —— 把模块拼成 `_bundle.js` 并内嵌进 `WebActivity.kt`
3. **校验**：`python3 _validate.py` —— 检查 XML / Kotlin / JS 语法、资源引用、内嵌代码一致性
4. **构建**：`gradle assembleDebug`
5. **真机测试**：安装到手机，扫码进入真实会话验证

> 这也是 GitHub Actions CI 的流程（见 `.github/workflows/build.yml`）。提交前本地跑一遍这两个脚本，能省掉 CI 失败。

## 提交规范

没有硬性约束，但建议：

- **commit message** 用清晰的中文或英文，说明改了什么、为什么
- 参考现有风格：`vXX: 改动描述——原因 / 细节`
- 一个 PR 聚焦一件事；大改动拆成多个 PR

## PR 流程

1. Fork 仓库，新建分支（`feature/xxx` 或 `fix/xxx`）
2. 改动、本地测试
3. 跑 `python3 _patch_web2.py && python3 _validate.py` 确认通过
4. 提交 PR，描述改动内容与测试情况（PR 模板会引导你填写）
5. 等待 review

## 报 bug / 提建议

- 先搜一下现有 Issue，避免重复
- 用 Issue 模板（bug 用 bug_report，功能用 feature_request）
- bug 报告请包含：机型、Android 版本、ZCodeRemote 版本、复现步骤、预期 vs 实际
- **不要在 Issue 里贴真实远控链接或会话截图**——那是你的凭证

## 行为准则

保持友善、尊重、建设性。我们是围绕一个非官方开源项目协作的社区。

---

再次感谢你的贡献！

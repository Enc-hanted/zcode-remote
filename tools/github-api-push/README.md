# github-api-push

GitHub REST API 推送工具——绕开不可达的 git 协议（github.com 的 git endpoint 被墙/不通时，api.github.com 仍然可用），用官方 Git Data API 完成推送、清理、打 tag。

单文件、无依赖（仅 Python 3 标准库），复制到任何项目即可用。

## 子命令

```bash
python ghpush.py root "提交说明" --repo owner/name    # 单根重建：远端历史压成 1 条（force 覆盖）
python ghpush.py api  "提交说明" --repo owner/name    # 普通追加：在远端当前提交上叠一条
python ghpush.py clean [keep]  --repo owner/name      # 清理 Actions runs（默认保留 3 条）
python ghpush.py tag <v1.0.0>  --repo owner/name      # 在 main 上打 lightweight tag
```

## 凭据

优先读环境变量 `GH_TOKEN`；未设置时自动执行 `git credential fill`，从本机 credential helper（Windows 的 Git Credential Manager 等）里取 GitHub token。token 需要 `repo` 权限。

## 推送规则

- `root` / `api` 推送的是**本地 HEAD 树里列出的文件**（内容读自工作区，先 `git commit` 再推）。
- `PUSH_EXCLUDE` 环境变量（逗号分隔路径）：保留在本地但不上远端；若远端已有则删除——适合内部文档（如 `HANDOFF.md,REVIEW.md`）。
- 远端有而本地没有的文件默认删除；但被本地 `.gitignore` 命中会保留（例如入库的 keystore，删了会破坏签名一致性）。
- 单根策略（`root`）用 force 覆盖 `main`，远端只剩 1 条提交；介意丢历史就用 `api`。

## 示例

```bash
# 单根推送（本项目 zcode-remote 的标准用法）
PUSH_EXCLUDE="HANDOFF.md,REVIEW.md,需求文档.md" \
  python ghpush.py root "0.0.2: ..." --repo Enc-hanted/zcode-remote

# 打 tag 发 release（配合下面的 workflow 模板）
python ghpush.py tag v0.0.2 --repo Enc-hanted/zcode-remote
```

## 发 APK release 的 workflow 模板

把下面内容存为 `.github/workflows/release.yml`（tag `v*` 推送或手动触发时自动构建并挂到 Releases 页；debug 构建用固定 keystore 签名，可覆盖安装）：

```yaml
on:
  workflow_dispatch:
    inputs: { tag: { description: 'Release 标签（如 v0.1.0）', required: true, type: string } }
  push: { tags: ['v*'] }
jobs:
  release:
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: '8.9' }
      - run: gradle --no-daemon assembleDebug
      - name: Create Release
        env:
          GH_TOKEN: ${{ github.token }}
          TAG: ${{ github.event.inputs.tag || github.ref_name }}
        run: |
          cd app/build/outputs/apk/debug
          cp app-debug.apk ZCodeRemote-$TAG.apk
          gh release create "$TAG" ZCodeRemote-$TAG.apk \
            --repo "$GITHUB_REPOSITORY" --title "ZCodeRemote $TAG" --notes "debug 构建（固定签名，可覆盖安装）。" \
          || gh release upload "$TAG" ZCodeRemote-$TAG.apk --repo "$GITHUB_REPOSITORY" --clobber
```

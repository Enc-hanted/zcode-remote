#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub API 推送工具 —— 绕开不可达的 git 协议，走 REST API。

用法:
  python ghpush.py root "提交说明" --repo owner/name   # 单根重建：远端历史压成 1 条（force）
  python ghpush.py api  "提交说明" --repo owner/name   # 普通追加：在远端当前提交上叠一条
  python ghpush.py clean [keep]  --repo owner/name     # 清理 Actions runs（默认保留 3）
  python ghpush.py tag <v1.0.0>  --repo owner/name     # 在 main 上打 lightweight tag

凭据:
  优先读环境变量 GH_TOKEN；未设置时自动从 git credential 取
  （Windows Git Credential Manager 等 credential.helper 里存的 GitHub token）。

推送内容:
  root/api 推送本地 HEAD 树中列出的文件（文件内容读自工作区，先 commit 再推）。
  - PUSH_EXCLUDE 环境变量：逗号分隔的路径，保留在本地但不推送、
    若远端已有则删除（例如内部文档 HANDOFF.md,REVIEW.md）。
  - 远端有而本地没有的文件默认删除；但被本地 .gitignore 命中
    （例如入库的 keystore）会保留，避免误删签名文件。

示例:
  PUSH_EXCLUDE="HANDOFF.md,REVIEW.md" GH_TOKEN=$TOKEN \
    python ghpush.py root "0.1.0: ..." --repo owner/myrepo
"""
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def repo_from_args(argv):
    if "--repo" in argv:
        i = argv.index("--repo")
        return argv[i + 1]
    r = os.environ.get("GITHUB_REPO", "").strip()
    if not r:
        sys.exit("缺少仓库: 用 --repo owner/name 或环境变量 GITHUB_REPO")
    return r


def get_token():
    t = os.environ.get("GH_TOKEN", "").strip()
    if t:
        return t
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[9:]
    sys.exit("没有 GH_TOKEN，git credential 里也没有 GitHub token")


def req(token, method, url, data=None):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", "Bearer " + token)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "ghpush")
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, body, timeout=60) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def git(*args):
    return subprocess.check_output(["git"] + list(args), text=True).strip()


def head_sha(token, repo):
    ref = req(token, "GET", API + "/repos/%s/git/ref/heads/main" % repo)
    return ref["object"]["sha"]


def push(token, repo, msg, fresh_root):
    head = head_sha(token, repo)
    commit = req(token, "GET", API + "/repos/%s/git/commits/%s" % (repo, head))
    base_tree = commit["tree"]["sha"]
    remote_tree = req(token, "GET", API + "/repos/%s/git/trees/%s?recursive=1" % (repo, base_tree))
    remote_set = {t["path"] for t in remote_tree["tree"] if t["type"] == "blob"}

    local_set = set(git("-c", "core.quotepath=false", "ls-tree", "-r", "HEAD", "--name-only").splitlines())
    exclude = {p.strip() for p in os.environ.get("PUSH_EXCLUDE", "").split(",") if p.strip()}

    push_set = sorted(local_set - exclude)
    deletes = sorted(remote_set - local_set)
    for p in sorted(exclude):
        if p in remote_set:
            deletes.append(p)
    # 本地被 gitignore 命中（远端独有，如入库的 keystore）：保留不删
    kept, deletes = [], []
    for p in sorted(set(deletes)):
        try:
            git("check-ignore", "-q", "--", p)
            kept.append(p)
        except subprocess.CalledProcessError:
            deletes.append(p)

    print("推送 %d 个文件，删除 %d 个，保留(gitignore) %d 个" % (len(push_set), len(deletes), len(kept)))
    entries = []
    for path in push_set:
        blob = req(token, "POST", API + "/repos/%s/git/blobs" % repo, {
            "content": base64.b64encode(open(path, "rb").read()).decode(),
            "encoding": "base64",
        })
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    for path in deletes:
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    tree = req(token, "POST", API + "/repos/%s/git/trees" % repo, {"base_tree": base_tree, "tree": entries})
    new_commit = req(token, "POST", API + "/repos/%s/git/commits" % repo, {
        "message": msg, "tree": tree["sha"],
        "parents": [] if fresh_root else [head],
    })
    req(token, "PATCH", API + "/repos/%s/git/refs/heads/main" % repo,
        {"sha": new_commit["sha"], "force": fresh_root})
    print("pushed:", new_commit["sha"], "(单根)" if fresh_root else "(追加)")


def clean(token, repo, keep):
    runs = req(token, "GET", API + "/repos/%s/actions/runs?per_page=100" % repo)["workflow_runs"]
    runs.sort(key=lambda r: r["created_at"], reverse=True)
    old = runs[keep:]
    for r in old:
        req(token, "DELETE", API + "/repos/%s/actions/runs/%s" % (repo, r["id"]))
        print("deleted run:", r["id"], r["created_at"], r["head_sha"][:7])
    print("total=%d keep=%d delete=%d" % (len(runs), keep, len(old)))


def tag(token, repo, name):
    sha = head_sha(token, repo)
    try:
        req(token, "POST", API + "/repos/%s/git/refs" % repo,
            {"ref": "refs/tags/%s" % name, "sha": sha})
        print("tag %s -> %s" % (name, sha[:7]))
    except urllib.error.HTTPError as e:
        if e.code == 422:   # 已存在 → force 移动
            req(token, "PATCH", API + "/repos/%s/git/refs/tags/%s" % (repo, name),
                {"sha": sha, "force": True})
            print("tag %s 已存在，force 移动到 %s" % (name, sha[:7]))
        else:
            raise


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        sys.exit(1)
    cmd = argv[0]
    repo = repo_from_args(argv)
    token = get_token()
    if cmd == "root":
        push(token, repo, argv[1], fresh_root=True)
    elif cmd == "api":
        push(token, repo, argv[1], fresh_root=False)
    elif cmd == "clean":
        clean(token, repo, int(argv[1]) if len(argv) > 1 and argv[1].isdigit() else 3)
    elif cmd == "tag":
        tag(token, repo, argv[1])
    else:
        sys.exit("未知子命令: %s（可用: root / api / clean / tag）")


if __name__ == "__main__":
    main()

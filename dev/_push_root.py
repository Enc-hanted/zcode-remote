# -*- coding: utf-8 -*-
"""单根历史推送：把本地 HEAD 树重建为无父提交（fresh root），force 更新远端 main。

与 _push_api.py（普通追加提交）不同：本脚本用于"干净历史"策略——
每次发布把整个公开树压成一条提交，远端永远只有 1 条历史。
排除内部文档（HANDOFF/REVIEW/需求文档），不推 keystore 之外的未提交文件。

用法: GH_TOKEN=xxx python dev/_push_root.py "commit message"
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request

REPO = "Enc-hanted/zcode-remote"
API = "https://api.github.com/repos/" + REPO
TOKEN = os.environ["GH_TOKEN"]
MSG = sys.argv[1]
# 内部文档：只留本地，不进公开树
EXCLUDE = {"HANDOFF.md", "REVIEW.md", "需求文档.md"}
# 远端独有但必须保留的文件（签名稳定性：keystore 入库，CI 与本地同签可覆盖安装）
KEEP_REMOTE_ONLY = {"keystore/debug.keystore"}


def req(method, url, data=None):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "zcode-remote-push-root")
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, body, timeout=60) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def git(*args):
    return subprocess.check_output(["git"] + list(args), text=True).strip()


def main():
    # 远端当前树
    ref = req("GET", API + "/git/ref/heads/main")
    head_sha = ref["object"]["sha"]
    commit = req("GET", API + "/git/commits/" + head_sha)
    base_tree = commit["tree"]["sha"]
    remote_tree = req("GET", API + "/git/trees/" + base_tree + "?recursive=1")
    remote_set = {t["path"] for t in remote_tree["tree"] if t["type"] == "blob"}

    # 本地 HEAD 树（关闭 quotepath，中文文件名按原始 UTF-8 输出）
    local_set = set(git("-c", "core.quotepath=false", "ls-tree", "-r", "HEAD", "--name-only").splitlines())
    push_set = sorted(local_set - EXCLUDE)
    deletes = sorted((remote_set - local_set) - KEEP_REMOTE_ONLY)
    # 内部文档即使本地有，也要从远端移除（否则会保留在基树上）
    for p in sorted(EXCLUDE):
        if p in remote_set:
            deletes.append(p)
    deletes = sorted(set(deletes))

    print("推送文件:", len(push_set), " 远端删除:", deletes)

    entries = []
    for path in push_set:
        content = open(path, "rb").read()
        blob = req("POST", API + "/git/blobs", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    for path in deletes:
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    tree = req("POST", API + "/git/trees", {"base_tree": base_tree, "tree": entries})
    new_commit = req("POST", API + "/git/commits", {
        "message": MSG,
        "tree": tree["sha"],
        "parents": [],            # 单根：无父提交
    })
    req("PATCH", API + "/git/refs/heads/main", {"sha": new_commit["sha"], "force": True})
    print("pushed fresh root:", new_commit["sha"])


if __name__ == "__main__":
    main()

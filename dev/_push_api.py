# -*- coding: utf-8 -*-
"""Push committed files to GitHub via Git Data API (bypasses unreachable github.com git endpoint).
用法: GH_TOKEN=xxx python _push_api.py "commit msg" [path...] [-Dremote_path...]
  -D 前缀的路径表示在远端删除（tree entry sha=null）。
"""
import base64
import json
import os
import sys
import urllib.request

REPO = "Enc-hanted/zcode-remote"
API = "https://api.github.com/repos/" + REPO
TOKEN = os.environ["GH_TOKEN"]
MSG = sys.argv[1]
FILES = []
DELETES = []
for a in sys.argv[2:]:
    if a.startswith("-D"):
        DELETES.append(a[2:])
    else:
        FILES.append(a)


def req(method, url, data=None):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "zcode-remote-push")
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, body, timeout=30) as resp:
        return json.loads(resp.read())


def main():
    ref = req("GET", API + "/git/ref/heads/main")
    head_sha = ref["object"]["sha"]
    commit = req("GET", API + "/git/commits/" + head_sha)
    base_tree = commit["tree"]["sha"]

    entries = []
    for path in FILES:
        content = open(path, "rb").read()
        blob = req("POST", API + "/git/blobs", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    for path in DELETES:
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    tree = req("POST", API + "/git/trees", {"base_tree": base_tree, "tree": entries})
    new_commit = req("POST", API + "/git/commits", {
        "message": MSG,
        "tree": tree["sha"],
        "parents": [head_sha],
    })
    req("PATCH", API + "/git/refs/heads/main", {"sha": new_commit["sha"], "force": False})
    print("pushed:", new_commit["sha"])


if __name__ == "__main__":
    main()

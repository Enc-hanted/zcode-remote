# -*- coding: utf-8 -*-
"""清理 GitHub Actions 过时构建：保留最近 KEEP 个 workflow run（含 artifact），其余连同 artifact 一起删除。
用法: GH_TOKEN=xxx python dev/_clean_runs.py [keep=3]
"""
import json
import os
import sys
import urllib.request

REPO = "Enc-hanted/zcode-remote"
API = "https://api.github.com/repos/" + REPO
TOKEN = os.environ["GH_TOKEN"]
KEEP = int(sys.argv[1]) if len(sys.argv) > 1 else 3


def req(method, url, data=None):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("User-Agent", "zcode-remote-clean")
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        r.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(r, body, timeout=30) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def main():
    runs = req("GET", API + "/actions/runs?per_page=100")["workflow_runs"]
    runs.sort(key=lambda x: x["created_at"], reverse=True)
    keep, delete = runs[:KEEP], runs[KEEP:]
    print("total=%d keep=%d delete=%d" % (len(runs), len(keep), len(delete)))
    for run in delete:
        try:
            req("DELETE", API + "/actions/runs/" + str(run["id"]))
            print("deleted:", run["id"], run["created_at"], run["head_sha"][:7])
        except Exception as e:
            print("skip:", run["id"], e)


if __name__ == "__main__":
    main()

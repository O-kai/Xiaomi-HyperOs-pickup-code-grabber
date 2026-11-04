#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 docs/pr-review/*.md 里的评审结论发到对应 PR，并按结论关闭。

为什么要有这个脚本：
  5 个 PR 的结论都已经写成人话稿子存在仓库里（可追溯），
  但逐条复制粘贴到 GitHub 容易漏、也容易被改得与仓库里的版本不一致。
  脚本保证「仓库里写的」和「GitHub 上发的」是同一份内容。

token 从哪来（按优先级）：
  1) 环境变量 GITHUB_TOKEN
  2) --token-file 指定的文件（默认 ~/.workbuddy/github_token.txt）
  文件里只放一行 token，不要提交进仓库。

用法：
  python tools/github_pr_action.py --dry-run          # 只打印将要做什么
  python tools/github_pr_action.py                    # 真发
  python tools/github_pr_action.py --only 13,14       # 只处理指定 PR

需要的权限（fine-grained PAT，只给这一个仓库）：
  Pull requests: Read and write
  Issues:        Read and write      （评论走 issue comment 接口）
  Metadata:      Read
"""

import argparse
import io
import json
import os
import sys
import urllib.error
import urllib.request

REPO = "O-kai/Xiaomi-HyperOs-pickup-code-grabber"
API = "https://api.github.com"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REVIEW_DIR = os.path.join(ROOT, "docs", "pr-review")

# PR 号 -> 关闭理由
#   completed   = 内容已经进仓库（移植 / 本来就已合入）
#   not_planned = 不采用（被新架构取代）
ACTIONS = [
    (14, "completed"),
    (13, "completed"),
    (12, "completed"),
    (9, "completed"),
    (8, "not_planned"),
]


def load_token(token_file):
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok, "env GITHUB_TOKEN"
    path = os.path.expanduser(token_file)
    if os.path.exists(path):
        with io.open(path, encoding="utf-8") as f:
            tok = f.read().strip()
        if tok:
            return tok, path
    return None, None


def api(method, path, token, payload=None):
    url = API + path
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "pickup-code-grabber-pr-action")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(body) if body.strip() else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        return e.code, body


def read_comment(pr):
    """读评论稿，去掉开头的元信息（标题行与引用块），只留正文。"""
    path = os.path.join(REVIEW_DIR, "pr%d.md" % pr)
    if not os.path.exists(path):
        return None
    lines = io.open(path, encoding="utf-8").read().splitlines()
    # 跳过开头的 H1 与紧随其后的引用块（那是给人看的元信息）
    i = 0
    while i < len(lines) and (lines[i].startswith("# ") or lines[i].strip() == ""):
        i += 1
    while i < len(lines) and lines[i].startswith(">"):
        i += 1
    while i < len(lines) and (lines[i].strip() == "" or lines[i].strip() == "---"):
        i += 1
    body = "\n".join(lines[i:]).strip()
    return body or None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--token-file", default="~/.workbuddy/github_token.txt")
    ap.add_argument("--only", default="")
    ap.add_argument("--no-close", action="store_true", help="只发评论，不关闭 PR")
    args = ap.parse_args()

    only = set()
    if args.only:
        only = {int(x) for x in args.only.replace(" ", "").split(",") if x}

    token, src = load_token(args.token_file)
    if not token and not args.dry_run:
        print("找不到 token。请设置 GITHUB_TOKEN，或把 token 写进 %s" % args.token_file)
        return 2

    if token:
        st, me = api("GET", "/user", token)
        if st != 200:
            print("token 无效（HTTP %s）：%s" % (st, me))
            return 3
        print("已认证为 %s（token 来源：%s）" % (me.get("login"), src))
        # 权限自检：能否读这个仓库的 PR
        st, prs = api("GET", "/repos/%s/pulls?state=open&per_page=100" % REPO, token)
        if st == 200:
            nums = [p["number"] for p in prs]
            print("当前 open PR：%s" % nums)
        else:
            print("读 PR 列表失败（HTTP %s）：%s" % (st, prs))
    print("")

    for pr, reason in ACTIONS:
        if only and pr not in only:
            continue
        body = read_comment(pr)
        print("=" * 62)
        print("#%d  关闭理由=%s  评论稿=%s" % (pr, reason, "有" if body else "缺失"))
        if not body:
            print("  跳过：docs/pr-review/pr%d.md 不存在或为空" % pr)
            continue
        print("  评论正文 %d 字，开头：" % len(body))
        print("  " + body.splitlines()[0][:70])
        if args.dry_run:
            continue

        st, res = api("POST", "/repos/%s/issues/%d/comments" % (REPO, pr), token,
                      {"body": body})
        if st not in (200, 201):
            print("  ✗ 发评论失败 HTTP %s：%s" % (st, res))
            continue
        print("  ✓ 评论已发：%s" % res.get("html_url"))

        if args.no_close:
            continue
        st, res = api("PATCH", "/repos/%s/pulls/%d" % (REPO, pr), token,
                      {"state": "closed", "state_reason": reason})
        if st == 200:
            print("  ✓ 已关闭（state_reason=%s）" % reason)
        else:
            print("  ✗ 关闭失败 HTTP %s：%s" % (st, res))

    if args.dry_run:
        print("\n（dry-run：什么都没发）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

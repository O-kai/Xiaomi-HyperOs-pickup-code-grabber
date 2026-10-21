#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
抓取「官方应用图标」到本目录（App Store 公开检索接口返回的官方美术图）。

用法：python fetch-icons.py            # 断点续传：已存在的文件会跳过
      python fetch-icons.py --force    # 全部重新下载

来源说明
--------
* 数据来自 Apple 公开接口 https://itunes.apple.com/search （无需鉴权），
  取 artworkUrl512 后把尾部尺寸段替换为 180x180bb.png 再下载（180px 足够 38dp @3x 显示）。
* 菜鸟裹裹（com.cainiao.guoguo）在 iOS 已并入「菜鸟」，没有独立官方图标，
  因此保留 icons/fallback/ 下的自绘占位图标。
* 图标是各公司的品牌资产（商标），仅建议用于原型/自用；
  随 APK 分发请改用 icons/fallback/ 里不含第三方素材的自绘版本。
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))
SIZE = "180x180bb.png"

# (输出文件名, App Store 检索词, trackName 前缀匹配)
TARGETS = [
    ("cainiao",        "菜鸟",     "菜鸟"),
    ("jd",             "京东",     "京东"),
    ("taobao",         "淘宝",     "淘宝"),
    ("pinduoduo",      "拼多多",   "拼多多"),
    ("sf",             "顺丰速运", "顺丰"),
]


def curl(url, binary=False, tries=4):
    """带重试的 curl（App Store 接口偶发 TLS 握手超时）。"""
    for i in range(tries):
        args = ["curl", "-s", "-m", "30", "-A", UA, url]
        r = subprocess.run(args, capture_output=True)
        if r.returncode == 0 and r.stdout:
            if binary:
                return r.stdout
            try:
                return json.loads(r.stdout.decode("utf-8", "ignore"))
            except Exception:
                pass
        time.sleep(1.5 * (i + 1))
    return None


def lookup(term, prefix):
    url = "https://itunes.apple.com/search?" + urllib.parse.urlencode(
        {"term": term, "country": "cn", "entity": "software", "limit": 5})
    data = curl(url)
    if not data:
        return None
    for r in data.get("results", []):
        if r.get("trackName", "").startswith(prefix):
            return r
    return None


def main():
    force = "--force" in sys.argv
    sources = {}
    ok = skipped = failed = 0
    for slug, term, prefix in TARGETS:
        dst = os.path.join(OUT_DIR, slug + ".png")
        if os.path.exists(dst) and not force:
            print("跳过（已存在）:", slug + ".png")
            skipped += 1
            continue
        r = lookup(term, prefix)
        if not r:
            print("失败（未查到）:", term)
            failed += 1
            continue
        art = r["artworkUrl512"]
        url = art.rsplit("/", 1)[0] + "/" + SIZE
        blob = curl(url, binary=True)
        if not blob or not blob.startswith(b"\x89PNG"):
            print("失败（下载/格式）:", slug, url)
            failed += 1
            continue
        with open(dst, "wb") as f:
            f.write(blob)
        sources[slug] = {
            "app": r["trackName"],
            "bundleId": r.get("bundleId"),
            "artwork": art,
            "downloaded": url,
            "bytes": len(blob),
        }
        print("下载完成: %-14s %-28s %6.1f KB" % (slug + ".png", r["trackName"], len(blob) / 1024))
        ok += 1

    sidecar = os.path.join(OUT_DIR, "official-sources.json")
    if os.path.exists(sidecar):
        old = json.load(open(sidecar, encoding="utf-8"))
        old.update(sources)
        sources = old
    json.dump(sources, open(sidecar, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("\n完成：下载 %d / 跳过 %d / 失败 %d（来源记录见 official-sources.json）" % (ok, skipped, failed))


if __name__ == "__main__":
    main()

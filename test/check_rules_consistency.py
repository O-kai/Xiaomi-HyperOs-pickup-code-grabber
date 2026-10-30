#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
规则三处副本一致性校验（v3.1.0 新增）

背景
----
官方规则集在仓库里有三份必须保持一致的副本：

  1. rules/rules.json               远端热更源（GitHub raw / jsdelivr）
  2. app/assets/rules/rules.json    APK 内置兜底
  3. app/src/.../ExtractorRules.java 编译进代码的 createDefault() 默认值
                                       —— 系统进程（system_server / telephony）无 Context，
                                          读不到前两份，只能用这份

第 3 份长期是最容易漏的一处：改了前两份却忘了改它，
系统进程就会一直跑旧规则，而短信通道是好的——用户在界面上完全看不出异常。
本脚本用于在改动规则后一次性把三处对齐情况查清楚。

用法
----
  python test/check_rules_consistency.py

退出码 0 = 三份一致；1 = 存在不一致（提交前应先修掉）。
"""

import io
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

P_REPO = os.path.join(REPO, "rules", "rules.json")
P_ASSET = os.path.join(REPO, "app", "assets", "rules", "rules.json")
P_JAVA = os.path.join(REPO, "app", "src", "io", "github", "okaidev", "pickupcode",
                      "ExtractorRules.java")

# 隐藏字符：BOM / 零宽空格 / NBSP / 全角空格
HIDDEN = {
    "BOM(EF BB BF)": "efbbbf",
    "零宽空格U+200B": "e2808b",
    "NBSP(C2 A0)": "c2a0",
    "全角空格(E3 80 80)": "e38080",
}


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def scan_hidden(data):
    """按 UTF-8 字节序列检测隐藏字符（不能用引号配对判断，遇转义会漂移出大量假警报）"""
    low = data.hex()
    hits = []
    for name, seq in HIDDEN.items():
        c = low.count(seq)
        if c:
            hits.append("%s x%d" % (name, c))
    return hits


def _java_unescape(t):
    out = []
    i = 0
    while i < len(t):
        c = t[i]
        if c == "\\" and i + 1 < len(t):
            n = t[i + 1]
            if n == "\\":
                out.append("\\")
            elif n == '"':
                out.append('"')
            elif n == "n":
                out.append("\n")
            elif n == "t":
                out.append("\t")
            else:
                out.append("\\" + n)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def parse_java_constant(src, name):
    """取 Java 里的字符串常量（支持 '+' 拼接的多段字面量）"""
    m = re.search(re.escape(name) + r"\s*=\s*", src)
    if not m:
        return None
    i = m.end()
    parts = []
    while i < len(src):
        c = src[i]
        if c == '"':
            i += 1
            buf = []
            while i < len(src):
                if src[i] == "\\":
                    buf.append(src[i])
                    buf.append(src[i + 1])
                    i += 2
                    continue
                if src[i] == '"':
                    break
                buf.append(src[i])
                i += 1
            parts.append("".join(buf))
            i += 1
        elif c == ";":
            break
        else:
            i += 1
    if not parts:
        return None
    return _java_unescape("".join(parts))


def parse_java_default(path):
    """从 ExtractorRules.java 的 createDefault() 里抠出 version / minAppVersionCode / 锚点"""
    src = io.open(path, encoding="utf-8").read()
    m = re.search(r"public static ExtractorRules createDefault\(\)\s*\{(.*?)\n    \}", src, re.S)
    if not m:
        return None
    body = m.group(1)
    out = {}
    mv = re.search(r"\.version\s*=\s*(\d+)", body)
    ma = re.search(r"\.minAppVersionCode\s*=\s*(\d+)", body)
    # 锚点在 createDefault() 里是逐个 r.keywordAnchors.add("...") 添加的，
    # 也兼容 new String[]{...} 写法（两种都出现过，按先到先得）。
    adds = re.findall(r'keywordAnchors\.add\(\s*"([^"]*)"\s*\)', body)
    if adds:
        out["anchors"] = adds
    else:
        mk = re.search(r"keywordAnchors\s*=\s*new String\[\]\s*\{(.*?)\}", body, re.S)
        out["anchors"] = re.findall(r'"([^"]*)"', mk.group(1)) if mk else None
    out["version"] = int(mv.group(1)) if mv else None
    out["minAppVersionCode"] = int(ma.group(1)) if ma else None
    # v7：码形正则同样要三份一致——只比版本号和锚点的话，
    # 改完 rules.json 忘了改 Java 副本，系统进程会一直用旧码形且没有任何提示。
    out["dashPattern"] = parse_java_constant(src, "DEFAULT_DASH_PATTERN")
    out["alnumPattern"] = parse_java_constant(src, "alnumPatternStr")
    return out


def main():
    problems = []

    print("=== 1. 文件存在性 ===")
    for p in (P_REPO, P_ASSET, P_JAVA):
        ok = os.path.isfile(p)
        print("  %-6s %s" % ("OK" if ok else "MISS", p))
        if not ok:
            problems.append("缺文件: " + p)

    if problems:
        print("")
        print("结论: 存在缺失文件，无法继续校验")
        return 1

    b_repo = read_bytes(P_REPO)
    b_asset = read_bytes(P_ASSET)

    print("")
    print("=== 2. 字节级一致性（远端源 vs APK 内置）===")
    same = b_repo == b_asset
    print("  repo   : %d bytes  sha=%s" % (len(b_repo), b_repo.hex()[:16]))
    print("  assets : %d bytes  sha=%s" % (len(b_asset), b_asset.hex()[:16]))
    print("  %s 两份字节完全一致" % ("OK  " if same else "FAIL"))
    if not same:
        problems.append("rules/rules.json 与 app/assets/rules/rules.json 字节不一致")

    print("")
    print("=== 3. 隐藏字符扫描 ===")
    for label, data in (("repo", b_repo), ("assets", b_asset)):
        hits = scan_hidden(data)
        print("  %-8s %s" % (label, "干净" if not hits else "发现 " + ", ".join(hits)))
        if hits:
            problems.append("%s 存在隐藏字符: %s" % (label, ", ".join(hits)))

    print("")
    print("=== 4. 内容一致性（三份）===")
    j_repo = json.loads(b_repo.decode("utf-8"))
    j_asset = json.loads(b_asset.decode("utf-8"))
    jd = parse_java_default(P_JAVA)

    for k in ("version", "minAppVersionCode"):
        a, b, c = j_repo.get(k), j_asset.get(k), jd.get(k)
        ok = (a == b == c)
        print("  %-20s repo=%-5s assets=%-5s java=%-5s  %s"
              % (k, a, b, c, "OK" if ok else "FAIL"))
        if not ok:
            problems.append("%s 三处不一致（repo=%s assets=%s java=%s）" % (k, a, b, c))

    a_repo = j_repo.get("keywordAnchors", [])
    a_asset = j_asset.get("keywordAnchors", [])
    a_java = jd.get("anchors")
    ok = (a_repo == a_asset == a_java)
    print("  %-20s repo=%-3s assets=%-3s java=%-3s  %s"
          % ("keywordAnchors", len(a_repo), len(a_asset),
             len(a_java) if a_java is not None else "?", "OK" if ok else "FAIL"))
    if not ok:
        problems.append("keywordAnchors 三处不一致")
        print("      repo   = %s" % a_repo)
        print("      assets = %s" % a_asset)
        print("      java   = %s" % a_java)
    elif a_repo != a_asset or a_repo != (a_java or []):
        problems.append("keywordAnchors 集合相同但顺序不同")

    for k in ("dashPattern", "alnumPattern"):
        a, b, c = j_repo.get(k), j_asset.get(k), jd.get(k)
        ok = (a == b == c)
        shown = (a or "")[:60]
        print("  %-20s repo=%-5s assets=%-5s java=%-5s  %s"
              % (k, len(a or ""), len(b or ""), len(c or ""), "OK" if ok else "FAIL"))
        if not ok:
            problems.append("%s 三处不一致" % k)
            print("      repo   = %s" % shown)
            print("      assets = %s" % (b or "")[:60])
            print("      java   = %s" % (c or "")[:60])

    print("")
    print("=== 5. 结论 ===")
    if problems:
        for p in problems:
            print("  [FAIL] " + p)
        print("")
        print("请把三处改齐后再提交；系统进程只能读 ExtractorRules.createDefault()，"
              "漏改它不会有任何界面提示。")
        return 1
    print("  三份副本全部一致 OK   version=%s  anchors=%d"
          % (j_repo.get("version"), len(a_repo)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
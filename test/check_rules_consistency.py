#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
规则单源校验（v4.0 起）

背景
----
以前官方规则集在仓库里有三份必须手工同步的副本：

  1. rules/rules.json                远端热更源
  2. app/assets/rules/rules.json     APK 内置兜底
  3. app/src/.../ExtractorRules.java 编译进代码的 createDefault() 默认值
                                      —— 系统进程（system_server / telephony）无 Context，
                                         读不到前两份，只能用这份

第 3 份长期是最容易漏的一处：改了前两份却忘了改它，
系统进程就会一直跑旧规则，而短信通道是好的——用户在界面上完全看不出异常。

v4.0 改成【单源】：
  rules/rules.json 是唯一需要人工维护的文件，
  构建任务 generateRules 从它生成
    - app/build/generated/rules/java/.../GeneratedRules.java（内置默认）
    - app/build/generated/rules/assets/rules/rules.json（APK 内置）
  于是「漏改副本」在物理上不再可能。

本脚本做两件事：
  1. 检查源文件本身是否健康（JSON 合法、无隐藏字符、关键字段齐全）
  2. 检查已生成的派生产物是否与源一致（不一致说明构建产物过期）

用法
----
  python test/check_rules_consistency.py

退出码 0 = 一致；1 = 存在问题（提交前应先修掉）。
"""

import io
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

P_REPO = os.path.join(REPO, "rules", "rules.json")
# 仓库里不应再保留手工副本（会与生成物在打包时冲突）
P_STALE_ASSET = os.path.join(REPO, "app", "assets", "rules", "rules.json")
GEN_ROOT = os.path.join(REPO, "app", "build", "generated", "rules")
P_GEN_ASSET = os.path.join(GEN_ROOT, "assets", "rules", "rules.json")
P_GEN_JAVA = os.path.join(GEN_ROOT, "java", "io", "github", "okaidev",
                          "pickupcode", "GeneratedRules.java")

# 隐藏字符：BOM / 零宽空格 / NBSP / 全角空格
HIDDEN = {
    "BOM(EF BB BF)": "efbbbf",
    "零宽空格U+200B": "e2808b",
    "NBSP(C2 A0)": "c2a0",
    "全角空格(E3 80 80)": "e38080",
}

REQUIRED_KEYS = [
    "version", "minAppVersionCode", "description",
    "featureWords", "actionWords", "keywordAnchors", "byWords",
    "byLookaheadWords", "dashPattern", "alnumPattern",
    "placePrefixes", "placeSuffixes", "negativeKeywords",
    "excludeTailWords", "excludeHeadWords", "excludeTailPhrases",
]

# 生成文件里的常量名 <-> rules.json 里的字段名
SCALARS = [
    ("VERSION", "version"),
    ("MIN_APP_VERSION_CODE", "minAppVersionCode"),
    ("DESCRIPTION", "description"),
    ("DASH_PATTERN", "dashPattern"),
    ("ALNUM_PATTERN", "alnumPattern"),
]
ARRAYS = [
    ("FEATURE_WORDS", "featureWords"),
    ("ACTION_WORDS", "actionWords"),
    ("KEYWORD_ANCHORS", "keywordAnchors"),
    ("BY_WORDS", "byWords"),
    ("BY_LOOKAHEAD_WORDS", "byLookaheadWords"),
    ("PLACE_PREFIXES", "placePrefixes"),
    ("PLACE_SUFFIXES", "placeSuffixes"),
    ("NEGATIVE_KEYWORDS", "negativeKeywords"),
    ("EXCLUDE_TAIL_WORDS", "excludeTailWords"),
    ("EXCLUDE_TAIL_PHRASES", "excludeTailPhrases"),
    ("EXCLUDE_HEAD_WORDS", "excludeHeadWords"),
    ("EXTRA_FEATURE_WORDS", "extraFeatureWords"),
]


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
            elif n == "r":
                out.append("\r")
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
    """取 Java 里的字符串常量"""
    m = re.search(re.escape(name) + r"\s*=\s*", src)
    if not m:
        return None
    i = m.end()
    while i < len(src) and src[i] in " \t\r\n":
        i += 1
    if i >= len(src) or src[i] != '"':
        return None
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
    return _java_unescape("".join(buf))


def parse_java_int(src, name):
    """取 Java 里的 int 常量"""
    m = re.search(re.escape(name) + r"\s*=\s*(-?\d+)\s*;", src)
    return int(m.group(1)) if m else None


def parse_java_array(src, name):
    """取 Java 里的 String[] 常量"""
    m = re.search(re.escape(name) + r"\s*=\s*\{", src)
    if not m:
        return None
    i = m.end()
    depth = 1
    buf = []
    while i < len(src) and depth > 0:
        c = src[i]
        if c == '"':
            j = i + 1
            chunk = []
            while j < len(src):
                if src[j] == "\\":
                    chunk.append(src[j])
                    chunk.append(src[j + 1])
                    j += 2
                    continue
                if src[j] == '"':
                    break
                chunk.append(src[j])
                j += 1
            buf.append(_java_unescape("".join(chunk)))
            i = j + 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        i += 1
    return buf


def main():
    problems = []

    print("=== 1. 源文件 ===")
    if not os.path.isfile(P_REPO):
        print("  MISS  %s" % P_REPO)
        return 1
    b_repo = read_bytes(P_REPO)
    print("  OK    %s  (%d bytes)" % (P_REPO, len(b_repo)))

    if os.path.isfile(P_STALE_ASSET):
        print("  FAIL  仓库里仍有手工副本 %s" % P_STALE_ASSET)
        print("        v4.0 起规则只有单源，该副本由构建生成，请删除")
        problems.append("存在手工副本 app/assets/rules/rules.json")
    else:
        print("  OK    仓库内无手工副本（单源）")

    print("")
    print("=== 2. 源文件健康度 ===")
    hits = scan_hidden(b_repo)
    print("  隐藏字符 : %s" % ("干净" if not hits else "发现 " + ", ".join(hits)))
    if hits:
        problems.append("rules.json 存在隐藏字符: %s" % ", ".join(hits))

    try:
        j_repo = json.loads(b_repo.decode("utf-8"))
    except Exception as e:
        print("  JSON    : 解析失败 %s" % e)
        return 1
    print("  JSON    : 合法")

    missing = [k for k in REQUIRED_KEYS if k not in j_repo]
    print("  必需字段: %s" % ("齐全" if not missing else "缺少 " + ", ".join(missing)))
    if missing:
        problems.append("rules.json 缺少字段: %s" % ", ".join(missing))

    print("")
    print("=== 3. 派生产物（构建生成）===")
    if not os.path.isfile(P_GEN_JAVA):
        print("  SKIP   尚未生成（先跑 gradlew generateRules 或 assembleDebug）")
        print("         %s" % P_GEN_JAVA)
    else:
        if os.path.isfile(P_GEN_ASSET):
            b_gen = read_bytes(P_GEN_ASSET)
            same = b_repo == b_gen
            print("  资产副本: %s  %d bytes"
                  % ("OK   字节一致" if same else "FAIL 与源不一致", len(b_gen)))
            if not same:
                problems.append("生成的 assets/rules/rules.json 与源不一致（构建产物过期）")
        else:
            print("  资产副本: MISS %s" % P_GEN_ASSET)
            problems.append("缺少生成的 assets 副本")

        src = io.open(P_GEN_JAVA, encoding="utf-8").read()

        for java_name, json_key in SCALARS:
            if java_name in ("VERSION", "MIN_APP_VERSION_CODE"):
                got = parse_java_int(src, java_name)
                want = j_repo.get(json_key)
            else:
                got = parse_java_constant(src, java_name)
                want = j_repo.get(json_key)
            ok = (got == want)
            print("  %-22s %s" % (json_key, "OK" if ok else "FAIL"))
            if not ok:
                problems.append("%s 与源不一致" % json_key)
                print("      源    = %s" % str(want)[:70])
                print("      生成 = %s" % str(got)[:70])

        for java_name, json_key in ARRAYS:
            got = parse_java_array(src, java_name)
            want = j_repo.get(json_key) or []
            ok = (got == want)
            print("  %-22s %-4s %s" % (json_key, len(want), "OK" if ok else "FAIL"))
            if not ok:
                problems.append("%s 与源不一致（源 %d 条 / 生成 %s 条）"
                                % (json_key, len(want), len(got) if got is not None else "?"))
                if got is not None:
                    only_src = [x for x in want if x not in got]
                    only_gen = [x for x in got if x not in want]
                    if only_src:
                        print("      仅源有: %s" % only_src[:6])
                    if only_gen:
                        print("      仅生成有: %s" % only_gen[:6])

    print("")
    print("=== 4. 结论 ===")
    if problems:
        for p in problems:
            print("  [FAIL] " + p)
        print("")
        print("规则只有 rules/rules.json 一份需要人工维护；"
              "派生产物由 gradlew generateRules 生成，若不一致请重新构建。")
        return 1
    print("  规则单源一致 OK   version=%s  anchors=%d"
          % (j_repo.get("version"), len(j_repo.get("keywordAnchors") or [])))
    return 0


if __name__ == "__main__":
    sys.exit(main())

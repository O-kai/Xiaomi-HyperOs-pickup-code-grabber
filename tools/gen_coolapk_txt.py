#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 COOLAPK-<版本>.md 生成纯文本发布版，并检查酷安不认的 Markdown 符号。

用法: python tools/gen_coolapk_txt.py 4.2.0
输出: docs/COOLAPK-发布用-<版本>.txt

格式与历史版本一致：═ 分段线 + ↓↓/↑↑ 复制区标记转成分隔符排版。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
version = sys.argv[1] if len(sys.argv) > 1 else "4.2.0"
src = os.path.join(ROOT, "docs", "COOLAPK-%s.md" % version)
dst = os.path.join(ROOT, "docs", "COOLAPK-发布用-%s.txt" % version)

if not os.path.exists(src):
    sys.exit("找不到源文件: %s" % src)

text = io.open(src, encoding="utf-8").read()
lines = text.split("\n")

# 收集所有 ↓↓ … ↑↑ 复制区，每块带上它上面最近的 ### 小标题
blocks = []
i = 0
while i < len(lines):
    if lines[i].strip() == "### ↓↓ 从这里复制":
        title = ""
        j = i - 1
        while j >= 0 and not lines[j].strip():
            j -= 1
        if j >= 0 and lines[j].startswith("### "):
            title = lines[j][4:].strip()
        buf = []
        i += 1
        while i < len(lines) and lines[i].strip() != "### ↑↑ 到这里":
            buf.append(lines[i])
            i += 1
        blocks.append((title, "\n".join(buf).strip("\n")))
    i += 1

if not blocks:
    sys.exit("没找到 ↓↓/↑↑ 复制区")

BAR = "═" * 62
SEP = "─" * 46
LABELS = ["① 主图文置顶评论", "② 最新动态", "③ 动态的置顶评论"]

out = []
for n, (title, body) in enumerate(blocks):
    label = LABELS[n] if n < len(LABELS) else title
    if n == 1 and title == "标题":
        label = "② 最新动态 · 标题"
    elif n == 1:
        label = "② 最新动态 · 正文"
    elif n == 2:
        label = "③ 动态的置顶评论"
    elif n >= 3:
        label = title
    out.append(BAR)
    out.append(label)
    out.append(BAR)
    out.append("")
    out.append(body)
    out.append("")
    out.append(SEP)
    out.append("正文结束" if (n == 1 and title == "正文") else "评论结束")
    out.append(SEP)
    out.append("")

content = "\n".join(out).rstrip() + "\n"
io.open(dst, "w", encoding="utf-8", newline="\r\n").write(content)

# ---- 检查酷安不认的符号 ----
bad = []
if "**" in content:
    bad.append("加粗 **")
if "`" in content:
    bad.append("反引号")
for n, ln in enumerate(content.split("\n"), 1):
    if ln.startswith("#"):
        bad.append("井号 第%d行" % n)
if bad:
    print("✗ 纯文本版仍含酷安不认的符号: %s" % "、".join(bad))
    sys.exit(2)
print("✓ 生成: docs/COOLAPK-发布用-%s.txt（%d 字节，%d 段）"
      % (version, len(content.encode("utf-8")), len(blocks)))
print("✓ 未发现 ** / # / 反引号")

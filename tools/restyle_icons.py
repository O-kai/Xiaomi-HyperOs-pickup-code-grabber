#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批量改造功能图标：线框简笔画 → 厚实有层次的图形。

v4.0.3。用户反馈：「在每一个栏目的左前方都有一个小图标，目前这个图标有点太简约了，
是黑白样式或者简单的简笔画状态。」

改造规则（只动视觉规格，不动图形语义——含义必须明确）：
  1. 描边加粗：1.7 → 2.3，端点更圆，视觉重量统一；
  2. 闭合轮廓（pathData 以 z/Z 结尾）额外加一层半透明填充，
     图形从「空心线框」变成「半透明实心 + 实色描边」，饱满度立刻上来；
  3. 开放线条（如圆弧箭头）不加填充——自动闭合会变成怪形状，
     靠加粗描边提升质感即可。

为什么半透明填充在主题色下仍然有层次：
ImageView.setColorFilter(color, PorterDuff.SRC_IN) 的结果是
「源的 RGB × 源的 alpha」，即 tint 只替换颜色、不吃掉 alpha。
所以 fillColor=#33FFFFFF 的那层在着色后仍是主题色的 20% 透明，
与不透明的主形形成明暗两层。
"""

import io
import os
import re
import sys

DRAWABLE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'app', 'res', 'drawable')

# 只处理功能图标（ic_ 前缀）；启动图标与应用内嵌的图不动
TARGET_PREFIX = 'ic_'
SKIP = {'ic_launcher_foreground.xml'}

PATH_RE = re.compile(r'<path\b[^>]*/>', re.S)
ATTR_RE = re.compile(r'([\w:]+)\s*=\s*"([^"]*)"')

OLD_WIDTH = '1.7'
NEW_WIDTH = '2.3'
FILL_ALPHA = '#33FFFFFF'


def parse_attrs(tag: str):
    return dict((m.group(1), m.group(2)) for m in ATTR_RE.finditer(tag))


def build_fill(path_data: str) -> str:
    return ('        <path\n'
            '            android:pathData="%s"\n'
            '            android:fillColor="%s"\n'
            '            android:strokeColor="#00000000"\n'
            '            android:strokeWidth="0" />\n' % (path_data, FILL_ALPHA))


def process(path: str) -> bool:
    src = io.open(path, encoding='utf-8').read()
    changed = False

    def repl(m):
        nonlocal changed
        tag = m.group(0)
        attrs = parse_attrs(tag)
        data = attrs.get('android:pathData', '')
        if not data:
            return tag
        closed = data.strip().endswith('z') or data.strip().endswith('Z')
        stroke_w = attrs.get('android:strokeWidth', '')
        fill = attrs.get('android:fillColor', '')

        out = tag
        # 1) 描边加粗
        if stroke_w == OLD_WIDTH:
            out = out.replace('android:strokeWidth="%s"' % OLD_WIDTH,
                              'android:strokeWidth="%s"' % NEW_WIDTH)
            changed = True
        # 2) 闭合轮廓加半透明填充层（放在原 path 之前）
        if closed and fill.upper() in ('#00000000', ''):
            out = build_fill(data) + out
            changed = True
        return out

    out = PATH_RE.sub(repl, src)
    if changed:
        io.open(path, 'w', encoding='utf-8', newline='\n').write(out)
    return changed


def main():
    names = sorted(n for n in os.listdir(DRAWABLE)
                   if n.startswith(TARGET_PREFIX) and n.endswith('.xml')
                   and n not in SKIP)
    n_changed = 0
    for n in names:
        if process(os.path.join(DRAWABLE, n)):
            n_changed += 1
    print('处理 %d 个图标文件，改造 %d 个' % (len(names), n_changed))
    for n in names:
        print('  - ' + n)


if __name__ == '__main__':
    main()

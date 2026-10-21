#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把原型里的内联 SVG 图标与自绘 App 图标，转换成 Android 矢量图（res/drawable/*.xml）。

用法：python tools/gen-android-icons.py

* 输入：prototype/index.html 的 <symbol id="i-xxx"> 精灵表 + prototype/icons/fallback/*.svg
* 输出：app/res/drawable/ic_<name>.xml          线条图标（统一 #FF000000，运行时 setColorFilter 着色）
      app/res/drawable/app_fallback_<slug>.xml  自绘 App 图标兜底（保留品牌色，作 assets 官方 PNG 缺失时的回退）
* 说明：Android 矢量图不支持 <text>，含文字的图标（淘宝兜底）会自动跳过文字、只保留图形。
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # prototype/
REPO = os.path.dirname(ROOT)                                        # 仓库根
OUT = os.path.join(REPO, 'app', 'res', 'drawable')
HTML = os.path.join(ROOT, 'index.html')
FALLBACK = os.path.join(ROOT, 'icons', 'fallback')
ROOT_ICONS = os.path.join(ROOT, 'icons')

HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="%ddp"\n    android:height="%ddp"\n'
        '    android:viewportWidth="%d"\n    android:viewportHeight="%d">\n')
FOOT = '</vector>\n'
STROKE_DEFAULT = 1.7
MONO = '#FF000000'


def norm_color(c):
    """#RGB / #RRGGBB / rgb(...) → Android 的 #AARRGGBB"""
    if not c or c in ('none', 'currentColor', 'transparent'):
        return None
    c = c.strip()
    if c.startswith('#'):
        h = c[1:]
        if len(h) == 3:
            h = ''.join(ch * 2 for ch in h)
        if len(h) == 6:
            return '#FF' + h.upper()
        return None
    m = re.match(r'rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)', c)
    if m:
        return '#FF%02X%02X%02X' % tuple(int(x) for x in m.groups())
    return None


def round_rect_path(x, y, w, h, r):
    """圆角矩形 → pathData（矢量图没有 rect 元素）"""
    if r <= 0:
        return 'M%.1f,%.1f h%.1f v%.1f h-%.1f z' % (x, y, w, h, w)
    return ('M%.1f,%.1f h%.1f a%.1f,%.1f 0 0 1 %.1f,%.1f v%.1f '
            'a%.1f,%.1f 0 0 1 -%.1f,%.1f h-%.1f a%.1f,%.1f 0 0 1 -%.1f,-%.1f v-%.1f '
            'a%.1f,%.1f 0 0 1 %.1f,-%.1f z'
            % (x + r, y, w - 2 * r, r, r, r, r, h - 2 * r,
               r, r, r, r, w - 2 * r, r, r, r, r, h - 2 * r,
               r, r, r, r))


def circle_path(cx, cy, r):
    return ('M%.2f,%.2f a%.2f,%.2f 0 1,0 %.2f,0 a%.2f,%.2f 0 1,0 -%.2f,0 z'
            % (cx - r, cy, r, r, 2 * r, r, r, 2 * r))


def attr(tag, name, default=None):
    m = re.search(r'%s="([^"]*)"' % name, tag)
    return m.group(1) if m else default


def path_block(d, fill_color, stroke_color, width, alpha):
    a = '        android:pathData="%s"\n' % d
    a += '        android:fillColor="%s"\n' % (fill_color or '#00000000')
    if stroke_color:
        a += ('        android:strokeColor="%s"\n'
              '        android:strokeWidth="%s"\n'
              '        android:strokeLineCap="round"\n'
              '        android:strokeLineJoin="round"\n' % (stroke_color, width))
        if alpha:            # 描边路径的透明度要写 strokeAlpha，写 fillAlpha 不生效
            a += '        android:strokeAlpha="%s"\n' % alpha
    elif alpha:
        a += '        android:fillAlpha="%s"\n' % alpha
    return '    <path\n' + a + '        />\n'


def parse_shapes(body, mono=False):
    """symbol / svg 里的 shape → vector path；mono=True 时统一用黑色（线条图标）"""
    out = []
    for m in re.finditer(r'<(path|circle|rect)\b([^>]*)/?>', body):
        kind, tag = m.group(1), m.group(2)
        fill = attr(tag, 'fill', 'none')
        alpha = attr(tag, 'opacity')
        width = attr(tag, 'stroke-width', STROKE_DEFAULT)

        if kind == 'path':
            d = attr(tag, 'd') or ''
        elif kind == 'circle':
            d = circle_path(float(attr(tag, 'cx')), float(attr(tag, 'cy')), float(attr(tag, 'r')))
        else:
            d = round_rect_path(float(attr(tag, 'x', 0)), float(attr(tag, 'y', 0)),
                                float(attr(tag, 'width')), float(attr(tag, 'height')),
                                float(attr(tag, 'rx', 0)))
        if not d:
            continue

        has_fill = fill not in ('none', None)
        stroke_attr = attr(tag, 'stroke')
        has_stroke = stroke_attr not in ('none',) and not has_fill

        if mono:
            fill_color = MONO if has_fill else None
            stroke_color = MONO if has_stroke else None
        else:
            fill_color = norm_color(fill) if has_fill else None
            stroke_color = norm_color(stroke_attr or '#000000') if has_stroke else None

        out.append(path_block(d, fill_color, stroke_color, width, alpha))
    return out


def gen_line_icons():
    html = io.open(HTML, encoding='utf-8').read()
    n = 0
    for m in re.finditer(r'<symbol id="([^"]+)" viewBox="0 0 (\d+) (\d+)">(.*?)</symbol>', html, re.S):
        sid, sw, sh, body = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        name = (sid[2:] if sid.startswith('i-') else sid).replace('-', '_')
        paths = parse_shapes(body, mono=True)
        if not paths:
            continue
        with io.open(os.path.join(OUT, 'ic_%s.xml' % name), 'w',
                     encoding='utf-8', newline='\n') as f:
            f.write(HEAD % (24, 24, sw, sh) + ''.join(paths) + FOOT)
        n += 1
    return n


def gen_app_fallbacks():
    n = 0
    for fn in sorted(os.listdir(FALLBACK)):
        if not fn.endswith('.svg'):
            continue
        svg = io.open(os.path.join(FALLBACK, fn), encoding='utf-8').read()
        body = svg[svg.index('>', svg.index('<svg')) + 1: svg.rindex('</svg>')]
        body = re.sub(r'<text\b.*?</text>', '', body, flags=re.S)     # 矢量图不支持 text
        paths = parse_shapes(body, mono=False)
        if not paths:
            continue
        slug = fn[:-4].replace('-', '_')
        with io.open(os.path.join(OUT, 'app_fallback_%s.xml' % slug), 'w',
                     encoding='utf-8', newline='\n') as f:
            f.write(HEAD % (48, 48, 96, 96) + ''.join(paths) + FOOT)
        n += 1
    return n


def gen_app_vectors():
    """prototype/icons/*.svg（官方/真实图标，矢量版）→ app/res/drawable/app_<slug>.xml"""
    n = 0
    for fn in sorted(os.listdir(ROOT_ICONS)):
        if not fn.endswith('.svg'):
            continue
        path = os.path.join(ROOT_ICONS, fn)
        svg = io.open(path, encoding='utf-8').read()
        m = re.search(r'viewBox="0 0 (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)"', svg)
        vw, vh = (float(m.group(1)), float(m.group(2))) if m else (96.0, 96.0)
        body = svg[svg.index('>', svg.index('<svg')) + 1: svg.rindex('</svg>')]
        body = re.sub(r'<text\b.*?</text>', '', body, flags=re.S)
        paths = parse_shapes(body, mono=False)
        if not paths:
            continue
        slug = fn[:-4].replace('-', '_')
        with io.open(os.path.join(OUT, 'app_%s.xml' % slug), 'w',
                     encoding='utf-8', newline='\n') as f:
            f.write(HEAD % (48, 48, int(vw), int(vh)) + ''.join(paths) + FOOT)
        n += 1
    return n


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    a = gen_line_icons()
    b = gen_app_fallbacks()
    c = gen_app_vectors()
    print('线条图标 %d 个 + App 兜底图 %d 个 + 真实图标矢量 %d 个 → %s' % (a, b, c, OUT))

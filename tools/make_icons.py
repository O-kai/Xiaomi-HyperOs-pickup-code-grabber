"""
从真机 APK 提取的图标 → 合成 App 内用的 appicons PNG。

不依赖 PIL：PNG 解码/编码用纯 Python（zlib + struct）实现。
只处理本项目需要的两种：
  1) 直接拷贝（蜂巢：本身就是一个方形 PNG）
  2) 自适应图标合成（设备互联：背景色 + 前景 PNG，居中缩放）

用法：python tools/make_icons.py <工作目录> <输出目录>
"""
import os
import struct
import sys
import zlib


# ---------------------------------------------------------------- PNG I/O

def read_png(path):
    """读 PNG → (w, h, RGBA bytes)。支持 8bit 的 RGB/RGBA/灰度/带调色板。"""
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a png: ' + path
    pos = 8
    w = h = 0
    bitdepth = colortype = 0
    idat = b''
    plte = None
    trns = None
    while pos < len(data):
        (length,) = struct.unpack('>I', data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b'IHDR':
            w, h, bitdepth, colortype, comp, filt, interlace = struct.unpack('>IIBBBBB', chunk)
            assert interlace == 0, 'interlaced png not supported'
            assert bitdepth == 8, 'only 8bit supported, got %d' % bitdepth
        elif ctype == b'PLTE':
            plte = chunk
        elif ctype == b'tRNS':
            trns = chunk
        elif ctype == b'IDAT':
            idat += chunk
        elif ctype == b'IEND':
            break

    raw = zlib.decompress(idat)
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    bpp = channels  # 8bit

    out = bytearray(w * h * 4)
    prev = bytearray(w * bpp)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + w * bpp])
        p += w * bpp
        # 反滤波
        if ft == 1:
            for i in range(bpp, len(line)):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ft == 2:
            for i in range(len(line)):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(len(line)):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(len(line)):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa = abs(b - c)
                pb = abs(a - c)
                pc = abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        # 展开成 RGBA
        for x in range(w):
            if colortype == 6:
                r, g, b, a = line[x * 4:x * 4 + 4]
            elif colortype == 2:
                r, g, b = line[x * 3:x * 3 + 3]
                a = 255
            elif colortype == 0:
                r = g = b = line[x]
                a = 255
            elif colortype == 4:
                r = g = b = line[x * 2]
                a = line[x * 2 + 1]
            elif colortype == 3:
                idx = line[x]
                r, g, b = plte[idx * 3:idx * 3 + 3]
                a = trns[idx] if (trns and idx < len(trns)) else 255
            o = (y * w + x) * 4
            out[o] = r
            out[o + 1] = g
            out[o + 2] = b
            out[o + 3] = a
        prev = line
    return w, h, bytes(out)


def write_png(path, w, h, rgba):
    """写 RGBA PNG（8bit，过滤器全 0）。"""
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * w * 4:(y + 1) * w * 4]
    comp = zlib.compress(bytes(raw), 9)

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    out = b'\x89PNG\r\n\x1a\n'
    out += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    out += chunk(b'IDAT', comp)
    out += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(out)


# ---------------------------------------------------------------- 合成

def composite(size, bg, fg, fg_scale):
    """背景色 + 前景 PNG 居中合成（前景按比例缩放）。"""
    out = bytearray(size * size * 4)
    for i in range(size):
        o = i * 4
        out[o] = bg[0]
        out[o + 1] = bg[1]
        out[o + 2] = bg[2]
        out[o + 3] = 255
    fw, fh, fdata = fg
    tw = int(fw * fg_scale)
    th = int(fh * fg_scale)
    ox = (size - tw) // 2
    oy = (size - th) // 2
    for y in range(th):
        sy = int(y / fg_scale)
        if sy >= fh:
            break
        ty = oy + y
        if ty < 0 or ty >= size:
            continue
        for x in range(tw):
            sx = int(x / fg_scale)
            if sx >= fw:
                break
            tx = ox + x
            if tx < 0 or tx >= size:
                continue
            si = (sy * fw + sx) * 4
            a = fdata[si + 3]
            if a == 0:
                continue
            di = (ty * size + tx) * 4
            if a == 255:
                out[di] = fdata[si]
                out[di + 1] = fdata[si + 1]
                out[di + 2] = fdata[si + 2]
                out[di + 3] = 255
            else:
                # source-over 混合
                f = a / 255.0
                out[di] = int(fdata[si] * f + out[di] * (1 - f))
                out[di + 1] = int(fdata[si + 1] * f + out[di + 1] * (1 - f))
                out[di + 2] = int(fdata[si + 2] * f + out[di + 2] * (1 - f))
                out[di + 3] = 255
    return bytes(out)


def resize(src, size):
    """最近邻缩放到 size×size（图标是小尺寸够用）。"""
    sw, sh, sd = src
    out = bytearray(size * size * 4)
    for y in range(size):
        sy = min(sh - 1, int(y * sh / size))
        for x in range(size):
            sx = min(sw - 1, int(x * sw / size))
            si = (sy * sw + sx) * 4
            di = (y * size + x) * 4
            out[di:di + 4] = sd[si:si + 4]
    return (size, size, bytes(out))


def main():
    work = sys.argv[1]
    outdir = sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    # 1) 蜂巢：直接缩放到 96px（界面图标 48dp @xxhdpi = 144px，取 144 更清晰）
    src = read_png(os.path.join(work, 'ex_fc/res/mipmap-xxhdpi-v4/ic_launcher.png'))
    write_png(os.path.join(outdir, 'fengchao.png'), *resize(src, 144))

    # 2) 设备互联：蓝底 #439DF6 + 白色 N 形箭头前景
    fg = read_png(os.path.join(work, 'ex_mi/res/Lf.png'))
    # 自适应图标的前景在 108dp 画布里只占中间 72dp，
    # 所以前景要按 72/108 缩到画布内，再整体按 66% 居中（对齐 Android 规范）
    canvas = composite(144, (0x43, 0x9d, 0xf6), fg, 66.0 / 108.0)
    write_png(os.path.join(outdir, 'mi_connect.png'), 144, 144, canvas)

    for f in sorted(os.listdir(outdir)):
        p = os.path.join(outdir, f)
        w, h, _ = read_png(p)
        print('%-20s %dx%d  %d bytes' % (f, w, h, os.path.getsize(p)))


if __name__ == '__main__':
    main()

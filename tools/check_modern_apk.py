#!/usr/bin/env python3
"""Check the module entry and ensure legacy/runtime API definitions are absent from the APK."""
import argparse
import struct
import zipfile


def defined_classes(dex):
    string_count, string_offset = struct.unpack_from('<II', dex, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from('<I', dex, string_offset + 4 * index)[0]
        while dex[offset] & 0x80:
            offset += 1
        offset += 1  # Skip the last byte of UTF-16 length (ULEB128).
        end = dex.index(b'\0', offset)
        strings.append(dex[offset:end])
    type_count, type_offset = struct.unpack_from('<II', dex, 64)
    types = [strings[struct.unpack_from('<I', dex, type_offset + 4 * i)[0]] for i in range(type_count)]
    class_count, class_offset = struct.unpack_from('<II', dex, 96)
    return [types[struct.unpack_from('<I', dex, class_offset + 32 * i)[0]] for i in range(class_count)]


def check(path, probe=False):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert 'assets/xposed_init' not in names, 'Legacy entry still packaged'
        metadata = [name for name in names if name.startswith('META-INF/xposed/')]
        if probe:
            assert not metadata, 'Renderer probe must not register as a module'
        else:
            prefix = 'META-INF/xposed/'
            assert apk.read(prefix + 'java_init.list').decode().strip() == 'io.github.okaidev.pickupcode.XposedEntry'
            props = dict(line.split('=', 1) for line in apk.read(prefix + 'module.prop').decode().splitlines() if '=' in line)
            assert props['minApiVersion'] == props['targetApiVersion'] == '102'
            assert props['autoHotReload'] == 'false'
            scope = set(apk.read(prefix + 'scope.list').decode().splitlines())
            assert {'system', 'com.cainiao.wireless', 'com.xunmeng.pinduoduo', 'com.xiaomi.subscreencenter'} <= scope
        for name in names:
            if not name.endswith('.dex'):
                continue
            dex = apk.read(name)
            assert b'de/robv/android/xposed' not in dex, 'Legacy API reference remains'
            assert all(not cls.startswith(b'Lio/github/libxposed/api/') for cls in defined_classes(dex)), 'Framework-only API classes bundled'
        print('PASS modern APK:', path)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--probe', action='store_true')
    args = parser.parse_args()
    check(args.apk, args.probe)

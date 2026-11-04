import io

# ---------- 1) RulesRegression：编译时补一个 NotiHook 替身 ----------
p = r'C:\Users\83420\pickup4\test\RulesRegression.java'
s = io.open(p, encoding='utf-8').read()

old = """        for (String f : NEED) {
            Files.copy(srcDir.resolve(f), tmp.resolve(f));
        }
"""
new = """        for (String f : NEED) {
            Files.copy(srcDir.resolve(f), tmp.resolve(f));
        }

        // 测试替身：真实 NotiHook 依赖 Xposed API（compileOnly，离线没有 jar）。
        // ProcessSync 引用了 NotiHook.BUILTIN_PKGS，-sourcepath 自动推导会把它
        // 拖进依赖链，于是整条链编译失败。这里显式编译一个只保留常量的替身，
        // 命令行里给的文件优先于 sourcepath 里的同名类。
        Files.write(tmp.resolve("NotiHook.java"), (
                "package io.github.okaidev.pickupcode;\\n"
                + "public final class NotiHook {\\n"
                + "    public static final String[] BUILTIN_PKGS = new String[0];\\n"
                + "    public static final java.util.Map<String, String> PKG_LABELS"
                + " = new java.util.HashMap<>();\\n"
                + "    private NotiHook() { }\\n"
                + "}\\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
"""
assert old in s, 'NEED copy block not found'
s = s.replace(old, new, 1)

old2 = """        // v4.0：内置默认规则来自构建生成的 GeneratedRules.java，必须一起编译
        cmd.add(tmp.resolve("GeneratedRules.java").toString());"""
new2 = """        // v4.0：内置默认规则来自构建生成的 GeneratedRules.java，必须一起编译
        cmd.add(tmp.resolve("GeneratedRules.java").toString());
        // Xposed 依赖的替身（见上面写入 NotiHook.java 的注释）
        cmd.add(tmp.resolve("NotiHook.java").toString());"""
assert old2 in s, 'cmd block not found'
s = s.replace(old2, new2, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('RulesRegression ok')

# ---------- 2) run_all_tests.sh ----------
p2 = r'C:\Users\83420\pickup4\tools\run_all_tests.sh'
t = io.open(p2, encoding='utf-8').read()

stub = (
    "# 测试替身：真实 NotiHook 依赖 Xposed API（compileOnly，离线没有 jar）。\n"
    "# ProcessSync 引用了 NotiHook.BUILTIN_PKGS，-sourcepath 自动推导会把它拖进来，\n"
    "# 于是「规则依赖链」整条编译失败。这里放一个只保留常量的替身，\n"
    "# 并把拷贝目录放在 sourcepath 最前面，让它优先命中。\n"
    "cat > \"$OUT/src/io/github/okaidev/pickupcode/NotiHook.java\" <<'PICKUP_STUB_EOF'\n"
    "package io.github.okaidev.pickupcode;\n"
    "\n"
    "public final class NotiHook {\n"
    "    public static final String[] BUILTIN_PKGS = new String[0];\n"
    "    public static final java.util.Map<String, String> PKG_LABELS = new java.util.HashMap<>();\n"
    "    private NotiHook() { }\n"
    "}\n"
    "PICKUP_STUB_EOF\n"
    "\n"
)
anchor = 'GEN="$ROOT/app/build/generated/rules/java/io/github/okaidev/pickupcode/GeneratedRules.java"'
assert anchor in t, 'GEN anchor not found'
if 'PICKUP_STUB_EOF' not in t:
    t = t.replace(anchor, stub + anchor, 1)

old4 = '    -sourcepath "app/src" \\\n'
new4 = '    -sourcepath "$OUT/src;app/src" \\\n'
assert old4 in t, 'sourcepath line not found'
t = t.replace(old4, new4, 1)
io.open(p2, 'w', encoding='utf-8', newline='\n').write(t)
print('script ok')

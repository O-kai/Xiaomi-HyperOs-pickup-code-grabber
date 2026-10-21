import io

P = r"D:\project\Xiaomi-HyperOs-pickup-code-grabber\app\src\io\github\okaidev\pickupcode\LauncherActivity.java"

with open(P, encoding="utf-8") as f:
    lines = f.readlines()

# 找到 buildExtraBoxContent 方法起始（含其上方注释行）
start = None
for i, l in enumerate(lines):
    if "private void buildExtraBoxContent" in l:
        start = i
        # 往上吃掉紧邻的注释/空行
        while start > 0 and (lines[start - 1].strip().startswith("/**")
                             or lines[start - 1].strip().startswith("*")
                             or lines[start - 1].strip().startswith("//")
                             or lines[start - 1].strip() == ""):
            start -= 1
            if lines[start].strip().startswith("/**"):
                break
        break

if start is None:
    raise SystemExit("未找到 buildExtraBoxContent")

# 找方法结束：从 start 起，遇到第一个单独一行的 "    }" 且下一行是空行或下一个方法
end = None
depth = 0
seen_open = False
for i in range(start, len(lines)):
    depth += lines[i].count("{") - lines[i].count("}")
    if "{" in lines[i]:
        seen_open = True
    if seen_open and depth == 0:
        end = i
        break

if end is None:
    raise SystemExit("未找到方法结束")

print("删除行范围: %d .. %d" % (start + 1, end + 1))
print("首行: %s" % lines[start].rstrip())
print("末行: %s" % lines[end].rstrip())

del lines[start:end + 1]

with open(P, "w", encoding="utf-8", newline="\n") as f:
    f.writelines(lines)
print("已删除，剩余 %d 行" % len(lines))

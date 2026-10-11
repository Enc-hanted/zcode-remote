# -*- coding: utf-8 -*-
"""0.4.0 提交前校验：XML 良构、Kotlin 括号平衡、内嵌 JS 语法、资源引用完整性。"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app")
SRC = os.path.join(APP, "src", "main")
errors = []


def err(msg):
    errors.append(msg)
    print("FAIL:", msg)


def ok(msg):
    print("  ok:", msg)


# 1) 所有 XML 良构
xml_files = []
for base, _, names in os.walk(os.path.join(SRC, "res")):
    for n in names:
        if n.endswith(".xml"):
            xml_files.append(os.path.join(base, n))
xml_files.append(os.path.join(SRC, "AndroidManifest.xml"))
for f in xml_files:
    try:
        ET.parse(f)
    except Exception as e:
        err("XML %s: %s" % (os.path.relpath(f, ROOT), e))
ok("XML parsed: %d files" % len(xml_files))

# 2) Kotlin 括号平衡（粗检，剔除字符串/注释后统计）
kt_files = []
for base, _, names in os.walk(os.path.join(SRC, "java")):
    for n in names:
        if n.endswith(".kt"):
            kt_files.append(os.path.join(base, n))


def strip_strings(text):
    # 先删 raw string，再删普通字符串与字符字面量
    text = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', text, flags=re.S)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)
    text = re.sub(r"'(?:[^'\\]|\\.)*'", "''", text)
    return text


def strip_kotlin(text):
    text = strip_strings(text)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def comment_depth_violation(text):
    # Kotlin 块注释可嵌套：注释文本里出现 /* 会真的再开一层（CI 实测翻车源——
    # 非贪婪删除正则会提前闭合，被吞的代码逃过下面的括号平衡检查）。逐 token 计深度。
    depth = 0
    i = 0
    while i < len(text):
        if text.startswith("/*", i):
            depth += 1
            i += 2
        elif text.startswith("*/", i):
            depth -= 1
            if depth < 0:
                return "多余的 */"
            i += 2
        else:
            i += 1
    if depth != 0:
        return "块注释未闭合（嵌套深度残值 %d——检查注释文本里的 /* 字样）" % depth
    return None


for f in kt_files:
    src = open(f, encoding="utf-8").read()
    violation = comment_depth_violation(strip_strings(src))
    if violation:
        err("KT %s: %s" % (os.path.basename(f), violation))
    stripped = strip_kotlin(src)
    for pair in ["{}", "()", "[]"]:
        a, b = pair
        if stripped.count(a) != stripped.count(b):
            err("KT %s: unbalanced %s (%d vs %d)" % (
                os.path.basename(f), pair,
                stripped.count(a), stripped.count(b)))
ok("KT brace balance: %d files" % len(kt_files))

# 3) 内嵌 JS 语法：从 WebActivity.kt 抽出 BUNDLE_JS（可能拆多段拼接），用 node --check
web = open(os.path.join(SRC, "java", "com", "zcode", "remote", "WebActivity.kt"),
           encoding="utf-8").read()
for const_name in ["BUNDLE_JS"]:
    m = re.search(const_name + r' =\n((?:""".*?"""\s*(?:\+\s*)?)+)', web, flags=re.S)
    if not m:
        err(const_name + " not found")
        continue
    joined = "".join(re.findall(r'"""(.*?)"""', m.group(1), flags=re.S))
    js = "(" + joined + ")(null,true,true,true,true)"
    dev_dir = os.path.join(ROOT, "dev")
    os.makedirs(dev_dir, exist_ok=True)
    tmp = os.path.join(dev_dir, "_check_embedded.js")
    open(tmp, "w", encoding="utf-8").write(js)
    r = subprocess.run(["node", "--check", tmp], capture_output=True, text=True)
    if r.returncode != 0:
        err("%s syntax: %s" % (const_name, r.stderr.strip()[:300]))
    else:
        ok(const_name + " syntax")

# 4) 布局引用的 @string/@style/@drawable/@color 是否都存在
strings = set(re.findall(r'name="([^"]+)"',
    open(os.path.join(SRC, "res", "values", "strings.xml"), encoding="utf-8").read()))
style_src = open(os.path.join(SRC, "res", "values", "styles.xml"), encoding="utf-8").read()
style_src += open(os.path.join(SRC, "res", "values", "themes.xml"), encoding="utf-8").read()
styles = set(re.findall(r'name="([^"]+)"', style_src))
colors = set(re.findall(r'name="([^"]+)"',
    open(os.path.join(SRC, "res", "values", "colors.xml"), encoding="utf-8").read()))
drawables = set()
# drawable 支持带限定符的目录（drawable-nodpi 等），全部纳入
for dd in glob.glob(os.path.join(SRC, "res", "drawable*")):
    for n in os.listdir(dd):
        drawables.add(os.path.splitext(n)[0])

for f in xml_files:
    content = open(f, encoding="utf-8").read()
    rel = os.path.relpath(f, ROOT)
    for name in re.findall(r'@string/([A-Za-z0-9_]+)', content):
        if name not in strings:
            err("%s: missing @string/%s" % (rel, name))
    for name in re.findall(r'@style/(Settings[A-Za-z0-9_]+)', content):
        if name not in styles:
            err("%s: missing @style/%s" % (rel, name))
    for name in re.findall(r'@drawable/([A-Za-z0-9_]+)', content):
        if name not in drawables:
            err("%s: missing @drawable/%s" % (rel, name))
    for name in re.findall(r'@color/([A-Za-z0-9_]+)', content):
        if name not in colors and not name.startswith("android:"):
            err("%s: missing @color/%s" % (rel, name))
ok("resource references checked")

# 5) 代码里 getString(R.string.xxx) / R.id 引用的 string 存在
for f in kt_files:
    content = open(f, encoding="utf-8").read()
    for name in re.findall(r'(?<!android\.)R\.string\.([A-Za-z0-9_]+)', content):
        if name not in strings:
            err("%s: missing R.string.%s" % (os.path.basename(f), name))
ok("R.string references checked")

print()
if errors:
    print("TOTAL FAILURES: %d" % len(errors))
    sys.exit(1)
print("ALL CHECKS PASSED")

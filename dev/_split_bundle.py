# -*- coding: utf-8 -*-
"""一次性工具：把 _bundle.js 按段落边界切成 dev/bundle/ 模块（保留原文逐字节内容）。
用法: python dev/_split_bundle.py
之后 _patch_web2.py 按文件名序拼接模块 == 原 _bundle.js（模块头注释行除外）。
"""
import os

SRC = "_bundle.js"
OUT = "dev/bundle"
os.makedirs(OUT, exist_ok=True)

lines = open(SRC, encoding="utf-8").read().splitlines(keepends=True)

# 段落边界（行号 1-based，含该行）：边界都落在段落注释行上，保证切片不截断语句
MODULES = [
    ("01_core.js", 1, 66),        # 头部/BODY 开头、开关、可见性谎报、错误捕获、共享变量
    ("02_style.js", 67, 228),     # 动效 + 注入样式大 CSS + hint/vibrate/bridge
    ("03_fix.js", 229, 304),      # 修正样式 + 溢出巡检 fix/schedule
    ("04_theme.js", 305, 619),    # 主题检测、就近取色、消息定位、诊断卡
    ("05_nav.js", 620, 951),      # 遮罩、真导航、自建面板、拖拽
    ("06_composer.js", 952, 1371),# 浮动 logo、输入框劫持、圆弧按钮、弹窗复活、组合手势
    ("07_settings.js", 1372, 1707),# 渲染时序兜底、设置即时生效 API、新回复监听
    ("08_boot.js", 1708, None),   # 顶层执行 + 同源广播（iframe/shadow）
]

total = len(lines)
for name, start, end in MODULES:
    stop = total if end is None else end
    chunk = "".join(lines[start - 1:stop])
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
        f.write("// ===== %s (%d-%s) =====\n" % (name, start, stop if end is None else end))
        f.write(chunk)
    print("wrote %s: lines %d-%d (%d chars)" % (name, start, stop, len(chunk)))

# 校验：拼接（去掉模块头注释）应与原文一致
concat = ""
for name, _, _ in MODULES:
    with open(os.path.join(OUT, name), encoding="utf-8") as f:
        for ln in f.read().splitlines(keepends=True):
            if ln.startswith("// ===== "):
                continue
            concat += ln
if concat == "".join(lines):
    print("\nOK: concat == original _bundle.js (byte-identical)")
else:
    print("\nMISMATCH: concat differs from original! check slices")
    for i, (a, b) in enumerate(zip(concat, "".join(lines))):
        if a != b:
            print("first diff at concat offset", i)
            break

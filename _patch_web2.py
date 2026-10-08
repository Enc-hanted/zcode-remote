# -*- coding: utf-8 -*-
"""把 dev/bundle/*.js 按文件名序拼接成注入脚本，写入 _bundle.js（生成物），再替换 WebActivity.kt 的 JS 常量区。
（MOBILE_CSS 保留，去掉 CSS_TURN_NAVIGATOR/TURN_NAV_JS/REPLY_WATCH_JS，新增 BUNDLE_JS。）
源码唯一真相源是 dev/bundle/ 下的模块，改 JS 一律改模块文件；_bundle.js 由本脚本生成，勿手改。
"""
import glob
import io
import os
import re

p = os.path.join("app", "src", "main", "java", "com", "zcode", "remote", "WebActivity.kt")
s = open(p, encoding="utf-8").read()

start_marker = 'private const val MOBILE_CSS = """'
i = s.index(start_marker)

# BUNDLE_JS 是多段 raw string 拼接（private val BUNDLE_JS = """…""" + """…"""），
# 结尾用正则匹配（与 _validate.py 一致），不再依赖后面的 MOBILE_FIX_JS 常量。
m = re.search(r'private val BUNDLE_JS =\n(?:""".*?"""\s*(?:\+\s*)?)+', s, flags=re.S)
if not m:
    raise SystemExit("BUNDLE_JS block not found in WebActivity.kt")
j = m.end()

# 保留 MOBILE_CSS 原文
moible_start = s.index('"""', i) + 3
mobile_css_body = s[moible_start:s.index('\n"""', moible_start)]
mobile_css_block = start_marker + mobile_css_body + '\n"""'

scroll_hidden = '''        /** 滚动条完全隐藏（手机上点不到，直接不要，内容占满全宽） */
        private const val CSS_SCROLLBAR_HIDDEN = """
* { scrollbar-width: none !important; scrollbar-color: transparent transparent !important; }
::-webkit-scrollbar { width: 0 !important; height: 0 !important; display: none !important; }
::-webkit-scrollbar-track, ::-webkit-scrollbar-thumb, ::-webkit-scrollbar-button, ::-webkit-scrollbar-corner { display: none !important; width: 0 !important; height: 0 !important; }
"""
'''

scroll_slim = '''        /** 3px 半透明细滚动条（备用方案，不挤占内容右侧） */
        private const val CSS_SCROLLBAR_SLIM = """
* { scrollbar-width: thin !important; scrollbar-color: rgba(170,180,200,0.30) transparent !important; scrollbar-gutter: auto !important; }
::-webkit-scrollbar { width: 3px !important; height: 3px !important; }
::-webkit-scrollbar-track, ::-webkit-scrollbar-corner { background: transparent !important; }
::-webkit-scrollbar-thumb { background: rgba(170,180,200,0.30) !important; border-radius: 2px !important; border: none !important; }
::-webkit-scrollbar-button { display: none !important; }
"""
'''

# 模块拼接（文件名序 = 段落顺序）；同时写回 _bundle.js 供预览服务/冒烟测试使用（生成物，已 gitignore）
mods = sorted(glob.glob(os.path.join("dev", "bundle", "*.js")))
if not mods:
    raise SystemExit("no modules in dev/bundle/")
parts = []
for f in mods:
    with io.open(f, encoding="utf-8") as mf:
        for ln in mf.read().splitlines(keepends=True):
            if ln.startswith("// ===== "):
                continue   # 模块头注释只是给人看的，不进入注入包
            parts.append(ln)
bundle = "".join(parts)
with io.open("_bundle.js", "w", encoding="utf-8", newline="\n") as out:
    out.write(bundle)
print("bundle built from %d modules, %d bytes -> _bundle.js" % (len(mods), len(bundle.encode("utf-8"))))

# BUNDLE_JS 拆成多段（JVM 常量池单字符串上限 65535 字节 UTF-8）：
# 单段 < 60000 字节即可避开；运行时拼接成完整 JS。段数由实际字节数决定。
b = bundle.encode("utf-8")
parts = []
while b:
    cut = min(len(b), 60000)
    if cut < len(b):
        cut2 = b.rfind(b"\n", 0, cut)
        if cut2 > 0 and cut - cut2 < 2000:
            cut = cut2 + 1
        else:
            # 60000 字节内没有合适换行：退到 UTF-8 字符边界再切，
            # 避免截断多字节字符导致 decode 报错（0b10xxxxxx 是续字节）
            while cut > 0 and (b[cut] & 0xC0) == 0x80:
                cut -= 1
    parts.append(b[:cut].decode("utf-8"))
    b = b[cut:]

parts_code = " +\n".join('"""' + p + '"""' for p in parts)
bundle_block = '''        /**
         * 广播式注入包（在顶层文档执行，并递归广播到同源 iframe 与 shadow DOM）：
         * 1) 溢出修正巡检器；2) 主题回报（页面背景色匹配 ZCode 主题 22/22/22 或 248/248/248）；
         * 3) 左缘三滑唤出对话问题导航（展开页面真导航，拖动悬停预览，点条目跳转）；
         *    真导航不存在时退化为自建面板；4) 新回复监听上报原生层。
         * 各文档用 root.__zcodeDone 防重入，同窗口多实例用 __zcodeNavLastToggle 去抖。
         * 注意：JS 超长时拆多段字符串拼接（JVM 常量池单字符串限 65535 字节），段数见下方。
         */
        private val BUNDLE_JS =
''' + parts_code + "\n"

new_block = mobile_css_block + '\n\n' + scroll_hidden + '\n' + scroll_slim + '\n' + bundle_block
s = s[:i] + new_block + s[j:]

open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched ok")

# -*- coding: utf-8 -*-
"""把 _turn_nav.js / _reply_watch.js 灌进 WebActivity.kt 的常量。"""
import io

p = r"app\src\main\java\com\zcode\remote\WebActivity.kt"
s = open(p, encoding="utf-8").read()

# 1) 替换 TURN_NAV_JS 整个常量
start = 'private const val TURN_NAV_JS = """'
i = s.index(start)
j = s.index('\n"""', i) + len('\n"""')
new_turn = io.open("_turn_nav.js", encoding="utf-8").read().rstrip("\n")
s = s[:i] + start + '\n' + new_turn + '\n"""' + s[j:]

# 2) TURN_NAV_JS 结束后插入 REPLY_WATCH_JS 常量
k = s.index('\n"""', s.index(start)) + len('\n"""')
new_reply = io.open("_reply_watch.js", encoding="utf-8").read().rstrip("\n")
comment = (
    "\n\n        /**\n"
    "         * 新回复监听：观察会话容器的新增消息块 + 轮询文本增长（流式输出兜底），\n"
    "         * 命中后通过 zcodeBridge 通知原生层；原生只在 App 不在前台时才弹通知。\n"
    "         * 页面结构是启发式识别，可能误报/漏报。\n"
    "         */\n"
)
reply_block = comment + '        private const val REPLY_WATCH_JS = """\n' + new_reply + '\n"""'
s = s[:k] + reply_block + s[k:]

open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched ok")

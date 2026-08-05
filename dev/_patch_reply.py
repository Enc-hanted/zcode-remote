# -*- coding: utf-8 -*-
"""把 _reply_section.txt 替换进 _bundle.js（从 'function send(el){' 到观察器 catch 结束）。"""
import io

p = "_bundle.js"
s = open(p, encoding="utf-8").read()

start = s.index("    function send(el){")
end = s.index("    } catch (e) {}", start) + len("    } catch (e) {}")

new_section = io.open("_reply_section.txt", encoding="utf-8").read().rstrip("\n")
s = s[:start] + new_section + s[end:]

open(p, "w", encoding="utf-8", newline="\n").write(s)
print("reply section patched ok")

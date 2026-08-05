# -*- coding: utf-8 -*-
"""本地注入预览服务器：从仓库根起 http 服务并自动打开浏览器。
用法: python dev/_preview_server.py [port]
（必须用 http 打开，file:// 下浏览器禁止 fetch，页面加载不到 _bundle.js）
"""
import http.server
import os
import socketserver
import sys
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765

os.chdir(ROOT)
handler = http.server.SimpleHTTPRequestHandler

# Windows 上 http.server 默认日志刷屏，压制访问日志
class Quiet(handler):
    def log_message(self, *args):
        pass

    def end_headers(self):
        # 允许真实页面（https://zcode.z.ai）fetch 本地 bundle 做注入验证
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

with socketserver.TCPServer(("127.0.0.1", PORT), Quiet) as httpd:
    url = "http://127.0.0.1:%d/dev/_preview.html" % PORT
    print("预览服务已启动: %s  (Ctrl+C 退出)" % url)
    webbrowser.open(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")

#!/usr/bin/env python3
"""T10-9 验收备选：WEBHOOK 字节级取证代理（仅本地回环，只记录不转发）。

用途：SSRF 防护按设计拦环回地址（127/8），所以**真机 WEBHOOK 投递无法直接打本地 stub**。
主路径用公网回声端点（https://httpbin.org/post）验证「2xx → DELIVERED」；
若需要**逐字节**证明真实 dispatcher 的请求头/请求体（X-WxSync-Signature / Timestamp /
Delivery + payloadJson 原文），则用本代理配合 JVM 代理属性：

    java -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<port> -jar ...

webhook_url 用 **http://**（明文，端口 80 合法）指向一个解析到公网 IP 的域名
（如 http://httpbin.org/post）：validator 过的是域名解析（公网 IP 通过），
实际 TCP 落到本代理 → 代理记录完整请求后直接回 200，不外发。

安全边界：本脚本只监听 127.0.0.1，只回 200 与固定 JSON，不转发任何流量到外部。
"""

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECORDS = []
LOCK = threading.Lock()
OUT = "scripts/t10_webhook_recorded.json"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _record(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        rec = {
            "method": method,
            "path": self.path,
            "headers": {k: v for k, v in self.headers.items()},
            "body": body.decode("utf-8", "replace"),
            "body_len": len(body),
        }
        with LOCK:
            RECORDS.append(rec)
            out = json.dumps(rec, ensure_ascii=False, indent=2)
            # 每次请求即落盘（进程被 kill 也不丢证据）
            with open(OUT, "w", encoding="utf-8") as f:
                json.dump(RECORDS, f, ensure_ascii=False, indent=2)
        payload = out.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        self._record("POST")

    def do_GET(self):
        self._record("GET")

    def log_message(self, *args):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--out", default="scripts/t10_webhook_recorded.json")
    args = ap.parse_args()
    global OUT
    OUT = args.out
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"recorder listening on 127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        with LOCK:
            with open(OUT, "w", encoding="utf-8") as f:
                json.dump(RECORDS, f, ensure_ascii=False, indent=2)
            print(f"recorded {len(RECORDS)} request(s) → {OUT}", flush=True)


if __name__ == "__main__":
    main()

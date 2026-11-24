#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""admin_token.py —— 取一枚可用的 admin access token（过期自动用 refresh 轮换并落盘）。

用法：
  python scripts/admin_token.py            # 打印 token（供 Swagger Authorize / curl 用）
  python scripts/admin_token.py --url ...  # 指定服务端地址

Swagger 入口：http://127.0.0.1:8080/swagger-ui.html → 右上 Authorize → 粘贴本输出。
注意：refresh 是一次性凭据，本脚本与 auto_reply_demo.py 共享同一对令牌文件，
谁过期谁轮换，不会互相破坏；两进程同时过期并发轮换的理论窗口可忽略（2h 一遇）。
"""

import argparse
import json
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--access", default=str(ROOT / ".t11_admin_access.txt"))
    ap.add_argument("--refresh", default=str(ROOT / ".t11_admin_refresh.txt"))
    a = ap.parse_args()
    access = Path(a.access).read_text(encoding="utf-8").strip()
    refresh = Path(a.refresh).read_text(encoding="utf-8").strip()

    with httpx.Client(timeout=10) as c:
        r = c.get(a.base + "/v1/accounts?page=1&page_size=1",
                  headers={"Authorization": "Bearer " + access})
        if r.json().get("code") in (10200, 10201):  # 过期/缺失 → 轮换
            rr = c.post(a.base + "/v1/auth/refresh",
                        headers={"Authorization": "Bearer " + refresh},
                        json={"refresh_token": refresh})
            d = (rr.json().get("data") or {})
            if not d:
                raise SystemExit("refresh 轮换失败（可能已被消费/整族撤销）——请重新 bootstrap")
            access, refresh = d["access_token"], d["refresh_token"]
            Path(a.access).write_text(access, encoding="utf-8")
            Path(a.refresh).write_text(refresh, encoding="utf-8")
            print("# access 已过期，refresh 轮换成功，新令牌对已落盘", file=__import__('sys').stderr)
    print(access)


if __name__ == "__main__":
    main()

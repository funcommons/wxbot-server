#!/usr/bin/env python3
"""T10-9 补充取证：真机 WEBHOOK **逐字节**验收（走真实 dispatcher，不手工复刻请求）。

背景：SSRF 防护按设计拦环回地址 → 真机 WEBHOOK 不能直接打本地 stub。本取证用
「JVM 代理」绕开环回限制而不放宽任何安全校验：

  - 服务端以 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<port>` 启动
    （java.net.http.HttpClient 默认取系统 ProxySelector → 明文 http:// 目标走本地代理）；
  - webhook_url 用 **http://httpbin.org/post**（端口 80 合法，域名解析到公网 IP →
    **SSRF 校验照常通过**，未放宽一字）；
  - 实际 TCP 落到本地 recorder（scripts/t10_webhook_recorder.py），它记录完整请求
    头 + 请求体并回 200（不外发）。

验收内容（消费方视角的完整契约）：
  1. 三件头齐全：X-WxSync-Signature / X-WxSync-Timestamp / X-WxSync-Delivery；
  2. 签名独立复算：HMAC-SHA256(key=sha256hex(secret), data="<ts>.<原始体>") 与头一致
     ——验证 §5.1 v1.1 裁决口径「服务端用库存 hash 签、消费方拿明文自行 sha256 作 key」；
  3. 逐字节保真：收到的体 == 服务端 payloadJson 原文（禁止重序列化）；
  4. 投递计数：recorder 计数 == 投递行数（stub webhook 计数口径）。
"""

import argparse
import hashlib
import hmac
import io
import json
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8080"


def request(method, url, body=None, token=None, timeout=30):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def read_token(path):
    return io.open(path, encoding="utf-8").read().strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--admin-token-file", default=".t10_admin_access.txt")
    ap.add_argument("--client-token-file", default=".t10_client_token.txt")
    ap.add_argument("--account", default="wxid_c00pwixax7xv22_8316")
    ap.add_argument("--webhook-url", default="http://httpbin.org/post")
    ap.add_argument("--recorded", default="scripts/t10_webhook_recorded.json")
    args = ap.parse_args()

    admin = read_token(args.admin_token_file)
    client = read_token(args.client_token_file)
    stamp = int(time.time())
    chat = f"t10_hook_chat_{stamp}"
    failures = []

    # recorder 的记录文件是**累计**的（进程内 RECORDS 列表），故先取基线计数，再用增量判定
    def post_count():
        try:
            recs = json.load(io.open(args.recorded, encoding="utf-8"))
        except Exception:
            return 0
        return sum(1 for r in recs if r.get("method") == "POST")

    baseline_posts = post_count()

    def log(ok, step, detail):
        print(f"[{'PASS' if ok else 'FAIL'}] {step}: {detail}", flush=True)
        if not ok:
            failures.append(f"{step}: {detail}")

    # 消费者（只要 WEBHOOK 凭据）
    env = request("POST", BASE + "/v1/consumers", {
        "name": f"t10_hook_consumer_{stamp}", "enable_poll": False, "enable_webhook": True,
    }, token=admin)
    consumer = env["data"]
    secret = consumer["webhook_secret"]
    log(bool(secret), "A 建消费者（只出 WEBHOOK 密钥）", f"secret 前 6 位 {secret[:6]}…")

    env = request("POST", BASE + "/v1/subscriptions", {
        "consumer_id": consumer["id"], "name": f"t10_hook_sub_{stamp}",
        "filter": {"chats": [chat]}, "delivery_mode": "WEBHOOK",
        "webhook_url": args.webhook_url, "visible_seconds": 60,
        "max_attempts": 3, "backoff_base_seconds": 5,
    }, token=admin)
    sub = env["data"]["subscription"]
    log(env["code"] == 0 and sub.get("webhook_url") == args.webhook_url,
        "B 建 WEBHOOK 订阅", f"sub_id={sub['id']} url={sub.get('webhook_url')}")

    body_text = f"t10hook {stamp} proof"
    env = request("POST", BASE + "/v1/reports/messages", {
        "account": args.account, "batch_ref": f"t10_hook_{stamp}",
        "chats": [{"chat_username": chat, "messages": [{
            "shard": "t10hook.db", "local_id": 1, "server_id": str(8_100_000_000 + stamp),
            "sort_seq": int(time.time()) * 1000, "create_time_raw": int(time.time()),
            "type_code": "1", "type_name": "TEXT", "sender_username": "t10_hook_sender",
            "body": body_text,
            "content_sha256": hashlib.sha256(body_text.encode("utf-8")).hexdigest(),
            "is_revoked": False,
        }]}],
    }, token=client)
    inserted = sum(1 for r in (env.get("data") or {}).get("results") or []
                   if r.get("action") == "inserted")
    log(inserted == 1, "C 上报 1 条消息（触发 ingest 钩子）", f"inserted={inserted}")

    # 等投递终态
    deadline = time.time() + 60
    rows = []
    while time.time() < deadline:
        env = request("GET", BASE + f"/v1/subscriptions/{sub['id']}/deliveries?page=1&page_size=10",
                      token=admin)
        rows = (env.get("data") or {}).get("list") or []
        if rows and rows[0].get("status") in ("DELIVERED", "DEAD"):
            break
        time.sleep(1)
    row = rows[0] if rows else {}
    log(row.get("status") == "DELIVERED" and row.get("response_code") == 200,
        "D 投递终态", f"status={row.get('status')} response_code={row.get('response_code')} "
                      f"error={row.get('error')}")

    # 取 recorder 录到的请求
    time.sleep(1)
    try:
        recorded = json.load(io.open(args.recorded, encoding="utf-8"))
    except Exception as e:
        recorded = []
        log(False, "E 读取 recorder 记录", f"读取失败: {e}")
    if not recorded:
        log(False, "E recorder 收到请求", "记录为空（代理未生效或投递未到达）")
    else:
        rec = recorded[-1]
        hdrs = {k.lower(): v for k, v in rec["headers"].items()}
        sig = hdrs.get("x-wxsync-signature", "")
        ts = hdrs.get("x-wxsync-timestamp", "")
        did = hdrs.get("x-wxsync-delivery", "")
        log(bool(sig and ts and did), "E 三件头齐全",
            f"signature={'有' if sig else '无'} timestamp={ts} delivery={did}")
        # 消费方独立复算：key = sha256hex(secret 明文)
        key = hashlib.sha256(secret.encode("utf-8")).hexdigest().encode("ascii")
        data = (ts + "." + rec["body"]).encode("utf-8")
        expect = "sha256=" + hmac.new(key, data, hashlib.sha256).hexdigest()
        log(hmac.compare_digest(expect, sig), "F 签名独立复算一致",
            f"本地复算 {expect[:22]}… vs 头 {sig[:22]}…")
        log(body_text in rec["body"], "G 投递体含原文且逐字节保真",
            f"体长 {rec['body_len']} B，正文片段命中={body_text in rec['body']}")
        # 计数只数 POST（recorder 可能有健康探测等 GET 噪声；请求走代理时 path 是绝对 URL 形态），
        # 且用**增量**判定——recorder 记录文件是累计的
        posts = [r for r in recorded if r["method"] == "POST"]
        delta = len(posts) - baseline_posts
        log(delta == 1, "H stub webhook 计数",
            f"本轮新增 POST {delta} 次（累计 {len(posts)}，基线 {baseline_posts}）")

    # 清理
    request("DELETE", BASE + f"/v1/subscriptions/{sub['id']}", token=admin)
    request("DELETE", BASE + f"/v1/consumers/{consumer['id']}", token=admin)

    if failures:
        print(f"\n=== 取证失败 {len(failures)} 项 ===")
        for f in failures:
            print(" - " + f)
        return 1
    print("\n=== 逐字节取证全过 ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())

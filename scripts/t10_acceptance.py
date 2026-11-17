#!/usr/bin/env python3
"""T10-9 订阅域真机验收脚本（SUBSCRIPTION_DESIGN §2~§8）。

只用标准库；不 import wechatauto；不碰本机微信库。全部流量打本地 8080 服务端。

覆盖：
  A 建消费者（明文 poll_token / webhook_secret 只此一次）
  B 建 POLL 订阅
  C 建 WEBHOOK 订阅（外网回声端点 httpbin——SSRF 必拦环回，故真机 WEBHOOK 走公网）
  D 负例：环回 webhook_url 必须被 10607 拦下（SSRF 字面量拦截）
  E 上报一批消息（真实 /v1/reports/messages 入口）→ 触发 ingest 钩子 → 匹配 → 入队
  F 重复上报同一批 → 不新增投递行（防重复订阅/防重双闸）
  G POLL 拉取 → ack → 终态 DELIVERED
  H WEBHOOK 派发 → DELIVERED + response_code=200
  I 性能：50 并发拉取 + 10 QPS 持续压（≥10 QPS 达标线）

退出码 0 = 全过；非 0 = 有失败项（明细见输出与 summary json）。
"""

import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

FAILURES = []
STEP_LOG = []


def log(step, ok, detail):
    line = f"[{'PASS' if ok else 'FAIL'}] {step}: {detail}"
    print(line, flush=True)
    STEP_LOG.append({"step": step, "ok": ok, "detail": detail})
    if not ok:
        FAILURES.append(f"{step}: {detail}")


def request(method, url, body=None, token=None, timeout=30):
    """返回 (http_status, parsed_json_or_None, raw_text)。业务错误一律 HTTP 200 + 信封。"""
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        status = e.code
    except Exception as e:  # 网络层异常也算失败证据
        return 0, None, f"{type(e).__name__}: {e}"
    try:
        parsed = json.loads(raw)
    except Exception:
        parsed = None
    return status, parsed, raw


def read_token(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read().strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--admin-token-file", default=".t9_admin_access.txt")
    ap.add_argument("--client-token-file", default=".t9_client_smoke.txt")
    ap.add_argument("--account", required=True)
    ap.add_argument("--webhook-url", default="https://httpbin.org/post")
    ap.add_argument("--messages", type=int, default=10)
    ap.add_argument("--summary-out", default="scripts/t10_acceptance_summary.json")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    admin = read_token(args.admin_token_file)
    client = read_token(args.client_token_file)

    stamp = int(time.time())
    chat = f"t10_accept_chat_{stamp}"
    consumer_name = f"t10_accept_consumer_{stamp}"
    suffix = f"t10_accept_{stamp}"
    # WEBHOOK 订阅独有的 keyword（区分订阅身份，见 uk_wx_subscription_filter 注释）；
    # 每条消息正文都含该标记 → WEBHOOK 订阅同样命中全部消息
    KEYWORD_MARK = "t10accept"

    summary = {"started_at": stamp, "chat": chat, "ids": {}}

    # ---------------- A 建消费者 ----------------
    status, env, raw = request("POST", base + "/v1/consumers", {
        "name": consumer_name,
        "enable_poll": True,
        "enable_webhook": True,
        "remark": "T10-9 真机验收临时消费者",
    }, token=admin)
    data = (env or {}).get("data") or {}
    consumer_id = data.get("id")
    poll_token = data.get("poll_token")
    webhook_secret = data.get("webhook_secret")
    ok = bool(status == 200 and (env or {}).get("code") == 0 and consumer_id and poll_token and webhook_secret)
    log("A 建消费者", ok, f"code={(env or {}).get('code')} consumer_id={consumer_id} "
                          f"poll_token={'有' if poll_token else '无'} webhook_secret={'有' if webhook_secret else '无'}")
    if not ok:
        print(raw, file=sys.stderr)
        emit(summary, args)
        return 1
    summary["ids"]["consumer_id"] = consumer_id

    # ---------------- B/C 建订阅 ----------------
    # 订阅身份 = (consumer_id, filter_hash)（uk_wx_subscription_filter，设计 §2.2 防重复订阅）：
    # 同一 consumer 下**相同 filter 只能有一棵订阅**（重复 POST 返回既有行 + already_exists）。
    # 故 POLL / WEBHOOK / SSRF 三棵订阅必须用**互不相同的 filter**（用 keyword 区分），
    # 否则 WEBHOOK 订阅会被幂等吞成 POLL 那一棵（webhook_url 丢失、投递形式退化为 POLL）。
    status, env, raw = request("POST", base + "/v1/subscriptions", {
        "consumer_id": consumer_id,
        "name": suffix + "_poll",
        "filter": {"chats": [chat]},
        "delivery_mode": "POLL",
        "visible_seconds": 60,
        "max_attempts": 3,
        "backoff_base_seconds": 5,
    }, token=admin)
    data = (env or {}).get("data") or {}
    sub = data.get("subscription") or {}
    sub_poll_id = sub.get("id")
    log("B 建 POLL 订阅", status == 200 and (env or {}).get("code") == 0 and sub_poll_id is not None
        and sub.get("delivery_mode") == "POLL",
        f"code={(env or {}).get('code')} sub_id={sub_poll_id} mode={sub.get('delivery_mode')} "
        f"filter={sub.get('filter')}")

    status, env, raw = request("POST", base + "/v1/subscriptions", {
        "consumer_id": consumer_id,
        "name": suffix + "_hook",
        "filter": {"chats": [chat], "keyword": KEYWORD_MARK},
        "delivery_mode": "WEBHOOK",
        "webhook_url": args.webhook_url,
        "visible_seconds": 60,
        "max_attempts": 3,
        "backoff_base_seconds": 5,
    }, token=admin)
    data = (env or {}).get("data") or {}
    sub = data.get("subscription") or {}
    sub_hook_id = sub.get("id")
    log("C 建 WEBHOOK 订阅", status == 200 and (env or {}).get("code") == 0 and sub_hook_id is not None
        and sub.get("delivery_mode") == "WEBHOOK" and sub.get("webhook_url") == args.webhook_url,
        f"code={(env or {}).get('code')} sub_id={sub_hook_id} mode={sub.get('delivery_mode')} "
        f"url={sub.get('webhook_url')}")

    summary["ids"]["sub_poll_id"] = sub_poll_id
    summary["ids"]["sub_hook_id"] = sub_hook_id

    # ---------------- D 负例：SSRF 环回必须被拦 ----------------
    status, env, raw = request("POST", base + "/v1/subscriptions", {
        "consumer_id": consumer_id,
        "name": suffix + "_ssrf",
        "filter": {"chats": [chat], "keyword": "ssrfprobe"},
        "delivery_mode": "WEBHOOK",
        "webhook_url": "http://127.0.0.1:9/ssrf-probe",
        "visible_seconds": 60,
        "max_attempts": 3,
        "backoff_base_seconds": 5,
    }, token=admin)
    code = (env or {}).get("code")
    log("D SSRF 环回拦截", code == 10607, f"code={code} msg={(env or {}).get('message')}")

    # ---------------- E 上报一批消息（真实 ingest 入口）----------------
    def build_messages():
        items = []
        for i in range(args.messages):
            body = f"{KEYWORD_MARK} t10 accept {stamp} #{i}"
            items.append({
                "shard": "t10acc.db",
                "local_id": i + 1,
                "server_id": str(9_000_000_000 + stamp * 100 + i),
                "sort_seq": (int(time.time()) + i) * 1000,
                "create_time_raw": int(time.time()) + i,
                "type_code": "1",
                "type_name": "TEXT",
                "sender_username": "t10_accept_sender",
                "body": body,
                "content_sha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
                "is_revoked": False,
            })
        return items

    messages = build_messages()
    report = {
        "account": args.account,
        "batch_ref": suffix + "_batch1",
        "chats": [{"chat_username": chat, "messages": messages}],
    }
    status, env, raw = request("POST", base + "/v1/reports/messages", report, token=client)
    data = (env or {}).get("data") or {}
    results = data.get("results") or []
    # 结果码字段是 action（契约三值 inserted|merged|unchanged），不是布尔 inserted
    inserted = sum(1 for r in results if r.get("action") == "inserted")
    log("E 上报消息（ingest 触发订阅）", status == 200 and (env or {}).get("code") == 0 and inserted == args.messages,
        f"code={(env or {}).get('code')} msg={(env or {}).get('message')} "
        f"results={len(results)} inserted={inserted}/{args.messages}")
    if (env or {}).get("code") == 10201:
        print("  ↳ client 令牌已失效：请先跑 `bash scripts/t10_deploy_accept.sh mint-client`", file=sys.stderr)

    # ---------------- F 重复上报：不得新增投递行 ----------------
    def delivery_count(sub_id):
        st, ev, rw = request("GET", base + f"/v1/subscriptions/{sub_id}/deliveries?page=1&page_size=100", token=admin)
        d = (ev or {}).get("data") or {}
        return d.get("total")

    poll_total_before = delivery_count(sub_poll_id)
    hook_total_before = delivery_count(sub_hook_id)

    report2 = dict(report)
    report2["batch_ref"] = suffix + "_batch2"
    status, env, raw = request("POST", base + "/v1/reports/messages", report2, token=client)
    data = (env or {}).get("data") or {}
    results2 = data.get("results") or []
    inserted2 = sum(1 for r in results2 if r.get("action") == "inserted")
    time.sleep(1.5)
    poll_total_after = delivery_count(sub_poll_id)
    hook_total_after = delivery_count(sub_hook_id)
    log("F 重复上报不新增投递", inserted2 == 0 and poll_total_after == poll_total_before
        and hook_total_after == hook_total_before,
        f"二次 inserted={inserted2} poll {poll_total_before}->{poll_total_after} hook {hook_total_before}->{hook_total_after}")

    # ---------------- G POLL 拉取 + ack ----------------
    deadline = time.time() + 30
    pulled = []
    while time.time() < deadline:
        st, ev, rw = request("GET", base + f"/v1/subscriptions/{sub_poll_id}/messages?max=50", token=poll_token)
        d = (ev or {}).get("data") or {}
        pulled = d.get("messages") or []
        if len(pulled) >= args.messages:
            break
        time.sleep(0.5)
    log("G1 POLL 拉取", len(pulled) == args.messages,
        f"拉到 {len(pulled)}/{args.messages} 条（visibility_seconds={(ev or {}).get('data', {}).get('visibility_seconds')}）")

    ack_items = [{"delivery_id": it.get("delivery_id"), "lease_id": it.get("lease_id")} for it in pulled]
    st, ev, rw = request("POST", base + f"/v1/subscriptions/{sub_poll_id}/ack",
                          {"items": ack_items}, token=poll_token)
    d = (ev or {}).get("data") or []
    ok_count = sum(1 for r in d if r.get("success"))
    log("G2 POLL ack", (ev or {}).get("code") == 0 and ok_count == len(ack_items),
        f"code={(ev or {}).get('code')} 成功 {ok_count}/{len(ack_items)}")

    # ---------------- H WEBHOOK 派发终态 ----------------
    deadline = time.time() + 60
    hook_rows = []
    while time.time() < deadline:
        st, ev, rw = request("GET", base + f"/v1/subscriptions/{sub_hook_id}/deliveries?page=1&page_size=100",
                             token=admin)
        d = (ev or {}).get("data") or {}
        rows = (d.get("list") or d.get("records") or [])
        hook_rows = rows
        if rows and all(r.get("status") == "DELIVERED" for r in rows) and len(rows) >= args.messages:
            break
        time.sleep(1.0)
    delivered = [r for r in hook_rows if r.get("status") == "DELIVERED"]
    codes = {r.get("response_code") for r in delivered}
    log("H WEBHOOK 派发", len(delivered) == args.messages and codes == {200},
        f"DELIVERED={len(delivered)}/{args.messages} response_code={sorted(codes, key=str)} "
        f"其它状态={[r.get('status') for r in hook_rows if r.get('status') != 'DELIVERED'][:3]}")

    # ---------------- I 性能：50 并发 + 10 QPS 持续 ----------------
    def one_pull(_):
        st, ev, _rw = request("GET", base + f"/v1/subscriptions/{sub_poll_id}/messages?max=50", token=poll_token)
        return (st, (ev or {}).get("code"))

    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:
        res = list(pool.map(one_pull, range(50)))
    elapsed = time.time() - t0
    qps = 50 / elapsed if elapsed > 0 else 0
    bad = [r for r in res if r[0] != 200 or r[1] != 0]
    log("I1 50 并发拉取", not bad and qps >= 10,
        f"耗时 {elapsed:.2f}s → {qps:.1f} QPS；非零信封 {len(bad)} 条")

    t0 = time.time()
    sent = 0
    errs = 0
    codes_seen = set()
    while time.time() - t0 < 5.0:
        target = t0 + sent / 10.0
        now = time.time()
        if target > now:
            time.sleep(target - now)
        st, code = one_pull(sent)
        codes_seen.add(code)
        if st != 200 or code != 0:
            errs += 1
        sent += 1
    sustained = sent / (time.time() - t0)
    log("I2 10 QPS 持续 5 秒", errs == 0 and sustained >= 10,
        f"发出 {sent} 请求 / {time.time() - t0:.2f}s = {sustained:.1f} QPS，失败 {errs}，信封码 {sorted(codes_seen, key=str)}")

    # ---------------- 清理（软删；物理清理由 driver 脚本执行）----------------
    for sid in (sub_poll_id, sub_hook_id):
        request("DELETE", base + f"/v1/subscriptions/{sid}", token=admin)
    request("DELETE", base + f"/v1/consumers/{consumer_id}", token=admin)

    summary["results"] = STEP_LOG
    summary["failed"] = FAILURES
    emit(summary, args)
    return 1 if FAILURES else 0


def emit(summary, args):
    path = args.summary_out
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)
        print(f"\n--- summary 已写 {path} ---")
    except Exception as e:
        print(f"summary 写入失败: {e}", file=sys.stderr)
    if FAILURES:
        print(f"\n=== 验收失败 {len(FAILURES)} 项 ===")
        for f in FAILURES:
            print(" - " + f)
    else:
        print("\n=== 验收全过 ===")


if __name__ == "__main__":
    sys.exit(main())

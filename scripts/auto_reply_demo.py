#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""auto_reply_demo.py —— 「收到消息 '1' → 自动回复 '好的'」DEMO

链路（全部复用已验收的积木，服务端/客户端零改动）：
  微信收到 "1"
    → wxbot-helper -sync 上报 → 服务端 wx_message + 订阅引擎（filter keyword="1"）
    → 本脚本 POLL 拉取投递 → 精确判定 body=="1"（订阅 keyword 是子串粗筛，这里才精确）
    → admin POST /v1/tasks 提交 SEND_MESSAGE 任务（chat=来源会话, text="好的"）
    → wxbot-helper hybrid 认领 → OCR 真发 → 任务 SUCCEEDED（本脚本轮询状态并打印回执）

用法（venv python：/c/workspace/wechatauto-replica/.venv/Scripts/python.exe）：
  python auto_reply_demo.py setup        # 建消费者 + POLL 订阅（幂等），凭据落 .t11_bot_state.json
  python auto_reply_demo.py run          # 常驻：拉取 → 判定 → 提交任务 → 追任务状态 → ack
  python auto_reply_demo.py once         # 单轮拉取（调试用）

防环分析：触发词 "1" 与回复 "好的" 互不匹配，回复被上报后不再命中 → 链路天然终止；
同会话在途回复由 uk_wx_task_active_target 唯一约束兜底（提交撞约束 → 跳过并 ack）。
"""

import argparse
import json
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent

# 消息类型码：1 = 文本（WeChat msg type 口径）
TYPE_TEXT = 1


def log(tag, msg):
    print(f"{time.strftime('%H:%M:%S')} [{tag}] {msg}", flush=True)


def out_utf8():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


# ---------------------------------------------------------------- HTTP

class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.http = httpx.Client(timeout=15)

    def call(self, method, path, body=None, token=None, bearer=False):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}" if bearer else token
        r = self.http.request(method, self.base + path, json=body, headers=headers)
        env = r.json()
        return env.get("code"), env.get("data"), env


# ---------------------------------------------------------------- admin 令牌（2h TTL，过期自动轮换）

class AdminAuth:
    """access 过期（10200/10201）→ 用 refresh 一次性轮换拿新对并落盘。

    注意：refresh 是一次性凭据（复用触发服务端重用检测 = 整族撤销），所以只在
    access 确认失效后才轮换，且轮换后立刻把新对写回文件。
    """

    RETRY_CODES = {10200, 10201}

    def __init__(self, client, access_file, refresh_file):
        self.client = client
        self.access_file = Path(access_file)
        self.refresh_file = Path(refresh_file)

    def _read(self, p):
        return p.read_text(encoding="utf-8").strip()

    def _save(self, access, refresh):
        self.access_file.write_text(access, encoding="utf-8")
        self.refresh_file.write_text(refresh, encoding="utf-8")

    def _rotate(self):
        refresh = self._read(self.refresh_file)
        code, data, env = self.client.call(
            "POST", "/v1/auth/refresh", {"refresh_token": refresh}, token=refresh, bearer=True)
        if code != 0 or not data:
            raise SystemExit(f"[admin] refresh 轮换失败 code={code} env={json.dumps(env, ensure_ascii=False)[:200]}"
                             f"—— refresh 可能已被消费/整族撤销，请重新 bootstrap 签发")
        self._save(data["access_token"], data["refresh_token"])
        log("admin", "access 已过期，refresh 轮换成功，新令牌对已落盘")

    def call(self, method, path, body=None):
        token = self._read(self.access_file)
        code, data, env = self.client.call(method, path, body, token=token, bearer=True)
        if code in self.RETRY_CODES:
            self._rotate()
            code, data, env = self.client.call(
                method, path, body, token=self._read(self.access_file), bearer=True)
        return code, data, env


# ---------------------------------------------------------------- setup：建消费者 + POLL 订阅

def cmd_setup(a):
    client = Client(a.base)
    admin = AdminAuth(client, a.admin_access, a.admin_refresh)

    consumer_name = f"auto_reply_demo_{int(time.time())}"
    code, data, env = admin.call("POST", "/v1/consumers", {"name": consumer_name, "enable_poll": True})
    if code != 0 or not (data or {}).get("poll_token"):
        raise SystemExit(f"[setup] 建消费者失败 code={code} {json.dumps(env, ensure_ascii=False)[:300]}")
    consumer_id, poll_token = data["id"], data["poll_token"]
    log("setup", f"消费者 {consumer_name} id={consumer_id} poll_token=已落盘(明文仅此一次)")

    # 订阅身份 = (consumer_id, filter_hash)：keyword="1" 做服务端粗筛（子串匹配），
    # 精确判定在本脚本（body=="1"），"21"/"a1" 会被拉到但被这里过滤。
    code, data, env = admin.call("POST", "/v1/subscriptions", {
        "consumer_id": consumer_id,
        "name": "auto_reply_1_to_ok",
        "filter": {"keyword": a.match},
        "delivery_mode": "POLL",
        "visible_seconds": 60,
        "max_attempts": 3,
        "backoff_base_seconds": 5,
    })
    sub = (data or {}).get("subscription") or {}
    if code != 0 or not sub.get("id"):
        raise SystemExit(f"[setup] 建订阅失败 code={code} {json.dumps(env, ensure_ascii=False)[:300]}")
    log("setup", f"POLL 订阅 id={sub['id']} filter={sub.get('filter')} mode={sub.get('delivery_mode')}")

    state = {"consumer_id": consumer_id, "subscription_id": sub["id"], "poll_token": poll_token}
    Path(a.state_file).write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    log("setup", f"凭据已写 {a.state_file}，下一步: python auto_reply_demo.py run")


# ---------------------------------------------------------------- 主循环

SUBMIT_CONFLICT_CODES = {10106}   # 数据约束冲突 ≈ 同会话已有在途 SEND_MESSAGE（active_target 唯一）


def submit_reply_task(admin, account, chat, match, reply, watched):
    payload = json.dumps({"chat_username": chat, "text": reply}, ensure_ascii=False)
    code, data, env = admin.call("POST", "/v1/tasks", {
        "task_type": "SEND_MESSAGE", "account": account, "payload": payload})
    if code == 0:
        task_id = data
        watched[task_id] = {"status": None}
        log("提交", f"命中 '{match}' → 建任务 id={task_id} chat={chat} text='{reply}'")
        return "ack"
    if code in SUBMIT_CONFLICT_CODES:
        log("跳过", f"chat={chat} 已有在途回复任务（active_target 唯一约束），本条放弃 code={code}")
        return "ack"
    log("失败", f"建任务失败 chat={chat} code={code} {json.dumps(env, ensure_ascii=False)[:200]} → nack 重试")
    return "nack"


def watch_tasks(admin, account, watched):
    """追已提交任务的状态迁移；终态时拉 task-run 打印回执。"""
    if not watched:
        return
    code, data, _ = admin.call("GET", f"/v1/tasks?page=1&page_size=100&account={account}")
    # 键统一 str：create 返回的 task id 是字符串，list 行 id 可能字符串/数字混型
    rows = {str(r["id"]): r for r in ((data or {}).get("list") or [])} if code == 0 else {}
    for tid in list(watched):
        row = rows.get(tid)
        if not row:
            continue
        st = row.get("status")
        if st != watched[tid]["status"]:
            log("任务", f"id={tid} {watched[tid]['status'] or '∅'} → {st}")
            watched[tid]["status"] = st
        if st in ("SUCCEEDED", "FAILED", "DEAD", "CANCELLED"):
            rc, rd, _ = admin.call("GET", f"/v1/task-runs?task_id={tid}&page=1&page_size=1")
            runs = (rd or {}).get("list") or []
            result = runs[0].get("result") if runs else None
            log("回执", f"任务 id={tid} 终态 {st} result={json.dumps(result, ensure_ascii=False)}")
            watched.pop(tid)


def poll_round(client, admin, a, watched):
    """一轮：拉取 → 逐条判定 → 批量 ack/nack → 追任务。返回本轮拉到条数。"""
    state = json.loads(Path(a.state_file).read_text(encoding="utf-8"))
    code, data, env = client.call(
        "GET", f"/v1/subscriptions/{state['subscription_id']}/messages?max=50",
        token=state["poll_token"], bearer=True)
    if code != 0:
        log("拉取", f"失败 code={code} {json.dumps(env, ensure_ascii=False)[:200]}")
        return -1
    items = (data or {}).get("messages") or []
    acks, nacks = [], []
    for it in items:
        m = it.get("message") or {}
        chat = m.get("chat_username")
        sender = m.get("sender_username")
        body = (m.get("body") or "").strip()
        log("收到", f"chat={chat} sender={sender} type={m.get('type_code')} attempt={it.get('attempt')} "
                   f"body={body[:40]!r}")

        # 注意：投递载荷 type_code 是字符串（Java DeliveryMessageVO.typeCode 为 String）
        if str(m.get("type_code")) == str(TYPE_TEXT) and body == a.match:
            action = submit_reply_task(admin, a.account, chat, a.match, a.reply, watched)
        else:
            if body == a.match:
                log("跳过", f"非文本消息（type={m.get('type_code')}），不触发")
            action = "ack"
        (acks if action == "ack" else nacks).append(
            {"delivery_id": it.get("delivery_id"), "lease_id": it.get("lease_id")})

    for verb, items_ in (("ack", acks), ("nack", nacks)):
        if items_:
            c, d, _ = client.call("POST", f"/v1/subscriptions/{state['subscription_id']}/{verb}",
                                  {"items": items_}, token=state["poll_token"], bearer=True)
            log(verb, f"{len(items_)} 条 → code={c}" + ("（10700 部分失败见 data）" if c == 10700 else ""))
    return len(items)


def cmd_run(a, once=False):
    if a.reply.strip() == a.match.strip():
        raise SystemExit(f"回复 '{a.reply}' 与触发词 '{a.match}' 相同 —— 会自我循环，拒绝启动")
    client = Client(a.base)
    admin = AdminAuth(client, a.admin_access, a.admin_refresh)
    watched = {}
    log("运行", f"base={a.base} account={a.account} 规则: '{a.match}' → '{a.reply}' 轮询={a.interval}s")
    while True:
        n = poll_round(client, admin, a, watched)
        watch_tasks(admin, a.account, watched)
        if once:
            break
        if n < 0:
            log("运行", f"拉取异常，{a.interval}s 后重试")
        time.sleep(a.interval)


def main():
    out_utf8()
    p = argparse.ArgumentParser(description="收到 '1' 自动回复 '好的' DEMO")
    p.add_argument("cmd", choices=["setup", "run", "once"])
    p.add_argument("--base", default="http://127.0.0.1:8080")
    p.add_argument("--account", default="wxid_c00pwixax7xv22_8316")
    p.add_argument("--match", default="1", help="触发词（精确匹配文本消息 body）")
    p.add_argument("--reply", default="好的", help="回复内容（不得与触发词相同，防自环）")
    p.add_argument("--interval", type=float, default=3.0, help="拉取轮询间隔秒")
    p.add_argument("--admin-access", default=str(ROOT / ".t11_admin_access.txt"))
    p.add_argument("--admin-refresh", default=str(ROOT / ".t11_admin_refresh.txt"))
    p.add_argument("--state-file", default=str(ROOT / ".t11_bot_state.json"))
    a = p.parse_args()
    (cmd_setup if a.cmd == "setup" else lambda x: cmd_run(x, once=(a.cmd == "once")))(a)


if __name__ == "__main__":
    main()

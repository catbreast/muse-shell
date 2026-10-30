#!/usr/bin/env python3
"""Muse 壳 root agent（0.8.0）：跑在手机 Debian 容器内的独立执行面。

与 App（BridgeService）分工：本 agent 只执行系统类工具
（shell / shell.check / privilege.status / linux.exec / linux.status），
进程独立于 App——App 被 ColorOS 冻结/杀死时，系统工具照常可用。

可靠性语义（与中继 0.8.0 协议配套）：
- 结果先落盘（outbox/<id>.json）再发送；收到中继 result_ack 才删；
  没确认就重发（重连时先补发全部未确认结果）。中继对同 id 结果幂等。
- 命令按 id 幂等：已执行过的 id 直接回缓存结果，不重复执行。
- WS 断线指数退避重连；hello/ping 带 tele 遥测（待发箱深度等）。

配置：同目录 agent.json {base, token, device}（600 权限，不入仓）。
"""
import json
import os
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
OUTBOX = os.path.join(HERE, "outbox")
EXECUTED = os.path.join(HERE, "executed.json")
LOG = os.path.join(HERE, "agent.log")
MAX_EXECUTED = 300

os.makedirs(OUTBOX, exist_ok=True)


# Force IPv4: this phone's IPv6 route to Cloudflare blackholes the same
# way the PC's did (TCP/TLS stalls while IPv4 is fine). The PC agent got
# the same cure (forced tcp4); without it the agent's TLS handshakes to
# the relay time out in bursts while the Android app connects fine.
import socket as _sock
_orig_gai = _sock.getaddrinfo
def _gai4(host, port, family=0, *a, **kw):
    return _orig_gai(host, port, _sock.AF_INET, *a, **kw)
_sock.getaddrinfo = _gai4

def log(msg):
    line = time.strftime("%F %T") + " " + str(msg)
    try:
        with open(LOG, "a") as f:
            f.write(line + "\n")
    except Exception:
        pass
    print(line, flush=True)


def load_config():
    with open(os.path.join(HERE, "agent.json")) as f:
        cfg = json.load(f)
    cfg.setdefault("base", "https://phone.ruisnow.de")
    return cfg


CFG = load_config()

# ---------------- 幂等账本 ----------------
_exec_lock = threading.Lock()
try:
    with open(EXECUTED) as f:
        _executed = json.load(f)  # {id: ts}
except Exception:
    _executed = {}


def mark_executed(cid):
    with _exec_lock:
        _executed[cid] = time.time()
        if len(_executed) > MAX_EXECUTED:
            for k in sorted(_executed, key=_executed.get)[: len(_executed) - MAX_EXECUTED]:
                del _executed[k]
        tmp = EXECUTED + ".tmp"
        with open(tmp, "w") as f:
            json.dump(_executed, f)
        os.replace(tmp, EXECUTED)


def was_executed(cid):
    with _exec_lock:
        return cid in _executed


# ---------------- 待发箱 ----------------
def outbox_put(result):
    tmp = os.path.join(OUTBOX, result["id"] + ".json.tmp")
    dst = os.path.join(OUTBOX, result["id"] + ".json")
    with open(tmp, "w") as f:
        json.dump(result, f)
    os.replace(tmp, dst)


def outbox_get(cid):
    try:
        with open(os.path.join(OUTBOX, cid + ".json")) as f:
            return json.load(f)
    except Exception:
        return None


def outbox_ack(cid):
    try:
        os.remove(os.path.join(OUTBOX, cid + ".json"))
    except Exception:
        pass


def outbox_pending():
    try:
        return [f[:-5] for f in os.listdir(OUTBOX) if f.endswith(".json")]
    except Exception:
        return []


# ---------------- 工具执行 ----------------
def cap(s, n):
    if s is None:
        return ""
    if len(s) > n:
        return s[:n] + "\n…[truncated]"
    return s


def run_host_sh(cmd, timeout):
    """在 Android 宿主侧执行命令（容器与宿主同 pid/mount 视图时直接
    /system/bin/sh；否则经 nsenter+chroot /proc/1/root）。"""
    if os.path.exists("/system/bin/sh"):
        argv = ["/system/bin/sh", "-c", cmd]
    else:
        argv = ["nsenter", "-t", "1", "-m", "chroot", "/proc/1/root",
                "/system/bin/sh", "-c", cmd]
    p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def tool_shell(args):
    cmd = args.get("cmd", "")
    timeout = max(1, min(int(args.get("timeout", 120)), 600))
    try:
        rc, out, err = run_host_sh(cmd, timeout)
    except subprocess.TimeoutExpired:
        raise Exception(f"shell_timeout_{timeout}s")
    return {"exit": rc, "stdout": cap(out, 6000), "stderr": cap(err, 6000),
            "via": "root-agent"}


def tool_shell_check(args):
    try:
        rc, out, err = run_host_sh("id", 10)
        root = rc == 0 and "uid=0" in out
    except Exception as e:
        return {"root": False, "channel": "none", "via": "root-agent",
                "detail": str(e)[:200]}
    return {"root": root, "channel": "su" if root else "none",
            "via": "root-agent", "detail": out.strip()[:200]}


def tool_linux_exec(args):
    cmd = args.get("cmd", "")
    timeout = max(1, min(int(args.get("timeout", 60)), 600))
    try:
        p = subprocess.run(["/bin/bash", "-lc", cmd], capture_output=True,
                           text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Exception(f"shell_timeout_{timeout}s")
    return {"exit": p.returncode, "stdout": cap(p.stdout, 4500),
            "stderr": cap(p.stderr, 4500), "via": "root-agent"}


def tool_linux_status(args):
    pretty = ""
    try:
        with open("/etc/os-release") as f:
            for line in f:
                if line.startswith("PRETTY_NAME="):
                    pretty = line.strip().split("=", 1)[1].strip('"')
    except Exception:
        pass
    return {"installed": True, "detail": (pretty + " (in-container, root-agent)").strip(),
            "via": "root-agent"}


TOOLS = {
    "shell": tool_shell,
    "shell.check": tool_shell_check,
    "privilege.status": tool_shell_check,
    "linux.exec": tool_linux_exec,
    "linux.status": tool_linux_status,
}


_INFLIGHT = set()
_INFLIGHT_LOCK = threading.Lock()

def execute(cmd, send_result):
    cid = cmd.get("id")
    tool = cmd.get("tool", "")
    args = cmd.get("args") or {}
    if was_executed(cid):
        cached = outbox_get(cid)
        if cached:
            log(f"dup {cid} {tool}: resend cached result")
            send_result(cached)
            return
    # In-flight dedup: the relay redelivers when a delivery socket dies
    # mid-execution — without this guard the redelivery runs CONCURRENTLY
    # and side effects double (live-proven on the app side, 2026-10-31).
    with _INFLIGHT_LOCK:
        if cid in _INFLIGHT:
            log(f"dup {cid} {tool}: already in flight, dropped")
            return
        _INFLIGHT.add(cid)
    try:
        _execute_inner(cid, tool, args, send_result)
    finally:
        with _INFLIGHT_LOCK:
            _INFLIGHT.discard(cid)

def _execute_inner(cid, tool, args, send_result):
    fn = TOOLS.get(tool)
    try:
        if fn is None:
            raise Exception("tool_not_for_root_agent: " + tool)
        result = {"id": cid, "ok": True, "result": fn(args)}
    except Exception as e:
        result = {"id": cid, "ok": False, "error": str(e)[:500]}
    outbox_put(result)
    mark_executed(cid)
    send_result(result)


# ---------------- WS 主循环 ----------------
def tele():
    return {"agent": "root", "outbox": len(outbox_pending()),
            "ts": int(time.time() * 1000)}


def main():
    import websocket  # websocket-client

    pool = ThreadPoolExecutor(max_workers=4)
    ws_ref = {"ws": None}
    send_lock = threading.Lock()

    def send_result(result):
        payload = json.dumps({"type": "result", "result": result})
        with send_lock:
            ws = ws_ref["ws"]
            if ws is None:
                return
            try:
                ws.send(payload)
            except Exception:
                pass

    def flush_outbox():
        for cid in outbox_pending():
            r = outbox_get(cid)
            if r:
                send_result(r)

    backoff = 2
    while True:
        try:
            url = CFG["base"].replace("https://", "wss://").replace("http://", "ws://") + "/ws"
            ws = websocket.create_connection(
                url, timeout=30,
                header=[f"X-Token: {CFG['token']}",
                        f"X-Device: {CFG['device']}",
                        "X-Role: root"])
            ws_ref["ws"] = ws
            backoff = 2
            ws.send(json.dumps({"type": "hello", "device": CFG["device"],
                                "role": "root", "tele": tele()}))
            log("connected, flushing outbox")
            flush_outbox()
            last_ping = time.time()
            ws.settimeout(5)
            while True:
                try:
                    raw = ws.recv()
                except websocket.WebSocketTimeoutException:
                    raw = None
                if raw:
                    try:
                        obj = json.loads(raw)
                    except Exception:
                        continue
                    if obj.get("type") == "result_ack":
                        outbox_ack(obj.get("id", ""))
                    elif obj.get("id") and obj.get("tool"):
                        # Receipt ack FIRST: the relay requeues unacked
                        # commands — without it a frame lost in a flap (or
                        # an executor thread stuck on a hung subprocess)
                        # strands the command with both ends looking fine.
                        try:
                            ws.send(json.dumps({"type": "cmd_ack", "id": obj["id"]}))
                        except Exception:
                            pass
                        pool.submit(execute, obj, send_result)
                if time.time() - last_ping > 25:
                    last_ping = time.time()
                    ws.send(json.dumps({"type": "ping", "tele": tele()}))
        except Exception as e:
            log(f"conn error: {str(e)[:160]}; retry in {backoff}s")
            ws_ref["ws"] = None
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)


if __name__ == "__main__":
    main()

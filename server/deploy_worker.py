#!/usr/bin/env python3
"""把 Muse 壳中继 Worker 部署到 Cloudflare（经 custom.cloudflare 连接器 surrogate）。

用法：
  deploy_worker.py deploy     上传 worker.js + DO 绑定/迁移 + 双令牌 Secret，开 workers.dev
  deploy_worker.py route-add  给 phone.ruisnow.de 加 Worker 路由（切换到边缘中继）
  deploy_worker.py route-del  删除该路由（回滚到隧道+VM 中继）
令牌从同目录 tokens.json 读，只进请求体，绝不打印。
"""
import json
import os
import sys
import urllib.request
import urllib.error
import uuid

for p in ("/opt/muse/skills/custom-connector-setup/bin",
          "/opt/muse/skills/custom-connector-setup/templates/skill/bin"):
    if os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)
from dynamic_credentials import add_surrogate_to_request, read_json_response

HERE = os.path.dirname(os.path.abspath(__file__))
ACCOUNT = "62dd5f65ea2c5fb76b04aed4f3ef0be8"
ZONE = "c895d2a235859ac7e7366d34144052f3"
SCRIPT = "phone-relay"
HOSTS = ["api.cloudflare.com"]
BASE = "https://api.cloudflare.com/client/v4"


def call(method, path, body=None, raw_ctype=None):
    data = None
    if body is not None:
        data = body if isinstance(body, (bytes, bytearray)) else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if raw_ctype:
        req.add_header("Content-Type", raw_ctype)
    elif body is not None:
        req.add_header("Content-Type", "application/json")
    add_surrogate_to_request(req, "custom.cloudflare", allowed_hosts=HOSTS)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return read_json_response(resp)
    except urllib.error.HTTPError as e:
        try:
            return read_json_response(e)
        except Exception:
            return {"success": False, "errors": [{"message": f"HTTP {e.code}"}]}


def multipart(metadata, filename, content):
    boundary = "----muse" + uuid.uuid4().hex
    parts = []
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
                 f"Content-Type: application/json\r\n\r\n".encode())
    parts.append(json.dumps(metadata).encode())
    parts.append(b"\r\n")
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{filename}\"; "
                 f"filename=\"{filename}\"\r\nContent-Type: application/javascript+module\r\n\r\n".encode())
    parts.append(content)
    parts.append(b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def deploy():
    tokens = json.load(open(os.path.join(HERE, "tokens.json")))
    code = open(os.path.join(HERE, "worker.js"), "rb").read()
    metadata = {
        "main_module": "worker.js",
        "compatibility_date": "2026-10-01",
        "bindings": [
            {"type": "durable_object_namespace", "name": "RELAY", "class_name": "Relay"},
            {"type": "secret_text", "name": "PHONE_TOKEN", "text": tokens["phone"]},
            {"type": "secret_text", "name": "ADMIN_TOKEN", "text": tokens["admin"]},
        ],
        "migrations": [{"tag": "v1", "new_sqlite_classes": ["Relay"]}],
    }
    body, ctype = multipart(metadata, "worker.js", code)
    out = call("PUT", f"/accounts/{ACCOUNT}/workers/scripts/{SCRIPT}", body, ctype)
    if not out.get("success"):
        # 迁移已应用过时会报错，去掉 migrations 重试
        metadata.pop("migrations", None)
        body, ctype = multipart(metadata, "worker.js", code)
        out = call("PUT", f"/accounts/{ACCOUNT}/workers/scripts/{SCRIPT}", body, ctype)
    print("upload:", "OK" if out.get("success") else json.dumps(out.get("errors"), ensure_ascii=False))
    if not out.get("success"):
        return 1
    sub = call("PUT", f"/accounts/{ACCOUNT}/workers/scripts/{SCRIPT}/subdomain", {"enabled": True})
    print("workers.dev:", "OK" if sub.get("success") else json.dumps(sub.get("errors"), ensure_ascii=False))
    acct = call("GET", f"/accounts/{ACCOUNT}/workers/subdomain")
    name = (acct.get("result") or {}).get("subdomain")
    if name:
        print("URL: https://%s.%s.workers.dev" % (SCRIPT, name))
    return 0


def route_add():
    out = call("POST", f"/zones/{ZONE}/workers/routes",
               {"pattern": "phone.ruisnow.de/*", "script": SCRIPT})
    print("route-add:", "OK" if out.get("success") else json.dumps(out.get("errors"), ensure_ascii=False))
    if out.get("success"):
        print("route id:", (out.get("result") or {}).get("id"))
    return 0 if out.get("success") else 1


def route_del():
    lst = call("GET", f"/zones/{ZONE}/workers/routes")
    rid = None
    for r in (lst.get("result") or []):
        if r.get("pattern") == "phone.ruisnow.de/*":
            rid = r.get("id")
    if not rid:
        print("route-del: no such route")
        return 0
    out = call("DELETE", f"/zones/{ZONE}/workers/routes/{rid}")
    print("route-del:", "OK" if out.get("success") else json.dumps(out.get("errors"), ensure_ascii=False))
    return 0 if out.get("success") else 1


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "deploy":
        sys.exit(deploy())
    elif cmd == "route-add":
        sys.exit(route_add())
    elif cmd == "route-del":
        sys.exit(route_del())
    print(__doc__)
    sys.exit(2)

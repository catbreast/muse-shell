// Muse 壳中继（Cloudflare Worker + Durable Object 版）
// 协议与原 VM 上的 server.py 完全一致：
//   GET  /poll   (phone 令牌) 长轮询取命令，25s 无命令返回 204；
//                带 ?n=k（k≥2，新版壳）时一次最多返回 k 条 {cmds:[...]}
//   POST /result (phone 令牌) 回填结果
//   POST /call   (admin 令牌) 入队并等待结果；单次 HTTP 最多等 wait 秒(≤55)，
//                超时返回 202 {pending:true,id}，客户端续用 /wait 取
//   POST /wait   (admin 令牌) {id, wait} 等某条结果
//   POST /batch  (admin 令牌) {calls:[{id?,tool,args}], wait} 一次入队多条并等齐；
//                超时 202 {pending:true, results:{id:result}, pending_ids:[...]}
//   POST /batchwait (admin 令牌) {ids:[...], wait} 续等一批未回的结果
//   GET  /status (admin 令牌) {device,last_seen_ago_sec,queued,last_tool}
// 单个 DO 实例串行处理全部状态，无竞态；队列持久化，进程被回收不丢命令。

function json(obj, code = 200) {
  return new Response(JSON.stringify(obj), {
    status: code,
    headers: { "Content-Type": "application/json" },
  });
}

// 0.8.0 角色路由：系统类工具（shell / linux.*）由手机容器内的 root agent
// 直执（独立进程，不受 App 被 ColorOS 冻结牵连）；其余工具归 App。
// 命令只投递给同角色的连接；无同角色连接时落持久队列，绝不串投——
// 串投给被冻结的执行者等于丢命令（旧协议的教训）。
const ROOT_TOOL_RE = /^(shell|linux\.)/;
function roleFor(tool, explicit) {
  if (explicit === "root" || explicit === "app") return explicit;
  return ROOT_TOOL_RE.test(tool || "") ? "root" : "app";
}

export class Relay {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.queue = [];               // [{id, tool, args}]（持久化）
    this.results = new Map();      // id -> result（内存，调用方在途时有效）
    this.pollWaiters = [];         // [resolve(cmd|null)]
    this.resultWaiters = new Map();// id -> [resolve(result|null)]
    this.state = { device: null, last_seen: 0, last_tool: null };
    this.inbox = {};               // id -> {id,text,roomId,recordId,sessionId,ts,answer,answered_ts}（持久化）
    this.loaded = false;
  }

  async load() {
    if (this.loaded) return;
    this.loaded = true;
    try {
      const q = await this.ctx.storage.get("queue");
      if (Array.isArray(q)) this.queue = q;
      const s = await this.ctx.storage.get("state");
      if (s && typeof s === "object") this.state = s;
      const ib = await this.ctx.storage.get("inbox");
      if (ib && typeof ib === "object") this.inbox = ib;
    } catch (e) { /* 存储不可用时退化为纯内存 */ }
  }

  async saveInbox() {
    try { await this.ctx.storage.put("inbox", this.inbox); } catch (e) {}
  }

  pruneInbox() {
    // 小布收件箱是短时通道：超过 30 分钟的条目（无论答没答）直接丢弃，并封顶 20 条
    const cutoff = Date.now() - 30 * 60 * 1000;
    let changed = false;
    for (const id of Object.keys(this.inbox)) {
      if ((this.inbox[id].ts || 0) < cutoff) { delete this.inbox[id]; changed = true; }
    }
    const ids = Object.keys(this.inbox).sort((a, b) => (this.inbox[a].ts || 0) - (this.inbox[b].ts || 0));
    while (ids.length > 20) { delete this.inbox[ids.shift()]; changed = true; }
    if (changed) this.saveInbox();
  }

  async saveQueue() {
    try { await this.ctx.storage.put("queue", this.queue); } catch (e) {}
  }

  async saveState() {
    try { await this.ctx.storage.put("state", this.state); } catch (e) {}
  }

  touch(req) {
    const dev = req.headers.get("X-Device");
    if (dev) this.state.device = dev;
    this.state.last_seen = Date.now();
    this.saveState();
  }

  // ---- WebSocket Hibernation（2026-10-27 改造）----
  // 壳改走 WS 后 DO 空闲即休眠，duration 计费趋近于零；HTTP 长轮询保留为回退，
  // 旧版壳在用户点装新版前继续走 /poll，双协议并存不断链。
  agentSockets() {
    try { return this.ctx.getWebSockets(); } catch (e) { return []; }
  }

  socketRole(ws) {
    try { return (ws.deserializeAttachment() || {}).role || "app"; } catch (e) { return "app"; }
  }

  socketsByRole(role) {
    return this.agentSockets().filter((ws) => this.socketRole(ws) === role);
  }

  touchWs(device) {
    if (device) this.state.device = device;
    this.state.last_seen = Date.now();
    this.saveState();
  }

  deliverToSockets(cmd) {
    const role = cmd.role || "app";
    for (const ws of this.socketsByRole(role)) {
      try { ws.send(JSON.stringify(cmd)); return true; } catch (e) { /* 换下一个 */ }
    }
    return false;
  }

  async webSocketMessage(ws, message) {
    await this.load();
    if (typeof message !== "string") return;
    let obj;
    try { obj = JSON.parse(message); } catch (e) { return; }
    if (!obj || typeof obj !== "object") return;
    let dev = null;
    try { dev = (ws.deserializeAttachment() || {}).device || null; } catch (e) {}
    if (obj.type === "hello") {
      this.touchWs(obj.device || dev);
      if (obj.tele && typeof obj.tele === "object") {
        this.state.tele = obj.tele;
        this.state.tele_ts = Date.now();
        this.saveState();
      }
      try { ws.send(JSON.stringify({ type: "hello_ack", ts: Date.now() })); } catch (e) {}
      return;
    }
    if (obj.type === "ping") {
      this.touchWs(dev);
      if (obj.tele && typeof obj.tele === "object") {
        this.state.tele = obj.tele;
        this.state.tele_ts = Date.now();
        this.saveState();
      }
      try { ws.send(JSON.stringify({ type: "pong", ts: Date.now() })); } catch (e) {}
      return;
    }
    if (obj.type === "result") {
      this.touchWs(dev);
      const data = (obj.result && typeof obj.result === "object") ? obj.result : obj;
      this.deliverResult(data);
      // 结果确认：执行者凭此删待发箱条目；没收到确认它会一直重发，
      // deliverResult 对同 id 重复投递是幂等的（落盘覆盖 + 唤醒等待者）。
      if (data && data.id) {
        try { ws.send(JSON.stringify({ type: "result_ack", id: data.id })); } catch (e) {}
      }
      return;
    }
    if (obj.id && obj.tool && (obj.ok !== undefined || obj.error !== undefined)) {
      this.touchWs(dev);
      this.deliverResult(obj);
      try { ws.send(JSON.stringify({ type: "result_ack", id: obj.id })); } catch (e) {}
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    try { ws.close(code || 1000, "closed"); } catch (e) {}
  }

  async webSocketError(ws, err) {
    try { ws.close(1011, "error"); } catch (e) {}
  }

  enqueue(cmd) {
    if (!cmd.role) cmd.role = roleFor(cmd.tool);
    // 同角色 WS 在线时直接推送（DO 随即继续休眠）；否则回退同角色长轮询等待者/持久队列
    if (this.deliverToSockets(cmd)) return;
    for (let i = 0; i < this.pollWaiters.length; i++) {
      if (this.pollWaiters[i].role === cmd.role) {
        const w = this.pollWaiters.splice(i, 1)[0];
        if (w.finish(cmd)) return;
      }
    }
    this.queue.push(cmd);
    this.saveQueue();
  }

  waitPoll(ms, role) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (cmd) => { if (!done) { done = true; resolve(cmd); return true; } return false; };
      const timer = setTimeout(() => {
        const i = this.pollWaiters.findIndex((w) => w.finish === finish);
        if (i >= 0) this.pollWaiters.splice(i, 1);
        finish(null);
      }, ms);
      this.pollWaiters.push({ role: role || "app", finish: (cmd) => { clearTimeout(timer); return finish(cmd); } });
    });
  }

  // 从持久队列取同角色命令（保持相对顺序，其余原样留队）
  takeQueued(role, n) {
    const out = [];
    const rest = [];
    for (const c of this.queue) {
      if ((c.role || "app") === role && out.length < n) out.push(c);
      else rest.push(c);
    }
    if (out.length) { this.queue = rest; this.saveQueue(); }
    return out;
  }

  async waitResult(id, ms) {
    if (this.results.has(id)) {
      const r = this.results.get(id);
      this.results.delete(id);
      return r;
    }
    // 休眠唤醒后内存结果已失：查落盘副本（deliverResult 无人等待时写入）
    try {
      const saved = await this.ctx.storage.get("res:" + id);
      if (saved) {
        try { await this.ctx.storage.delete("res:" + id); } catch (e) {}
        return saved;
      }
    } catch (e) {}
    return new Promise((resolve) => {
      let done = false;
      const finish = (r) => { if (!done) { done = true; resolve(r); } };
      const timer = setTimeout(() => finish(null), ms);
      const list = this.resultWaiters.get(id) || [];
      list.push((r) => { clearTimeout(timer); finish(r); });
      this.resultWaiters.set(id, list);
    });
  }

  deliverResult(data) {
    const id = data && data.id;
    if (!id) return;
    // 总是落盘一份：等待者可能已随调用方断线而失效，结果被其消费后既不在
    // 内存也不在盘上就会永久丢失；落盘后 /wait 凭 id 仍可取回（waitResult
    // 查 res: 前缀），DO 休眠/回收同理。
    try {
      const p = this.ctx.storage.put("res:" + id, data);
      if (p && p.catch) p.catch(() => {});
    } catch (e) {}
    const list = this.resultWaiters.get(id);
    if (list && list.length) {
      this.resultWaiters.delete(id);
      for (const fn of list) fn(data);
    } else {
      this.results.set(id, data);
      if (this.results.size > 50) {
        const oldest = this.results.keys().next().value;
        this.results.delete(oldest);
      }
    }
  }

  async fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    await this.load();

    if (path === "/ws") {
      if ((req.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
        return json({ error: "websocket_required" }, 426);
      }
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      this.ctx.acceptWebSocket(server);
      const role = req.headers.get("X-Role") === "root" ? "root" : "app";
      try {
        server.serializeAttachment({ device: req.headers.get("X-Device") || null, role, since: Date.now() });
      } catch (e) {}
      this.touch(req);
      // 连接建立时把持久队列里同角色的积压命令依次推给新 socket
      const mine = this.takeQueued(role, 1000);
      let drained = 0;
      for (const cmd of mine) {
        try { server.send(JSON.stringify(cmd)); drained++; }
        catch (e) { this.queue.unshift(...mine.slice(drained)); await this.saveQueue(); break; }
      }
      return new Response(null, { status: 101, webSocket: client });
    }

    if (path.startsWith("/poll")) {
      this.touch(req);
      const n = Math.max(1, Math.min(parseInt(url.searchParams.get("n") || "1", 10) || 1, 16));
      const role = url.searchParams.get("role") === "root" ? "root" : "app";
      const queued = this.takeQueued(role, n);
      if (queued.length) {
        if (n === 1) return json(queued[0], 200);
        return json({ cmds: queued }, 200);
      }
      const cmd = await this.waitPoll(25000, role);
      if (cmd) {
        if (n === 1) return json(cmd, 200);
        const cmds = [cmd, ...this.takeQueued(role, n - 1)];
        return json({ cmds }, 200);
      }
      this.touch(req);
      return new Response(null, { status: 204 });
    }

    if (path.startsWith("/result")) {
      this.touch(req);
      const data = await req.json().catch(() => ({}));
      this.deliverResult(data);
      return json({ ok: true }, 200);
    }

    if (path.startsWith("/call")) {
      const data = await req.json().catch(() => ({}));
      const tool = data.tool;
      if (!tool) return json({ error: "tool_required" }, 400);
      const wait = Math.max(1, Math.min(Number(data.wait || data.timeout || 60), 55));
      // 客户端可自带 id：断连重试复用同一 id，服务端按 id 幂等去重，不重复执行
      const id = (typeof data.id === "string" && /^[a-zA-Z0-9-]{6,32}$/.test(data.id))
        ? data.id
        : crypto.randomUUID().replace(/-/g, "").slice(0, 12);
      if (this.results.has(id)) {
        const r = this.results.get(id);
        this.results.delete(id);
        return json(r, 200);
      }
      this.state.last_tool = tool;
      this.saveState();
      const already = this.queue.some((c) => c.id === id) || this.resultWaiters.has(id);
      if (!already) this.enqueue({ id, tool, args: data.args || {}, role: roleFor(tool, data.role) });
      const res = await this.waitResult(id, wait * 1000);
      if (res === null) return json({ pending: true, id }, 202);
      return json(res, 200);
    }

    if (path.startsWith("/wait")) {
      const data = await req.json().catch(() => ({}));
      const id = data.id;
      if (!id) return json({ error: "id_required" }, 400);
      const wait = Math.max(1, Math.min(Number(data.wait || 50), 55));
      const res = await this.waitResult(id, wait * 1000);
      if (res === null) return json({ pending: true, id }, 202);
      return json(res, 200);
    }

    if (path.startsWith("/batch")) {
      const data = await req.json().catch(() => ({}));
      const wait = Math.max(1, Math.min(Number(data.wait || 55), 55));
      let ids = [];
      if (path.startsWith("/batchwait")) {
        ids = Array.isArray(data.ids) ? data.ids.filter((x) => typeof x === "string") : [];
        if (!ids.length) return json({ error: "ids_required" }, 400);
      } else {
        const calls = Array.isArray(data.calls) ? data.calls : [];
        if (!calls.length) return json({ error: "calls_required" }, 400);
        for (const c of calls) {
          if (!c || !c.tool) continue;
          const id = (typeof c.id === "string" && /^[a-zA-Z0-9-]{6,32}$/.test(c.id))
            ? c.id
            : crypto.randomUUID().replace(/-/g, "").slice(0, 12);
          ids.push(id);
          this.state.last_tool = c.tool;
          const already = this.queue.some((q) => q.id === id) || this.resultWaiters.has(id) || this.results.has(id);
          if (!already) this.enqueue({ id, tool: c.tool, args: c.args || {}, role: roleFor(c.tool, c.role) });
        }
        this.saveState();
        if (!ids.length) return json({ error: "calls_required" }, 400);
      }
      const settled = await Promise.all(ids.map((id) => this.waitResult(id, wait * 1000)));
      const results = {};
      const pending = [];
      ids.forEach((id, i) => { if (settled[i] === null) pending.push(id); else results[id] = settled[i]; });
      if (pending.length) return json({ pending: true, results, pending_ids: pending }, 202);
      return json({ results }, 200);
    }

    if (path.startsWith("/status")) {
      const wsCount = this.agentSockets().length;
      // WS 在线即在线：连接本身就是存活证据，不被旧 last_seen 误导
      const seenTs = wsCount > 0 ? Date.now() : this.state.last_seen;
      return json({
        device: this.state.device,
        last_seen_ago_sec: seenTs ? (Date.now() - seenTs) / 1000 : null,
        queued: this.queue.length,
        queued_app: this.queue.filter((c) => (c.role || "app") === "app").length,
        queued_root: this.queue.filter((c) => c.role === "root").length,
        last_tool: this.state.last_tool,
        ws_connected: wsCount,
        ws_app: this.socketsByRole("app").length,
        ws_root: this.socketsByRole("root").length,
        transport: wsCount > 0 ? "websocket" : "poll",
        // 执行者遥测（随 hello/ping 上报）：待发箱深度、最近结果确认、
        // 下一个本地任务等——「心跳绿但命令死」的假象靠它戳穿
        tele: this.state.tele || null,
        tele_ago_sec: this.state.tele_ts ? (Date.now() - this.state.tele_ts) / 1000 : null,
      }, 200);
    }

    // ---- 小布收件箱（P2 深层直达）：钩子投递原话 -> 主脑取走处理 -> 回执供钩子轮询回灌 ----
    if (path === "/inbox" && req.method === "POST") {
      this.pruneInbox();
      const data = await req.json().catch(() => ({}));
      const text = typeof data.text === "string" ? data.text.trim() : "";
      if (!text) return json({ error: "text_required" }, 400);
      const id = (typeof data.id === "string" && /^[a-zA-Z0-9-]{6,32}$/.test(data.id))
        ? data.id
        : crypto.randomUUID().replace(/-/g, "").slice(0, 12);
      if (!this.inbox[id]) {
        this.inbox[id] = {
          id,
          text: text.slice(0, 2000),
          roomId: typeof data.roomId === "string" ? data.roomId : "",
          recordId: typeof data.recordId === "string" ? data.recordId : "",
          sessionId: typeof data.sessionId === "string" ? data.sessionId : "",
          ts: Date.now(),
          answer: null,
          answered_ts: 0,
        };
        await this.saveInbox();
      }
      return json({ ok: true, id }, 200);
    }

    if (path === "/inbox/reply" && req.method === "GET") {
      this.pruneInbox();
      const id = url.searchParams.get("id") || "";
      const item = this.inbox[id];
      if (!item) return json({ error: "not_found" }, 404);
      if (!item.answer) return new Response(null, { status: 204 });
      return json({ id, answer: item.answer }, 200);
    }

    if (path === "/inbox/ack" && req.method === "POST") {
      const data = await req.json().catch(() => ({}));
      if (data.id && this.inbox[data.id]) {
        delete this.inbox[data.id];
        await this.saveInbox();
      }
      return json({ ok: true }, 200);
    }

    if (path === "/inbox/pending" && req.method === "GET") {
      this.pruneInbox();
      const items = Object.values(this.inbox)
        .filter((it) => !it.answer)
        .sort((a, b) => a.ts - b.ts)
        .map((it) => ({ id: it.id, text: it.text, roomId: it.roomId, recordId: it.recordId, sessionId: it.sessionId, ts: it.ts }));
      return json({ items }, 200);
    }

    if (path === "/inbox/answer" && req.method === "POST") {
      const data = await req.json().catch(() => ({}));
      const item = data.id ? this.inbox[data.id] : null;
      if (!item) return json({ error: "not_found" }, 404);
      if (typeof data.answer !== "string" || !data.answer.trim()) return json({ error: "answer_required" }, 400);
      item.answer = data.answer.slice(0, 4000);
      item.answered_ts = Date.now();
      await this.saveInbox();
      return json({ ok: true, id: item.id }, 200);
    }

    return json({ error: "not_found" }, 404);
  }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/") return new Response("muse phone relay (edge)\n", { status: 200 });
    const token = req.headers.get("X-Token") || "";
    const isPhone = path.startsWith("/poll") || path.startsWith("/result") || path === "/ws";
    const isAdmin = path.startsWith("/call") || path.startsWith("/wait") || path.startsWith("/status")
      || path.startsWith("/batch")
      || path === "/inbox/pending" || path === "/inbox/answer";
    const isBreeno = path === "/inbox" || path === "/inbox/reply" || path === "/inbox/ack";
    if (!isPhone && !isAdmin && !isBreeno) return json({ error: "not_found" }, 404);
    if (isPhone && token !== env.PHONE_TOKEN) return json({ error: "bad_token" }, 401);
    if (isAdmin && token !== env.ADMIN_TOKEN) return json({ error: "bad_token" }, 401);
    if (isBreeno && token !== env.BREENO_TOKEN) return json({ error: "bad_token" }, 401);
    // 2026-10-26：DO 落点迁亚太。新实例名 main-apac-v1（旧 main 的队列切换时为空、
    // inbox 已随小布钩子摘除停用，不迁移）；locationHint 仅在实例创建时生效。
    const stub = env.RELAY.get(env.RELAY.idFromName("main-apac-v1"), { locationHint: "apac" });
    return stub.fetch(req);
  },
};

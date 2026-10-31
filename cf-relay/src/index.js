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
    this.inflight = {};            // cmdId -> {cmd, tag, at}（持久化）已投递未回结果的命令，socket 死亡时重投
    this.assemblies = new Map();   // 快车道：id -> {parts:[], next, bytes, at} 分块结果组装区（内存）
    this.streams = new Map();      // 快车道：id -> {controller, timer, started} /callstream 在线消费者
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
      const inf = await this.ctx.storage.get("inflight");
      if (inf && typeof inf === "object") this.inflight = inf;
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

  async saveInflight() {
    try { await this.ctx.storage.put("inflight", this.inflight); } catch (e) {}
  }

  socketTag(ws) {
    try { const a = ws.deserializeAttachment() || {}; return a.since || 0; } catch (e) { return 0; }
  }

  // Requeue commands whose delivery socket is gone (freeze/thaw zombie,
  // network flap). Executors dedup by command id (outbox / executed
  // ledger), so redelivery never re-executes a completed command.
  async reapInflight() {
    const ids = Object.keys(this.inflight);
    if (!ids.length) return;
    const live = new Set(this.agentSockets().map((ws) => this.socketTag(ws)));
    let changed = false;
    for (const id of ids) {
      const e = this.inflight[id];
      const age = Date.now() - (e.at || 0);
      // root-role executors ack on receipt: unacked after 45s means the
      // frame died in transit — requeue even if the socket looks alive.
      // Acked but resultless after 240s means execution died mid-flight;
      // executor dedup (executed ledger / outbox) makes redelivery safe.
      const lostInTransit = e.cmd.role === "root" && !e.acked && age > 45000;
      const diedMidFlight = e.cmd.role === "root" && e.acked && age > 240000;
      if (lostInTransit || diedMidFlight) {
        delete this.inflight[id];
        changed = true;
        if (!this.deliverToSockets(e.cmd)) {
          this.queue.unshift(e.cmd);
          await this.saveQueue();
        }
        continue;
      }
      if (!live.has(e.tag) && age > 20000) {
        delete this.inflight[id];
        changed = true;
        if (!this.deliverToSockets(e.cmd)) {
          this.queue.unshift(e.cmd);
          await this.saveQueue();
        }
      }
    }
    if (changed) await this.saveInflight();
  }

  deliverToSockets(cmd) {
    const role = cmd.role || "app";
    // Freshest socket first: a frozen/thawed client leaves a half-dead
    // socket that accepts send() silently — delivering to it strands the
    // command. lastSeen is stamped on every inbound message (see below).
    const cands = this.socketsByRole(role).map((ws) => {
      let seen = 0;
      try { const a = ws.deserializeAttachment() || {}; seen = a.lastSeen || a.since || 0; } catch (e) {}
      return { ws, seen };
    }).sort((x, y) => y.seen - x.seen);
    for (const c of cands) {
      try {
        c.ws.send(JSON.stringify(cmd));
        this.inflight[cmd.id] = { cmd, tag: this.socketTag(c.ws), at: Date.now() };
        this.saveInflight();
        return true;
      } catch (e) { /* 换下一个 */ }
    }
    return false;
  }

  async webSocketMessage(ws, message) {
    await this.load();
    await this.reapInflight();
    if (typeof message !== "string") return;
    let obj;
    try { obj = JSON.parse(message); } catch (e) { return; }
    if (!obj || typeof obj !== "object") return;
    let dev = null;
    try {
      const att = ws.deserializeAttachment() || {};
      dev = att.device || null;
      att.lastSeen = Date.now();
      ws.serializeAttachment(att);
    } catch (e) {}
    if (obj.type === "hello") {
      this.touchWs(obj.device || dev);
      if (Array.isArray(obj.caps) && obj.caps.length) {
        try {
          const att = ws.deserializeAttachment() || {};
          att.caps = obj.caps.filter((c) => typeof c === "string");
          ws.serializeAttachment(att);
        } catch (e) {}
      }
      if (obj.tele && typeof obj.tele === "object") {
        this.state.tele = obj.tele;
        this.state.tele_ts = Date.now();
        this.saveState();
      }
      try { ws.send(JSON.stringify({ type: "hello_ack", ts: Date.now(), caps: ["chunk1"] })); } catch (e) {}
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
    if (obj.type === "cmd_ack") {
      this.touchWs(dev);
      const e = this.inflight[obj.id];
      if (e && !e.acked) { e.acked = Date.now(); await this.saveInflight(); }
      return;
    }
    if (obj.type === "result_chunk") {
      this.touchWs(dev);
      this.feedChunk(obj);
      return;
    }
    if (obj.type === "result_end") {
      this.touchWs(dev);
      const okEnd = this.finishAssembly(obj);
      if (okEnd && obj.id) {
        try { ws.send(JSON.stringify({ type: "result_ack", id: obj.id })); } catch (e) {}
      }
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
    try {
      const tag = this.socketTag(ws);
      let changed = false;
      for (const id of Object.keys(this.inflight)) {
        const e = this.inflight[id];
        if (e.tag === tag) {
          delete this.inflight[id];
          changed = true;
          if (!this.deliverToSockets(e.cmd)) { this.queue.unshift(e.cmd); }
        }
      }
      if (changed) { await this.saveQueue(); await this.saveInflight(); }
    } catch (e) {}
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
    // 休眠唤醒后内存结果已失：查落盘副本（deliverResult 无人等待时写入；
    // 大结果是切片存的，loadStoredCopy 负责拼回）
    const savedCopy = await this.loadStoredCopy(id);
    if (savedCopy) return savedCopy;
    return new Promise((resolve) => {
      let done = false;
      const finish = (r) => { if (!done) { done = true; resolve(r); } };
      const timer = setTimeout(() => finish(null), ms);
      const list = this.resultWaiters.get(id) || [];
      list.push((r) => { clearTimeout(timer); finish(r); });
      this.resultWaiters.set(id, list);
    });
  }

  // ---- 快车道（流式大结果）：执行者把结果 JSON 文本切片发来 ----
  // 帧协议：{type:"result_chunk", id, seq, data} 逐片（data 为结果 JSON
  // 文本的子串），末帧 {type:"result_end", id, bytes}（bytes = JSON 文本
  // 的 UTF-8 字节数，完整性校验）。DO 一边组装一边转发给 /callstream 的
  // 在线消费者（NDJSON），尾帧到达且校验通过后照常 deliverResult 落账
  // ——慢车道语义（等待者/落盘副本）一点不变，快车道纯粹是加速通道：
  // 流断了、DO 被回收了，调用方回退 /wait 照样能拿到落盘的大结果
  // （大结果落盘时按 100KB 切片存，绕开单值上限）。
  feedChunk(obj) {
    const id = obj.id;
    if (!id || typeof obj.data !== "string" || typeof obj.seq !== "number") return;
    let a = this.assemblies.get(id);
    if (obj.seq === 0 || !a) {
      // seq 0 永远开新组：执行者重发（待发箱语义）即整组重来；在线消费者
      // 若已收过半截，先收到 reset 把缓冲清掉，不会拼出垃圾
      if (a && a.next > 0) {
        const s = this.streams.get(id);
        if (s) { s.sent = 0; this.writeStream(id, { t: "reset" }); }
      }
      a = { parts: [], next: 0, bytes: 0, at: Date.now() };
      this.assemblies.set(id, a);
    }
    if (obj.seq !== a.next) {
      // 乱序/缺帧：整组作废，等执行者从 seq 0 重发或走旧 result 通道
      this.assemblies.delete(id);
      return;
    }
    a.parts.push(obj.data);
    a.next += 1;
    a.bytes += new TextEncoder().encode(obj.data).length;
    a.at = Date.now();
    this.pumpStream(id);
  }

  // 把组装区里还没发给在线消费者的分片依次推入流（单一写者，无重无漏）
  pumpStream(id) {
    const s = this.streams.get(id);
    const a = this.assemblies.get(id);
    if (!s || !a) return;
    while (s.sent < a.parts.length) {
      const d = a.parts[s.sent];
      try {
        s.controller.enqueue(new TextEncoder().encode(JSON.stringify({ t: "chunk", d }) + "\n"));
        s.sent += 1;
      } catch (e) { this.closeStream(id); return; }
    }
  }

  finishAssembly(obj) {
    const id = obj.id;
    const a = this.assemblies.get(id);
    if (!a) return false;
    this.assemblies.delete(id);
    if (typeof obj.bytes === "number" && obj.bytes !== a.bytes) return false;
    const text = a.parts.join("");
    let data;
    try { data = JSON.parse(text); } catch (e) { return false; }
    this.pumpStream(id);
    this.writeStream(id, { t: "end" });
    this.closeStream(id);
    this.deliverResult(data, text);
    return true;
  }

  pruneAssemblies() {
    const cutoff = Date.now() - 300000;
    for (const [id, a] of this.assemblies) {
      if (a.at < cutoff) this.assemblies.delete(id);
    }
  }

  writeStream(id, obj) {
    const s = this.streams.get(id);
    if (!s) return;
    try {
      s.controller.enqueue(new TextEncoder().encode(JSON.stringify(obj) + "\n"));
    } catch (e) { this.closeStream(id); }
  }

  closeStream(id) {
    const s = this.streams.get(id);
    if (!s) return;
    this.streams.delete(id);
    if (s.timer) clearInterval(s.timer);
    try { s.controller.close(); } catch (e) {}
  }

  // 大结果落盘：单值放不下时切片存 res:<id>:n + res:<id>:<i>（原文切片）
  async storeResultCopy(id, data, rawText) {
    const text = typeof rawText === "string" ? rawText : JSON.stringify(data);
    if (text.length <= 100000) {
      try { await this.ctx.storage.put("res:" + id, data); } catch (e) {}
      return;
    }
    const n = Math.ceil(text.length / 100000);
    try {
      await this.ctx.storage.put("res:" + id + ":n", n);
      for (let i = 0; i < n; i++) {
        await this.ctx.storage.put("res:" + id + ":" + i, text.slice(i * 100000, (i + 1) * 100000));
      }
    } catch (e) {}
  }

  async loadStoredCopy(id) {
    try {
      const saved = await this.ctx.storage.get("res:" + id);
      if (saved) {
        try { await this.ctx.storage.delete("res:" + id); } catch (e) {}
        return saved;
      }
      const n = await this.ctx.storage.get("res:" + id + ":n");
      if (typeof n === "number" && n > 0) {
        let text = "";
        for (let i = 0; i < n; i++) {
          const part = await this.ctx.storage.get("res:" + id + ":" + i);
          if (typeof part !== "string") return null;
          text += part;
        }
        try {
          await this.ctx.storage.delete("res:" + id + ":n");
          for (let i = 0; i < n; i++) await this.ctx.storage.delete("res:" + id + ":" + i);
        } catch (e) {}
        return JSON.parse(text);
      }
    } catch (e) {}
    return null;
  }

  deliverResult(data, rawText) {
    const id = data && data.id;
    if (!id) return;
    if (this.inflight[id]) { delete this.inflight[id]; this.saveInflight(); }
    // 在线流消费者：组装路径在 finishAssembly 里已推完分片并关流（此处
    // streams 已无此 id，不会重复）；旧单帧 result 到达而流在等时，把
    // 结果原文整块推给它并收尾——否则流消费者只能饿到超时。
    if (this.streams.has(id)) {
      const text = typeof rawText === "string" ? rawText : JSON.stringify(data);
      this.writeStream(id, { t: "chunk", d: text });
      this.writeStream(id, { t: "end" });
      this.closeStream(id);
    }
    // 总是落盘一份：等待者可能已随调用方断线而失效，结果被其消费后既不在
    // 内存也不在盘上就会永久丢失；落盘后 /wait 凭 id 仍可取回（waitResult
    // 查 res: 前缀），DO 休眠/回收同理。大结果走切片落盘。
    try {
      const p = this.storeResultCopy(id, data, rawText);
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
    await this.reapInflight();
    this.pruneAssemblies();

    if (path === "/ws") {
      if ((req.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
        return json({ error: "websocket_required" }, 426);
      }
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      // Single session per device+role: a fresh connection replaces older
      // ones (freeze/thaw zombies). Client outboxes make this safe —
      // unacked results are resent on the new connection.
      try {
        const dev0 = req.headers.get("X-Device") || null;
        const role0 = req.headers.get("X-Role") === "root" ? "root" : "app";
        for (const old of this.agentSockets()) {
          try {
            const a = old.deserializeAttachment() || {};
            if ((a.device || null) === dev0 && (a.role || "app") === role0) old.close(1012, "replaced");
          } catch (e) {}
        }
      } catch (e) {}
      this.ctx.acceptWebSocket(server);
      const role = req.headers.get("X-Role") === "root" ? "root" : "app";
      try {
        const caps = (req.headers.get("X-Caps") || "").split(",").map((s) => s.trim()).filter(Boolean);
        server.serializeAttachment({ device: req.headers.get("X-Device") || null, role, since: Date.now(), caps });
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

    if (path.startsWith("/callstream")) {
      // 快车道：与 /call 同语义入队，但结果以 NDJSON 流回：{"t":"begin"}、
      // 若干 {"t":"chunk","d":...}（拼起来即结果 JSON 原文）、{"t":"end"}；
      // 等待期间每 20s 一帧 {"t":"ping"} 保活。目标执行者不支持分块、
      // 或结果已在内存/队列中时，退化为与 /call 完全一致的 JSON 应答，
      // 旧客户端与慢车道语义不受影响。流断开不影响投递：结果照常落账，
      // 调用方用同一 id 走 /wait 即可取回（含大切片落盘的副本）。
      const data = await req.json().catch(() => ({}));
      const tool = data.tool;
      if (!tool) return json({ error: "tool_required" }, 400);
      const id = (typeof data.id === "string" && /^[a-zA-Z0-9-]{6,32}$/.test(data.id))
        ? data.id
        : crypto.randomUUID().replace(/-/g, "").slice(0, 12);
      if (this.results.has(id)) {
        const r = this.results.get(id);
        this.results.delete(id);
        return json(r, 200);
      }
      const role = roleFor(tool, data.role);
      const roleCaps = this.socketsByRole(role).map((ws) => {
        try { return (ws.deserializeAttachment() || {}).caps || []; } catch (e) { return []; }
      });
      const canChunk = roleCaps.some((c) => c.includes("chunk1"));
      this.state.last_tool = tool;
      this.saveState();
      const already = this.queue.some((c) => c.id === id) || this.resultWaiters.has(id) || this.inflight[id];
      if (!canChunk) {
        if (!already) this.enqueue({ id, tool, args: data.args || {}, role });
        const wait = Math.max(1, Math.min(Number(data.wait || 50), 55));
        const res = await this.waitResult(id, wait * 1000);
        if (res === null) return json({ pending: true, id }, 202);
        return json(res, 200);
      }
      if (this.streams.has(id) || this.streams.size >= 8) {
        return json({ error: "stream_busy", id }, 429);
      }
      const enc = new TextEncoder();
      let controllerRef = null;
      const stream = new ReadableStream({
        start: (controller) => { controllerRef = controller; },
        cancel: () => { this.closeStream(id); },
      });
      const sink = { controller: controllerRef, timer: null, started: Date.now(), sent: 0 };
      this.streams.set(id, sink);
      try { sink.controller.enqueue(enc.encode(JSON.stringify({ t: "begin", id }) + "\n")); } catch (e) {}
      sink.timer = setInterval(() => {
        if (Date.now() - sink.started > 900000) {
          this.writeStream(id, { t: "error", error: "stream_timeout" });
          this.closeStream(id);
          return;
        }
        this.writeStream(id, { t: "ping" });
      }, 20000);
      if (!already) this.enqueue({ id, tool, args: data.args || {}, role, stream: true });
      // 组装区可能已有先到的分片（命令早先已入队）：从游标处补推
      this.pumpStream(id);
      return new Response(stream, {
        status: 200,
        headers: { "Content-Type": "application/x-ndjson", "Cache-Control": "no-store" },
      });
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
        fastlane: {
          app: this.socketsByRole("app").some((ws) => { try { return ((ws.deserializeAttachment() || {}).caps || []).includes("chunk1"); } catch (e) { return false; } }),
          root: this.socketsByRole("root").some((ws) => { try { return ((ws.deserializeAttachment() || {}).caps || []).includes("chunk1"); } catch (e) { return false; } }),
          streams: this.streams.size,
        },
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

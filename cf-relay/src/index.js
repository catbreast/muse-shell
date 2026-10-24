// Muse 壳中继（Cloudflare Worker + Durable Object 版）
// 协议与原 VM 上的 server.py 完全一致：
//   GET  /poll   (phone 令牌) 长轮询取命令，25s 无命令返回 204
//   POST /result (phone 令牌) 回填结果
//   POST /call   (admin 令牌) 入队并等待结果；单次 HTTP 最多等 wait 秒(≤55)，
//                超时返回 202 {pending:true,id}，客户端续用 /wait 取
//   POST /wait   (admin 令牌) {id, wait} 等某条结果
//   GET  /status (admin 令牌) {device,last_seen_ago_sec,queued,last_tool}
// 单个 DO 实例串行处理全部状态，无竞态；队列持久化，进程被回收不丢命令。

function json(obj, code = 200) {
  return new Response(JSON.stringify(obj), {
    status: code,
    headers: { "Content-Type": "application/json" },
  });
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

  enqueue(cmd) {
    // 有正在等的 /poll 就直接交付（保持 FIFO：队列非空时不会有 waiter）
    while (this.pollWaiters.length) {
      const resolve = this.pollWaiters.shift();
      if (resolve(cmd)) return;
    }
    this.queue.push(cmd);
    this.saveQueue();
  }

  waitPoll(ms) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (cmd) => { if (!done) { done = true; resolve(cmd); return true; } return false; };
      const timer = setTimeout(() => {
        const i = this.pollWaiters.indexOf(finish);
        if (i >= 0) this.pollWaiters.splice(i, 1);
        finish(null);
      }, ms);
      this.pollWaiters.push((cmd) => { clearTimeout(timer); return finish(cmd); });
    });
  }

  waitResult(id, ms) {
    if (this.results.has(id)) {
      const r = this.results.get(id);
      this.results.delete(id);
      return Promise.resolve(r);
    }
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

    if (path.startsWith("/poll")) {
      this.touch(req);
      if (this.queue.length) {
        const cmd = this.queue.shift();
        await this.saveQueue();
        return json(cmd, 200);
      }
      const cmd = await this.waitPoll(25000);
      if (cmd) return json(cmd, 200);
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
      if (!already) this.enqueue({ id, tool, args: data.args || {} });
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

    if (path.startsWith("/status")) {
      return json({
        device: this.state.device,
        last_seen_ago_sec: this.state.last_seen ? (Date.now() - this.state.last_seen) / 1000 : null,
        queued: this.queue.length,
        last_tool: this.state.last_tool,
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
    const isPhone = path.startsWith("/poll") || path.startsWith("/result");
    const isAdmin = path.startsWith("/call") || path.startsWith("/wait") || path.startsWith("/status")
      || path === "/inbox/pending" || path === "/inbox/answer";
    const isBreeno = path === "/inbox" || path === "/inbox/reply" || path === "/inbox/ack";
    if (!isPhone && !isAdmin && !isBreeno) return json({ error: "not_found" }, 404);
    if (isPhone && token !== env.PHONE_TOKEN) return json({ error: "bad_token" }, 401);
    if (isAdmin && token !== env.ADMIN_TOKEN) return json({ error: "bad_token" }, 401);
    if (isBreeno && token !== env.BREENO_TOKEN) return json({ error: "bad_token" }, 401);
    const stub = env.RELAY.get(env.RELAY.idFromName("main"));
    return stub.fetch(req);
  },
};

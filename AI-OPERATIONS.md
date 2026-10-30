# AI 操作手册 — Muse 壳（手机执行面）

> 这份手册写给**操作这套系统的 AI**：接手一个远程操作手机的任务时，先读完第 0–2 节再动手。
> 人类向的项目介绍见 `README.md`；福袋等具体业务打法见 `server/fudai-runbook.md`（本手册只讲平台）。

## 0. 系统全景

```
AI（沙盒 VM） ──HTTPS──► CF 中继 phone.ruisnow.de ──长轮询──► 手机壳 BridgeService
     │                        (Worker + Durable Object)            │ ToolDispatcher
     └─ server/phone.py 等客户端脚本                          工具执行 + JSONL 账本
```

三段：**沙盒侧客户端**（`server/`）→ **CF 通道**（`cf-relay/`）→ **手机侧执行**（`app/`）。
手机永远是主动外连的一方（长轮询取令），沙盒无法直连手机；一切操作都是「入队一条工具调用、等结果」。

关键坐标（本仓库部署实例）：

| 项 | 值 |
|---|---|
| 中继域名 | `https://phone.ruisnow.de`（Worker 路由 `phone.ruisnow.de/*`） |
| Worker 名 | `phone-relay`，Durable Object 导出名 `Relay`（单实例 `idFromName("main")`，SQLite 存储） |
| 手机应用 | `app.ruisnow.muse`（系统 app 形态存活最好），控制台 Activity 为 MainActivity |
| 手机侧数据 | `/sdcard/MuseShell/`（账本 `ledger.jsonl`、帧图、开关文件） |
| 沙盒侧令牌 | `server/tokens.json`（不入库）：`{"admin": "<ADMIN_TOKEN>"}` |
| 部署 | `cf-relay/` 里跑 `cf deploy`（官方 cf CLI 的 OAuth 通道）；令牌是 Worker secrets（`PHONE_TOKEN`/`ADMIN_TOKEN`/`BREENO_TOKEN`），不在代码与仓库里 |

## 1. CF 通道（中继实现：`cf-relay/src/index.js`）

单个 Durable Object 串行持有全部状态：命令队列（持久化）、结果表（内存、上限 50）、
轮询等待者、设备状态（`device`/`last_seen`/`last_tool`，持久化）。DO 被回收不丢队列。

### 1.1 接口协议

所有请求带请求头 `X-Token`。三类令牌按路径分权：

| 路径 | 方法 | 令牌 | 语义 |
|---|---|---|---|
| `/poll` | GET | PHONE | 手机长轮询取命令；无命令挂起 25 秒后回 `204`；有命令回 `{id, tool, args}`。带 `?n=k`（0.7.10+ 壳）时一次最多回 k 条 `{cmds:[...]}` |
| `/result` | POST | PHONE | 手机回填结果 `{id, ok, result｜error}`；唤醒对应等待者，无人等则暂存结果表 |
| `/call` | POST | ADMIN | 入队并等待：`{id?, tool, args, wait}`；`wait` 被钳在 1–55 秒，超时回 `202 {pending:true, id}` |
| `/wait` | POST | ADMIN | `{id, wait}` 续等某条结果；同为 202 语义 |
| `/batch` | POST | ADMIN | 一次入队多条并等齐：`{calls:[{id?, tool, args}], wait}`；全回 `200 {results:{id:结果}}`，超时 `202 {pending:true, results, pending_ids}` |
| `/batchwait` | POST | ADMIN | `{ids:[...], wait}` 续等一批未回的结果，返回形同 `/batch` |
| `/status` | GET | ADMIN | `{device, last_seen_ago_sec, queued, last_tool}`——**判断手机在不在线只看这个** |
| `/inbox*` | — | BREENO | 历史遗留的小布收件箱（投递/答复/确认）；接管功能已于 0.7.9 摘除，此组接口闲置，勿再使用 |

### 1.2 幂等与可靠性（写客户端时必须懂）

- `/call` 可由客户端自带 `id`（`[a-zA-Z0-9-]{6,32}`）：同 id 重复提交不会重复执行——
  已有结果直接回结果，在途则只挂等待者。`phone.py` 每条调用生成一个 12 位 id，断连重试复用它。
- `/call` 单次 HTTP 最多等 55 秒（平台限制），长工具（OCR、装包）必然走 202 → `/wait` 续等循环。
- 手机侧 `/poll` 是 FIFO 直交付：有等待者时命令不进队列、直接递给最先挂起的轮询。

### 1.3 部署与回滚

- 改 `cf-relay/src/index.js` 后在 `cf-relay/` 执行 `cf deploy` 发布；部署前先想清协议兼容：
  手机端 BridgeService 与沙盒端 phone.py 都按本协议说话，改协议要三方一起动。
- DO 落点（2026-10-26 起）：实例名 `main-apac-v1`，`get(id, {locationHint:"apac"})`
  （提示仅创建时生效）。旧实例 `main` 已弃（切换时队列为空；inbox 随小布摘除停用未迁移）。
  再迁落点 = 换新实例名 + 部署，须挑队列为空时做。
- 旧 VM 版中继 `server/server.py`（同协议）保留作回滚参考：把壳控制台的服务地址与
  `PHONE_BASE` 一起切回旧地址即可，**现行不要启用**（双中继会抢命令）。

## 2. 沙盒侧服务端代码（`server/`）

### 2.1 `phone.py` — 唯一的调用入口

```bash
python3 phone.py status                                   # 中继状态（手机在线否）
python3 phone.py call <tool> '<args-json>' [--timeout N]   # 调一个工具，打印结果 JSON
```

- 令牌从同目录 `tokens.json` 读 `admin` 字段；中继地址默认 `https://phone.ruisnow.de`，
  环境变量 `PHONE_BASE` 可覆盖。
- `call` 内部：生成 id → `POST /call`（wait=50）→ 202 就循环 `POST /wait`，直到 `--timeout`（默认 60 秒）。
  传输层错误自动重试 2 次（幂等保证不重复执行）。超时返回
  `{"ok": false, "error": "phone_timeout_or_offline"}`——先查 `status` 再下结论。
- 返回值约定：`{"id": ..., "ok": true, "result": {...}}`；`result` 的形状随工具而定。
  **工具返回体积按约 7KB 封顶**（中继大包不可靠），所以截图类工具一律切片（见 2.2）。

### 2.2 看图：两条路，本机 OCR 优先

**首选——手机本地 OCR（图不出手机、快、坐标即真值）**：

```bash
# 1) 现查虚拟屏的 SurfaceFlinger ID（每次建屏都变；screencap 只认这个 ID，不认逻辑 displayId）
phone.py call shell '{"cmd":"dumpsys SurfaceFlinger --display-id | grep MuseScreen"}'
#    → Display 11529215046768835799 (Virtual display): displayName="MuseScreen"
# 2) 直截虚拟屏到手机本地
phone.py call shell '{"cmd":"screencap -d <SFID> -p /sdcard/MuseShell/frame.png"}'
# 3) 手机 Debian 容器内 OCR（/root/muse_ocr.py，RapidOCR，整帧约 2 秒）
phone.py call linux.exec '{"cmd":"python3 /root/muse_ocr.py /mnt/sdcard/MuseShell/frame.png"}'
#    → {"n":65, "sec":1.9, "items":[["文本", cx, cy], ...]}，cx/cy 就是 vscreen 像素坐标，直接拿去 tap
```

主屏同理（`screencap -p` 不带 `-d` 即主屏），但**用户在用主屏时禁止抢主屏**，见第 4 节。

**备用——沙盒侧 OCR**：`see_parts.py vscreen <out.jpg> 32000` 把 `vscreen.see` 的切片
（`{part, parts, data}`，每片约 3.4KB base64）拼回整幅 JPEG，再
`./ocr-venv/bin/python ocrtap.py file <图>` 识别；`ocrtap.py index|tap <文字> [--vscreen]`
是一条龙（取帧→识别→按字点击），坐标换算：屏坐标 = 图坐标 / scale（see_parts 输出 scale）。
慢（每帧数十秒）且小字易误读，只在容器不可用时用。

### 2.3 其他常驻脚本

| 文件 | 用途 |
|---|---|
| `unlock.py` | 只封装 `lock.unlock`（远程解锁，PIN 只在手机端，调用零参数） |
| `keepalive.sh` / `muse-phone-relay.service` | 旧 VM 中继时代的保活与 systemd 单元，现行闲置，勿启 |
| `muse-thaw.sh` | root 解冻看门狗原件（ColorOS 冻结器对策，现由壳内监工主导，此为保险丝） |
| `deploy_worker.py` / `push_apk.py` / `grab.py` / `see.py` | 历史运维脚本，非现行链路，改动前先确认还有没有人用 |

## 3. 手机侧执行面（`app/`）

- `BridgeService`：前台服务，持 WakeLock 长轮询 `/poll`；监工线程每 30 秒查轮询活性，
  僵死先进程内自愈、再不行杀进程自重启（断联上限约 3 分钟）；看门狗闹钟在服务被杀后拉活。
- **启停语义（控制台按钮与通知按钮共用）**：停止 = `Prefs.setUserStopped(true)` + 停服务
  （看门狗据此**不**复活）；启动 = 清该标记 + `startForegroundService`。
  通知栏按钮随状态翻面：运行中显示「停止」，停止后的通知上是「启动」。
- `ToolDispatcher`：全部工具的注册与分发；`Prefs.killSwitch` 是分发层总闸（默认关，现无 UI 入口）；
  `shell` 工具的免审偏好 `shell_trust` 默认开。
- 工具清单（53 个，按域）：
  - 基础：`ping` `status` `notify` `lock.unlock` `shell` `shell.check`
  - 本地任务（0.8.0）：`jobs.add/list/remove`——手机本地持久化定时任务（daily/at_ms/every_min），AlarmManager 准点唤醒本机执行、结果进待发箱回仓；临点任务（如零点抢券启动）应入 jobs、不要靠云端临点下令。`shell`/`linux.*` 自 0.8.0 起由容器内 root agent（server/agent/，独立进程）直执，中继按角色路由，App 冻结不影响；其余工具仍走 App。
  - 文件：`files.list/read/write/delete/mkdir`（白名单目录）
  - 无障碍：`a11y.status/see/nodes/tree/tap/type/scroll/back/home`（手势只落**主屏**）
  - 应用：`android.launch/packages/install/uninstall/force_stop/clear/setting/input`
    （`android.launch` 的参数名是 `package` 不是 `pkg`）
  - 虚拟屏：`vscreen.create/launch/status/see/tap/swipe/key/preview/close`
  - 浏览器：`browser.open/snapshot/find/click/type/read`、`browser.batch`（0.7.15：一次下发多步在设备端顺序执行；步骤 `do` 可为 open/find/click/type/read/snapshot/sleep 与等待条件 wait_text/wait_url/wait_quiet；find 以 `as` 命名 ref、后续步用 `$名字` 引用，缺省存 `$last`；默认失败即停并回 stopped_at，每步只回紧凑摘要，全批上限 30 步/120 秒）
  - 容器：`linux.status` `linux.exec`（输出封顶约 4500 字）
  - 权限：`privilege.status/request/mode`（Shizuku 等）
- 每次调用都记账本；排障时先看账本尾部，比猜快。

## 4. 标准作业流程（远程操作手机）

1. `phone.py status`：`last_seen_ago_sec` 应为个位数秒；`queued` 长期 >0 说明手机没在取令。
2. `lock.unlock`：未锁自动跳过；BFU（重启后未首次解锁）状态无解，不要尝试绕。
3. **默认在虚拟屏作业**（`vscreen.create {"width":1080,"height":2376,"dpi":480}` →
   `vscreen.launch`）：用户多半正在主屏用手机，a11y 手势与主屏截图都会打扰/串台。
   只有任务明确指向主屏现状时才用主屏工具链。
4. 看图走 2.2 本机 OCR；点按用 OCR 给的中心点；**每次关键动作后重新取帧验收**，
   不要凭「应该成功了」继续。
5. 收尾：`vscreen.close`；把过程中的临时文件清掉（手机 `/sdcard/MuseShell/` 与沙盒 /tmp）。

## 5. 约束与坑（全部实测换来的，别重踩）

- **返回体积**：单次工具返回 ≳7KB 不可靠 → 截图切片、文本截断都是为此；自己写新工具时守此限。
- **息屏断联**：息屏后轮询线程可能僵死，壳有自愈（≤约 3 分钟）；给手机的调用超时要 ≥300 秒再判死。
- **`pkill -f` 会自匹配**：模式串出现在执行命令的 shell 自身命令行里 → 自杀。杀进程用 pgrep + 读 `/proc/<pid>/cmdline` 过滤。
- **screencap 只认 SurfaceFlinger ID**：逻辑 displayId（如 7）会报 `is not valid`；每次建屏后现查（2.2 第 1 步）。
- **开关文件在小布进程里读不到**：作用域存储拦截 `/sdcard/MuseShell/*` 的读写，读失败的代码默认值会决定行为——
  这是「小布接管关不掉」的根因（0.7.9 已摘除对话钩子，只剩电源键启动拦截）。给钩子加开关时别再用文件做跨进程信道。
- **绝不冻结 `com.facebook.aura`**：那是 Muse 官方 App 本人，冻结即黑屏。
- **APK 分发**：壳的更新只发聊天窗口让用户点装，不走远程推送安装。
- **改壳规矩**：改前先把当前源码与 APK 各存一份带版本号的备份（`backups/<日期>-<说明>/`）；
  构建完必须 commit + push（仓库 `catbreast/muse-shell`）；版本号在 `app/AndroidManifest.xml`。

## 6. 故障速查

| 症状 | 先查 | 常见原因 |
|---|---|---|
| `phone.py call` 超时 | `phone.py status` 的 `last_seen_ago_sec` | 手机息屏僵死（等自愈 ≤3 分钟）／壳服务被停（看 userStopped）／中继异常 |
| `/status` 里 queued 涨 | 手机是否在轮询 | 壳进程被冻结或已死；让用户点一下通知「启动」最快 |
| OCR 找不到字 | 帧是不是当前页 | 页面未加载完、弹窗遮挡；先整帧看图再查字 |
| `vscreen.see` 无帧 | `vscreen.status` 的 `hasFrame` | 屏上还没启动任何应用，先 `vscreen.launch` |
| 点了没反应 | 点的坐标系 | 本机 OCR 坐标=虚拟屏像素；沙盒 OCR 要除 scale；a11y.tap 落的是主屏不是虚拟屏 |
| 构建后钩子不生效 | Vector 里模块是否启用/作用域 | 钩子类改动要重启目标进程；日志在小布进程里落不了盘，只能实测验证 |

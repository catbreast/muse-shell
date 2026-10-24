# agy（gemini-3.8-flash-high）对 0.6.8/0.6.9 保活改动的审查（2026-10-23 01:24，原样保存）

结论：当前「持锁 + 监工 150 秒超时自杀 + shell 脚本 5 秒解冻」的方案属高对抗性临时修补，在断网或长任务时存在确定性的误杀循环（Crash Loop），且对 Android 系统机制（Doze 闹钟配额、AMS 重启语义）的理解存在偏差。

## 一、确认的问题（按严重度）

### [P0-1] 网络离线/服务端故障时的 Supervisor 误杀死循环
- 位置：startSupervisor()。仅依赖挂钟差值判断，未对 ConnectivityManager 网络连通性做前置判断。真断网（地下车库/飞行模式/中继故障）>150 秒即无限自杀+冷启动循环。
- 后果：AMS 对频繁自杀进程有惩罚性退避（Bad Process / Crash Throttling），START_STICKY 重启延迟被指数拉长甚至放弃托管；ColorOS Athena/Hans 会把应用打入受限池、撤销前台服务资格。

### [P0-2] 耗时命令执行时的 Supervisor 误杀（与注释断言直接矛盾）
- 位置：loop() 同步 dispatch vs startSupervisor() 注释 "A live task implies fresh polls"。
- 派发期间 loop 线程被完全阻塞、不可能发起新 poll，lastPollOkMs 停留在派发前。命令超过 150 秒（如长 shell、文件传输）会被误判僵死而 killProcess：长命令被腰斩、中继端等不到 /result 产生逻辑悬挂。

### [P0-3] Doze 下 30s/60s setAndAllowWhileIdle 是伪命题
- Android 6 起 Doze 对 setAndAllowWhileIdle 有硬配额：每 9–15 分钟最多一次，白名单也不能突破；30/60 秒的 delay 会被强制推迟对齐。
- 实测的快速复活来自 START_STICKY 的 AMS 原生重启而非该闹钟；一旦 AMS 触发自杀惩罚停止拉起，断联上限即退化为 10–15 分钟。

### [P1-4] killProcess 跳过生命周期
- SIGKILL 不触发 onDestroy：悬浮球移除、wakeLock.release()、service_stop 落账都不执行；SharedPreferences apply() 未刷盘时可能数据断裂。

### [P1-5] muse-thaw.sh 硬编码 UID 与解冻震荡
- uid_10381 硬编码：重装/清数据后 UID 变化则脚本静默失效。
- 5 秒轮询与 Hans 毫秒级冻结之间形成「冻 4.9 秒→解冻 0.1 秒」震荡；冻结期间内核协议栈仍运行，中继端 FIN 到达后用户态无法处理，解冻瞬间 socket 常已半关闭。

### [P1-6] 阻塞式 IO 对 interrupt() 脱敏
- HttpURLConnection 的阻塞 socket IO / DNS 属不可中断阻塞，interrupt() 无法叫醒卡死线程；只有关闭 socket fd / disconnect() 才行。（即：线程级软重启不可行）

## 二、存疑点

1. **僵死根因**：通知停在「连接中断，重连中…」证明线程进过 catch 并执行了 Thread.sleep(backoff)，是在下一次 HttpUtil.request() 中挂死。两大嫌疑：①DNS getaddrinfo 经 netd 解析无超时，息屏/冻结/网络切换后可永久挂死在 native 层；②Keep-Alive 连接池复用半开死连接，陷入极长 TCP 重传。
2. **挂钟跳变**：lastPollOkMs 用 System.currentTimeMillis()，NTP/基站对时向前校准 >2.5 分钟会瞬间误触发；应使用 SystemClock.elapsedRealtime() 单调时钟。
3. **BAL 限制**：Android 12+ 后台 BroadcastReceiver 调 startForegroundService 有 ForegroundServiceStartNotAllowedException 风险（root+豁免下目前没炸，属平台非标准行为隐患）。

## 三、改进建议

1. **根因向**：停止 userspace 脚本暴力解冻，用 root 把 App 真正加入 ColorOS Hans 白名单（如 /data/system/hans_config.xml / /system/etc/hans_policy.xml），或把进程移出受 freezer 监管的 cgroup 分组；deviceidle whitelist 一并确认。
2. **Supervisor 防御守卫**：超时判定前加三道前置校验——网络守卫（无 NET_CAPABILITY_INTERNET 只记日志不杀）、任务守卫（dispatcher 执行中 isDispatching 标志，执行中严禁杀）、单调时钟替换挂钟。
3. **避免 killProcess，改网络句柄 Cancel + 线程重置**：HttpUtil 暴露当前 HttpURLConnection，Supervisor 先 disconnect() 强制阻塞 read 抛 SocketException 让 worker 安全退出并在进程内重启线程；killProcess 只作最后底牌。
4. **网络层换 OkHttp**：精确 Call.cancel()、可定制超时的独立 Dns、连接池自带陈旧连接驱逐；若暂留 HttpURLConnection，至少显式 `Connection: close` + `http.keepAlive=false` 禁用复用。

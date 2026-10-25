# Muse Shell（壳）

给 AI agent 当「手和眼」的 Android 壳应用：agent 本体跑在远端沙盒里，手机上只装这个壳，
负责执行工具调用——看屏幕、点按、跑 shell、开虚拟屏、操作内嵌浏览器——并把结果回传。

为一加 13（ColorOS / Android 15）自用而建，包名 `app.ruisnow.muse`，当前版本 0.7.9。

## 架构

```
agent（沙盒） ── phone.py ──► Cloudflare Worker 中继（队列/结果）
                                    │  长轮询
                             BridgeService（前台服务）
                                    │  ToolDispatcher 分发
              ┌─────────┬───────────┼────────────┬──────────────┐
           a11y 工具   root shell   虚拟屏        AI 浏览器     LSPosed 钩子
```

- **中继**：`cf-relay/` 是 Cloudflare Worker + Durable Object 实现的命令队列；
  壳端长轮询取令、执行、回结果。中继只认令牌，壳与服务端各持一份（构建时注入，见下）。
- **账本**：每次工具调用都写入手机本地 JSONL 账本（`/sdcard/MuseShell/ledger.jsonl`），可审计。
- **自愈**：监工线程检测轮询僵死并自重启、看门狗闹钟在服务被杀后拉活（用户手动停止除外）。

## 功能面

- **基础工具**：`ping` / `status` / 文件白名单读写 / 通知 / 远程解锁（`lock.unlock`，PIN 只存手机端）
- **无障碍**：`a11y.see/nodes/tap/type/scroll`——截图（切片回传）、控件树、按字点击（OCR 补树）
- **root shell**：经 ReSukiSU 执行命令；免审开关在控制台（默认开，急停总闸保留在分发层）
- **虚拟屏**：`vscreen.create/launch/see/tap/close`——独立虚拟显示器里操作应用，不打扰主屏；
  统一规格 1080×2376@480
- **AI 浏览器**：内嵌 WebView + 结构化 DOM 快照工具（`browser.*`）
- **LSPosed 钩子**（`hook/`）：长按电源键劫持小布的语音助手启动、改为拉起 Muse 应用
  （拦截的只是 SpeechService 的启动命令，不碰对话内容）
- **控制台**：配对（中继地址+令牌）、服务启停、常驻通知上的启停按钮（随状态翻面）

## 目录

- `app/` — Android 应用（纯 Java，手工构建流水线 `build.sh`：aapt2 → javac → d8 → zipalign → apksigner，无 Gradle）
- `server/` — 沙盒侧工具：`phone.py`（命令行客户端）、`ocrtap.py`（OCR 按字点击）、
  `see_parts.py`（切片取帧拼装）、`muse_ocr.py`（手机本地 Debian 容器内运行的 OCR，坐标直出真实像素）、
  以及一个把上述能力串起来的自动化打法手册（`fudai-runbook.md`）
- `cf-relay/` — Cloudflare Worker 中继
- `core/`、`reviews/`、`DESIGN.md` — 引擎说明与审查记录

## 构建

需要 JDK 21 + Android SDK（platform 35 / build-tools）。签名 keystore 与令牌文件不入库：

- `app/build.sh` 在构建时由 `server/tokens.json`（本地文件，已 gitignore）生成
  `hook/RelaySecret.java`（中继令牌），缺失时生成空占位、相关功能自动关闭；
- APK 用自有 keystore 签名（`muse-shell.keystore`，不入库）。

```bash
cd app && bash build.sh   # 产物 ../MuseShell.apk
```

## 运行要求

- Root（ReSukiSU/KSU 类）——shell 与虚拟屏能力依赖它
- 可选：LSPosed（Vector 等分叉）——仅电源键钩子需要，作用域为小布
- 壳转为系统应用后在 ColorOS 上存活率显著更好（本项目的实测结论）

## 安全说明

这是一个给**自己设备**用的高权限工具：它能以 root 执行命令、读取屏幕内容。
中继令牌与配对令牌是唯一的访问控制，请勿把构建产物或令牌文件外发。
本项目按现状提供，用于个人自动化与研究。

## License

MIT

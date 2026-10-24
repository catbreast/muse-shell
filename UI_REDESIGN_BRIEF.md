# 任务：重做 Muse Shell 控制台界面（只改外观，不改行为）

项目：纯 Java 手工构建的 Android 应用（无 Gradle、无 XML 布局、无 Material 库），全部 UI 在代码里以原生 View 拼装。构建脚本：app/build.sh。

## 改哪些文件
- app/src/app/ruisnow/muse/MainActivity.java（控制台主界面，387 行）
- app/src/app/ruisnow/muse/ApprovalActivity.java（审批卡界面，85 行）

其余文件一律不许动。

## 现状问题
默认 Button、默认背景、纯文本堆叠，像十年前的工具 App。要做成现代 Android 控制台的样子。

## 设计方向（只用原生 View + GradientDrawable 实现，不加任何依赖）
- 深色主题：背景深炭色（如 #0E1116）；内容用卡片分区：圆角 16–20dp、1dp 微光描边或轻微底色差（#171B22 一类），卡内 padding 16–20dp，卡间距 12–16dp，全局遵循 8dp 间距节奏。
- 顶部标题区：应用名大字（26–28sp、粗体）+ 一行副标题；运行状态做成「小圆点 + 文字」的状态徽标（运行中 = 绿色点，未运行 = 灰/红点）。状态点可以由 refresh() 里已有的服务运行状态驱动着色——但只能改着色方式，不能改 refresh 的判定逻辑。
- 主操作：启动/停止服务做成全宽主按钮（高约 52dp、圆角 14dp、强调色填充）；其余操作按钮做成次级样式（卡面色填充 + 描边 + 圆角）。
- 强调色选一个克制的现代色（如青蓝 #4CC2FF 或紫 #A78BFA），全 App 统一。
- 输入区（服务地址、令牌、PIN 三个 EditText）：圆角描边背景、hint 调暗，分组放进「连接设置」卡片。
- 状态文本放进「状态」卡片；日志区做成终端风卡片：更深底色（如 #0A0C10）、等宽字体、11–12sp、行距放宽。
- 审批卡（ApprovalActivity）：同一套深色卡片语言——标题醒目、内容卡片化、同意/拒绝两按钮并排且高度 ≥52dp，同意用强调色填充，拒绝用危险红描边或填充。保持它现有的窗口形态（不要改成全屏或对话框属性）。
- 细节：所有可点控件最小高度 48dp；文字层级分明（分区标题 16sp 半粗、说明小字 12sp 灰）；每个 Button 都必须有自定义背景或 tint + 圆角，不许留系统默认灰按钮样式。
- 窗口：给 window 设置与深色背景协调的 statusBarColor / navigationBarColor，避免白边；不要改其它窗口行为。

## 硬约束（违反任何一条都算失败）
1. 行为零改动：所有按钮的点击处理、开关逻辑、文本内容（按钮文字、状态文案、hint、标题文字）保持原样；控件集合不增不减；类内字段都要保留（statusView、logView、serviceToggleBtn、filePermBtn、a11yBtn、overlayBtn、batteryBtn、shizukuBtn、urlEdit、tokenEdit、pinEdit 等）。
2. 权限颜色语义保留：setBtnColor(btn, granted) 机制必须保留且继续被 refresh() 调用——granted 与 revoked 用两种一眼可分的颜色（色值可换成与新主题协调的版本）。serviceToggleBtn 的「启动服务/停止服务」文字切换逻辑不动。
3. 不加依赖、不改构建脚本、不动 AndroidManifest、不动其它类。
4. 代码风格与现有一致（程序化拼 View；可以抽 dp()、card()、roundedBg() 等 helper，但只能放在这两个文件内部）。
5. 改完必须在项目根目录运行 app/build.sh 确认构建通过；失败就修到通过为止，并在总结里写明构建结果。
6. 不要顺手重构、不要改任何逻辑判断、不要动 Breeno/小布相关的静态方法。

## 交付
完成后给一份简短总结：改了哪些文件、设计要点清单、构建结果。不要把大段代码贴进总结。

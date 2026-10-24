# 任务：把 Muse 壳控制台界面回退到改版前的经典样式

你上次把界面改成了深色卡片风（0.7.2），用户不接受，要求回退到改版前的样子。本任务把两个文件的**外观**改回经典样式，**功能 100% 保持现状**。

## 只许改这两个文件

- `app/src/app/ruisnow/muse/MainActivity.java`
- `app/src/app/ruisnow/muse/ApprovalActivity.java`

不许动 AndroidManifest.xml、app/build.sh、其它任何类。不加新依赖。

## 外观目标（经典样式参照）

参照同机另一目录里的旧版公开版主界面（只看样式与布局手法，**不要整段抄它的代码**——它的包名和功能集都更旧）：

`/home/hatch/workspace/muse-android-shell-publish/app/src/app/museshell/MainActivity.java`

经典样式要点：

- 普通浅色系统外观：根布局一个竖排 LinearLayout + 常规 padding，不设窗口/状态栏自定义颜色。
- 没有卡片、没有圆角渐变背景、没有徽标、没有自定义按钮背景：按钮就是系统默认 Button 样式（除 setBtnColor 的权限着色，见下）。
- 标题就是一个普通 TextView「Muse 控制台」+ 设备 ID 一行。
- 状态文本、日志就是普通 TextView（日志等宽字体即可），不做终端风深色块。
- 输入框就是普通 EditText，不加描边背景。
- 控件顺序与分组沿用**当前文件**的顺序（当前文件的控件集合与文案为准），只是去掉卡片容器、平铺回根布局（可用普通 LinearLayout 分行）。

## 功能必须原样保留（以当前两文件为准）

- 全部字段：statusView、logView、serviceToggleBtn、filePermBtn、a11yBtn、overlayBtn、batteryBtn、shizukuBtn、urlEdit、tokenEdit、pinEdit，一个不能少、名字不能改。
- serviceToggleBtn 仍是合并的启停按钮：文字随 BridgeService.isRunning 在「启动服务 / 停止服务」间切换，点击逻辑不变。
- savePairBtn「保存配对并启动服务」：保存 base_url / token / unlock_pin（空则删）到 Prefs 后调用 startBridge()，逻辑一字不改。
- setBtnColor(btn, granted) 必须保留原语义（已授权 = 绿底 0xFF2E7D32、未授权 = 红底 0xFFC62828、白字），refresh() / refreshInner() 的调用与逻辑不变。
- autoHint 复选框、breeno 复选框及 breenoTakeoverOn / setBreenoTakeover 静态逻辑不变。
- ApprovalActivity 逻辑不变：读 intent extra "id"、查 ApprovalManager.PENDING、拒绝 → resolve(false)、允许一次 → resolve(true)、onDestroy 未决 → resolve(false)、Ledger 两条日志（approval_shown / approval_decided）；外观回到朴素对话框（标题 + 描述 + 两个普通按钮并排）。

## 必须修死的一个点：解锁 PIN 掩码

改版后 PIN 变成了明文。这次 pinEdit 必须确保掩码生效，且是该字段**最后**执行的设置：

```java
pinEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
        | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
pinEdit.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
```

- 这两行放在 pinEdit 所有其它样式设置之后。
- 不要对 pinEdit 调用 setTypeface，也不要在这两行之后再调用 setSingleLine / setInputType。

## 构建

改完在项目根执行 `bash app/build.sh`，必须 exit 0。失败就修到过，只修这两个文件。

## 交付说明（最后打印）

- 改动文件清单
- PIN 掩码采用的实现方式（一行）
- 构建结果（成功/失败 + 产物名）

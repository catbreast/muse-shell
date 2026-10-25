package app.ruisnow.muse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView logView;
    private Button serviceToggleBtn;
    private Button filePermBtn;
    private Button a11yBtn;
    private Button overlayBtn;
    private Button batteryBtn;
    private Button shizukuBtn;
    private EditText urlEdit;
    private EditText tokenEdit;
    private EditText pinEdit;

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void setBtnColor(Button btn, boolean granted) {
        if (btn == null) return;
        btn.setBackgroundColor(granted ? 0xFF2E7D32 : 0xFFC62828);
        btn.setTextColor(0xFFFFFFFF);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ledger.init(this);

        // targetSdk 35: since API 33 the foreground-service notification
        // only displays with the runtime notification permission granted.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 32, 36, 32);

        TextView title = new TextView(this);
        title.setText("Muse 控制台");
        title.setTextSize(22);
        root.addView(title);

        TextView devIdView = new TextView(this);
        devIdView.setText("设备 ID：" + Prefs.deviceId(this));
        devIdView.setTextSize(14);
        devIdView.setPadding(0, 4, 0, 12);
        root.addView(devIdView);

        serviceToggleBtn = btn(BridgeService.isRunning ? "停止服务" : "保存配对并启动服务", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (BridgeService.isRunning) {
                    Prefs.setUserStopped(MainActivity.this, true);
                    stopService(new Intent(MainActivity.this, BridgeService.class));
                    toast("服务已停止");
                } else {
                    android.content.SharedPreferences.Editor ed = Prefs.sp(MainActivity.this).edit()
                            .putString("base_url", urlEdit.getText().toString().trim())
                            .putString("token", tokenEdit.getText().toString().trim());
                    String pin = pinEdit.getText().toString().trim();
                    if (pin.length() > 0) ed.putString("unlock_pin", pin);
                    else ed.remove("unlock_pin");
                    ed.apply();
                    startBridge();
                    toast("已保存配对，服务启动中");
                }
                refresh();
                // startForegroundService is async: isRunning flips only when
                // the service's onCreate runs, so an immediate refresh still
                // reads the old state. Re-check shortly after to flip the
                // button label to match the service's real state.
                serviceToggleBtn.postDelayed(new Runnable() {
                    @Override public void run() { refresh(); }
                }, 800);
                serviceToggleBtn.postDelayed(new Runnable() {
                    @Override public void run() { refresh(); }
                }, 2000);
            }
        });
        root.addView(serviceToggleBtn);

        urlEdit = new EditText(this);
        urlEdit.setHint("服务地址，如 https://phone.ruisnow.de");
        urlEdit.setSingleLine(true);
        root.addView(urlEdit);

        tokenEdit = new EditText(this);
        tokenEdit.setHint("配对令牌（向你的 Muse 索取）");
        tokenEdit.setSingleLine(true);
        root.addView(tokenEdit);

        pinEdit = new EditText(this);
        pinEdit.setHint("解锁 PIN（只存本机，供远程解锁；留空保存 = 删除）");
        pinEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinEdit.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        root.addView(pinEdit);

        LinearLayout row2 = row();
        filePermBtn = btn("文件权限", new View.OnClickListener() {
            @Override public void onClick(View v) { askFilePerms(); }
        });
        row2.addView(filePermBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        a11yBtn = btn("无障碍设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        row2.addView(a11yBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(row2);

        LinearLayout row3 = row();
        overlayBtn = btn("悬浮窗权限", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            }
        });
        row3.addView(overlayBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        batteryBtn = btn("电池白名单", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            }
        });
        row3.addView(batteryBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(row3);

        LinearLayout row4 = row();
        shizukuBtn = btn("申请 Shizuku 授权", new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean sent = ShizukuPrivilege.request();
                toast(sent ? "已向 Shizuku 发送授权请求，请在弹窗里允许"
                        : "Shizuku 未运行：请先安装 Shizuku 并启动其服务");
                refresh();
            }
        });
        row4.addView(shizukuBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(row4);

        CheckBox shellTrust = new CheckBox(this);
        shellTrust.setText("root shell 免审（默认开启，重启保持）");
        shellTrust.setChecked(Prefs.shellTrust(this));
        shellTrust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setShellTrust(MainActivity.this, checked);
                ToolDispatcher.shellTrustSession = checked;
                toast(checked ? "shell 免审已开" : "shell 免审已关");
            }
        });
        root.addView(shellTrust);

        CheckBox autoConn = new CheckBox(this);
        autoConn.setText("自动连接（开机自启 + 被系统杀掉后自动恢复）");
        autoConn.setChecked(Prefs.autoConnect(this));
        autoConn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setAutoConnect(MainActivity.this, checked);
                if (checked) {
                    Prefs.setUserStopped(MainActivity.this, false);
                    BridgeService.armWatchdog(MainActivity.this, 60 * 1000L);
                    if (!BridgeService.isRunning && Prefs.token(MainActivity.this).length() > 0) startBridge();
                    toast("自动连接已开");
                } else {
                    BridgeService.disarmWatchdog(MainActivity.this);
                    toast("自动连接已关（开机不自启、被杀不恢复）");
                }
                refresh();
            }
        });
        root.addView(autoConn);

        CheckBox browserBox = new CheckBox(this);
        browserBox.setText("AI 浏览器（允许 Muse 操作内嵌网页）");
        browserBox.setChecked(Prefs.browserEnabled(this));
        browserBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setBrowserEnabled(MainActivity.this, checked);
                toast(checked ? "AI 浏览器已开" : "AI 浏览器已关");
                refresh();
            }
        });
        root.addView(browserBox);

        TextView autoHint = new TextView(this);
        autoHint.setText("提示：若开机后没自动连上，请在系统设置里给本应用开启「自启动」允许。");
        autoHint.setTextSize(12);
        autoHint.setPadding(0, 4, 0, 12);
        root.addView(autoHint);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setPadding(0, 12, 0, 12);
        root.addView(statusView);

        LinearLayout row5 = row();
        row5.addView(btn("刷新状态与日志", new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        row5.addView(btn("清除日志", new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean any = Ledger.clear(MainActivity.this);
                toast(any ? "日志已清除" : "没有日志可清除");
                refresh();
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(row5);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        urlEdit.setText(Prefs.baseUrl(this));
        tokenEdit.setText(Prefs.token(this));
        pinEdit.setText(Prefs.sp(this).getString("unlock_pin", ""));
        refresh();
    }

    private void startBridge() {
        Prefs.setUserStopped(this, false);
        Intent svc = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
    }

    private void askFilePerms() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    private void refresh() {
        try {
            refreshInner();
        } catch (Throwable t) {
            try {
                statusView.setText("状态读取出错（已拦截，不影响服务）:\n" + t);
                logView.setText("");
            } catch (Throwable ignored) {
            }
        }
    }

    private void refreshInner() {
        if (serviceToggleBtn != null) {
            serviceToggleBtn.setText(BridgeService.isRunning ? "停止服务" : "保存配对并启动服务");
        }

        boolean storageGranted = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean fileGranted = (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) && storageGranted;
        setBtnColor(filePermBtn, fileGranted);

        setBtnColor(a11yBtn, ShellA11yService.enabled());

        setBtnColor(overlayBtn, Settings.canDrawOverlays(this));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryGranted = pm != null
                && (Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(getPackageName()));
        setBtnColor(batteryBtn, batteryGranted);

        setBtnColor(shizukuBtn, "granted".equals(ShizukuPrivilege.state()));

        StringBuilder sb = new StringBuilder();
        sb.append("设备 ID：").append(Prefs.deviceId(this)).append("\n");
        sb.append("服务地址：").append(Prefs.baseUrl(this)).append("\n");
        sb.append("已配对：").append(Prefs.token(this).length() > 0 ? "是" : "否").append("\n");
        sb.append("解锁 PIN：").append(Prefs.sp(this).getString("unlock_pin", "").length() > 0 ? "已设置" : "未设置").append("\n");
        sb.append("自动连接：").append(Prefs.autoConnect(this) ? "开" : "关").append("\n");
        statusView.setText(sb.toString());
        logView.setText(Ledger.recent(this, 30));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}

package app.ruisnow.muse;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class ApprovalActivity extends Activity {
    private String id;
    private boolean resolved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getIntent().getStringExtra("id");
        ApprovalManager.Pending p = id == null ? null : ApprovalManager.PENDING.get(id);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 40, 48, 24);

        TextView title = new TextView(this);
        title.setText("Muse 请求执行高风险操作");
        title.setTextSize(18);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText(p == null ? "(请求已失效)" : p.desc);
        desc.setTextSize(15);
        desc.setPadding(0, 24, 0, 24);
        root.addView(desc);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishWith(false); }
        });
        row.addView(deny);

        Button allow = new Button(this);
        allow.setText("允许一次");
        allow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishWith(true); }
        });
        row.addView(allow);

        root.addView(row);
        setContentView(root);
        setFinishOnTouchOutside(false);

        try {
            JSONObject d = new JSONObject();
            d.put("desc", p == null ? "" : p.desc);
            Ledger.log(this, "approval_shown", d);
        } catch (Exception ignored) {
        }
    }

    private void finishWith(boolean allowed) {
        resolved = true;
        if (id != null) ApprovalManager.resolve(id, allowed);
        try {
            JSONObject d = new JSONObject();
            d.put("allowed", allowed);
            Ledger.log(this, "approval_decided", d);
        } catch (Exception ignored) {
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!resolved && id != null) ApprovalManager.resolve(id, false);
    }
}

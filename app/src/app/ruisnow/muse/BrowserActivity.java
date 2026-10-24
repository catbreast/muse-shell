package app.ruisnow.muse;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * AI 浏览器的可见页面（0.6.0）：只做一件事——把 BrowserHost 的 WebView 挂上屏。
 * 用户随时能看清 AI 在看哪个网页，也能直接上手接管；退出本页不销毁 WebView，
 * 页面与登录态由 BrowserHost 继续持有。
 */
public class BrowserActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("AI 浏览器");
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        try {
            View w = BrowserHost.get(this).attach(this);
            ViewGroup.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(w, lp);
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        BrowserHost.get(this).detach();
        super.onDestroy();
    }
}

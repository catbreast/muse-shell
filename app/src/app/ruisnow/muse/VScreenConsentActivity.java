package app.ruisnow.muse;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** One-shot transparent activity that runs the system screen-capture consent. */
public class VScreenConsentActivity extends Activity {
    private static final int REQ = 42;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            VScreenManager.onConsentResult(RESULT_CANCELED, null);
            finish();
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ) {
            VScreenManager.onConsentResult(resultCode, data);
        }
        finish();
    }
}

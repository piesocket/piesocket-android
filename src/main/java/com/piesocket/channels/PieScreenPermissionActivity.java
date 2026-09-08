package com.piesocket.channels;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * Transparent one-shot activity that asks the system for the
 * {@code MediaProjection} screen-capture permission and hands the result back to
 * {@link PieRTC}. The host app never sees it — {@link PieRTC#shareScreen()}
 * launches it, and this SDK's manifest declares it.
 */
public class PieScreenPermissionActivity extends Activity {

    interface ResultCallback {
        void onResult(int resultCode, Intent data);
    }

    static ResultCallback pendingCallback;

    private static final int REQUEST_CODE = 0x5C11;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            deliver(RESULT_CANCELED, null);
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE);
        } catch (Exception e) {
            deliver(RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE) {
            deliver(resultCode, data);
        }
    }

    private void deliver(int resultCode, Intent data) {
        ResultCallback callback = pendingCallback;
        pendingCallback = null;
        if (callback != null) {
            callback.onResult(resultCode, data);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    static void launch(Context context, ResultCallback callback) {
        pendingCallback = callback;
        Intent intent = new Intent(context, PieScreenPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}

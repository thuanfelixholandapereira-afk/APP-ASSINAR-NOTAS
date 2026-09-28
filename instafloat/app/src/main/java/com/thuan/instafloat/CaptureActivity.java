package com.thuan.instafloat;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

public class CaptureActivity extends Activity {
    private static final int REQ_CAPTURE = 9001;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            restoreBubble();
            finish();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("resultData", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        } else {
            restoreBubble();
        }
        finishAndRemoveTask();
    }

    private void restoreBubble() {
        Intent i = new Intent(this, FloatingService.class);
        i.setAction(FloatingService.ACTION_SHOW_BUBBLE);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }
}

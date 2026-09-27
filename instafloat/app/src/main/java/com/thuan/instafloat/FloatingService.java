package com.thuan.instafloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingService extends Service {
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private View panel;

    @Override public void onCreate() {
        super.onCreate();
        startAsForeground();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showBubble();
    }

    private void startAsForeground() {
        String channelId = "insta_float";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channelId, "Insta Float", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        b.setContentTitle("Insta Float ativo")
                .setContentText("Toque na bolha sobre o Instagram para baixar")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(pi);
        startForeground(77, b.build());
    }

    private void showBubble() {
        bubble = new TextView(this);
        bubble.setText("↓"); bubble.setTextSize(25); bubble.setTextColor(Color.WHITE); bubble.setGravity(Gravity.CENTER); bubble.setElevation(dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(Color.rgb(18,18,20)); bubble.setBackground(bg);

        bubbleParams = new WindowManager.LayoutParams(dp(54), dp(54), overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START; bubbleParams.x = dp(12); bubbleParams.y = dp(220);
        wm.addView(bubble, bubbleParams);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float downX, downY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleParams.x; startY = bubbleParams.y; downX = e.getRawX(); downY = e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX()-downX); int dy = (int)(e.getRawY()-downY);
                        if (Math.abs(dx)+Math.abs(dy) > dp(8)) moved = true;
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy; wm.updateViewLayout(bubble, bubbleParams); return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel(); return true;
                }
                return false;
            }
        });
    }

    private void togglePanel() {
        if (panel != null) { removePanel(); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14),dp(14),dp(14),dp(14)); box.setElevation(dp(10));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(18)); box.setBackground(bg);
        TextView title = new TextView(this); title.setText("Insta Float"); title.setTextSize(16); title.setTextColor(Color.rgb(25,25,27)); title.setPadding(dp(4),0,dp(4),dp(8)); box.addView(title);

        Button download = menuButton("↓ Baixar conteúdo atual"); box.addView(download);
        download.setOnClickListener(v -> { removePanel(); InstaAccessibilityService.requestCapture(this); });
        Button open = menuButton("Abrir aplicativo"); box.addView(open);
        open.setOnClickListener(v -> { removePanel(); Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); });
        Button close = menuButton("Fechar bolha"); box.addView(close); close.setOnClickListener(v -> stopSelf());

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(250), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START; p.x = Math.max(dp(8), bubbleParams.x); p.y = bubbleParams.y + dp(62);
        panel = box; wm.addView(panel, p);
    }

    private Button menuButton(String text) {
        Button b = new Button(this); b.setText(text); b.setTextAllCaps(false); b.setTextSize(13);
        GradientDrawable g = new GradientDrawable(); g.setColor(Color.rgb(241,241,243)); g.setCornerRadius(dp(12)); b.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)); p.topMargin = dp(7); b.setLayoutParams(p); return b;
    }

    private int overlayType() { return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }
    private void removePanel() { if (panel != null && wm != null) { try { wm.removeView(panel); } catch (Exception ignored) {} panel = null; } }

    @Override public void onDestroy() {
        removePanel();
        if (bubble != null && wm != null) { try { wm.removeView(bubble); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

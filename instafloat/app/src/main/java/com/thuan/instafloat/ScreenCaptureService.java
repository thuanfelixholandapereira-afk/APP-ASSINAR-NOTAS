package com.thuan.instafloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    private static final String CHANNEL = "insta_capture";
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean captured;

    @Override public void onCreate() {
        super.onCreate();
        startCaptureForeground();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { finish(false); return START_NOT_STICKY; }
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("resultData");
        if (resultCode == 0 || data == null) { finish(false); return START_NOT_STICKY; }

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) { finish(false); return START_NOT_STICKY; }

        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) { finish(false); return START_NOT_STICKY; }

        new Handler(Looper.getMainLooper()).postDelayed(this::startSingleFrameCapture, 650);
        return START_NOT_STICKY;
    }

    private void startCaptureForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Captura do Insta Float", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Insta Float")
                .setContentText("Capturando o conteúdo atual…")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);
        startForeground(88, b.build());
    }

    private void startSingleFrameCapture() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) { finish(false); return; }
            wm.getDefaultDisplay().getRealMetrics(dm);
            int width = dm.widthPixels;
            int height = dm.heightPixels;
            int density = dm.densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                if (captured) return;
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    captured = true;
                    Bitmap bitmap = imageToBitmap(image, width, height);
                    boolean ok = bitmap != null && saveBitmap(bitmap);
                    if (bitmap != null) bitmap.recycle();
                    finish(ok);
                } catch (Exception e) {
                    finish(false);
                } finally {
                    if (image != null) image.close();
                }
            }, new Handler(Looper.getMainLooper()));

            virtualDisplay = projection.createVirtualDisplay(
                    "InstaFloatCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!captured) finish(false);
            }, 4000);
        } catch (Exception e) {
            finish(false);
        }
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private boolean saveBitmap(Bitmap bitmap) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "InstaFloat_" + stamp + ".jpg";
        OutputStream out = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/InstaFloat");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                out = resolver.openOutputStream(uri);
                if (out == null) return false;
                boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                out.close(); out = null;
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return ok;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "InstaFloat");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File file = new File(dir, name);
                out = new FileOutputStream(file);
                boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                out.flush();
                return ok;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void finish(boolean success) {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}

        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this,
                        success ? "Captura salva em Fotos/Pictures/InstaFloat" : "Não foi possível capturar esta tela",
                        Toast.LENGTH_LONG).show()
        );

        Intent show = new Intent(this, FloatingService.class);
        show.setAction(FloatingService.ACTION_SHOW_BUBBLE);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(show); else startService(show);
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

package com.piesocket.channels;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps a {@code MediaProjection} screen capture alive —
 * a platform requirement on Android 10+ (enforced hard on 14+). Declared with
 * {@code foregroundServiceType="mediaProjection"} in this SDK's manifest and
 * merged into the host app automatically; the app never touches it directly.
 * {@link PieRTC#shareScreen()} starts and stops it.
 */
public class PieScreenCaptureService extends Service {

    private static final int NOTIFICATION_ID = 0x50E50C;
    private static final String CHANNEL_ID = "piesocket_screen_share";

    public class LocalBinder extends Binder {
        PieScreenCaptureService getService() {
            return PieScreenCaptureService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundInternal();
        return START_NOT_STICKY;
    }

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW));
        }

        int icon = getApplicationInfo().icon;
        if (icon == 0) {
            icon = android.R.drawable.ic_menu_share;
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("Sharing your screen")
                .setContentText("Tap to return to the app")
                .setSmallIcon(icon)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, PieScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, PieScreenCaptureService.class));
    }
}

package ru.cuxtoms.flashx;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;

public class TorchService extends Service {

    public static final String ACTION_APPLY = "ru.cuxtoms.flashx.ACTION_APPLY";
    public static final String ACTION_TOGGLE = "ru.cuxtoms.flashx.ACTION_TOGGLE";
    public static final String ACTION_OFF = "ru.cuxtoms.flashx.ACTION_OFF";
    public static final String ACTION_STATE = "ru.cuxtoms.flashx.ACTION_STATE";

    private static final String CHANNEL = "flashx_torch";
    private static final int NOTIF_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LedController controller;
    private Runnable autoOffRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        controller = LedController.get(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_APPLY : intent.getAction();

        if (ACTION_OFF.equals(action)) {
            controller.setOn(false);
            sendState();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification());

        if (ACTION_TOGGLE.equals(action)) {
            controller.discover();
            controller.tileToggle();
            if (!controller.isOn()) {
                stopForeground(true);
                stopSelf();
                sendState();
                return START_NOT_STICKY;
            }
        } else if (!controller.isCapture()) {
            controller.setOn(false);
            stopForeground(true);
            stopSelf();
            sendState();
            return START_NOT_STICKY;
        }

        controller.applyNow();
        scheduleAutoOff();
        sendState();
        return START_STICKY;
    }

    private void scheduleAutoOff() {
        if (autoOffRunnable != null) handler.removeCallbacks(autoOffRunnable);
        int minutes = controller.getAutoOffMin();
        if (minutes <= 0) return;
        autoOffRunnable = new Runnable() {
            @Override
            public void run() {
                controller.setOn(false);
                sendState();
                stopForeground(true);
                stopSelf();
            }
        };
        handler.postDelayed(autoOffRunnable, minutes * 60_000L);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Intent off = new Intent(this, TorchService.class);
        off.setAction(ACTION_OFF);
        PendingIntent offPi = PendingIntent.getService(this, 11, off, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        int enabled = 0, max = 0;
        for (LedController.Led led : controller.getLeds()) {
            if (led.enabled && led.percent > 0) { enabled++; max = Math.max(max, led.percent); }
        }
        String text = enabled + " LED · " + max + "%";
        if (controller.isBoost()) text += " · BOOST";

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_flash)
                .setContentTitle("JailFlash включён")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(0xFFFFC44D)
                .addAction(new Notification.Action.Builder(null, "Выключить", offPi).build()).build();
    }

    private static int immutableFlag() { return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "JailFlash", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false); ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void sendState() {
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                TileService.requestListeningState(this, new ComponentName(this, FlashXTileService.class));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        if (autoOffRunnable != null) handler.removeCallbacks(autoOffRunnable);
        controller.setOn(false);
        sendState();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
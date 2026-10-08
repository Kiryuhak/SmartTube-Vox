package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCategory;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxLogCode;
import com.liskovsoft.smartyoutubetv2.common.vox.diagnostics.VoxSafeLogger;

/**
 * Фоновая служба удержания воспроизведения аудио (audio-only) на Android 8+ и Android 11+ (API 30+).
 * Предотвращает заморозку процесса системой при переходе на домашний экран.
 */
public class BackgroundPlaybackService extends Service {
    private static final String TAG = BackgroundPlaybackService.class.getSimpleName();
    public static final String CHANNEL_ID = "vox_background_playback";
    public static final int NOTIFICATION_ID = 2446;
    private static volatile boolean sIsRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SmartTube VOX Фоновое воспроизведение",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Уведомление фонового воспроизведения звука");
            channel.setShowBadge(false);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("SmartTube VOX")
                    .setContentText("Фоновое воспроизведение активно")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .build();

            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                sIsRunning = true;
                VoxSafeLogger.info(
                        VoxLogCategory.BACKGROUND,
                        VoxLogCode.BACKGROUND_SERVICE_STARTED,
                        "Служба фонового воспроизведения запущена"
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service: %s", e.getMessage());
            }
        } else {
            sIsRunning = true;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: %s", Helpers.toString(intent));
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: %s", Helpers.toString(intent));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sIsRunning = false;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
        }
        VoxSafeLogger.info(
                VoxLogCategory.BACKGROUND,
                VoxLogCode.BACKGROUND_SERVICE_STOPPED,
                "Служба фонового воспроизведения остановлена"
        );
        super.onDestroy();
    }

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static void start(Context context) {
        if (context == null || sIsRunning) {
            return;
        }
        try {
            Intent serviceIntent = new Intent(context.getApplicationContext(), BackgroundPlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.getApplicationContext().startForegroundService(serviceIntent);
            } else {
                context.getApplicationContext().startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start BackgroundPlaybackService: %s", e.getMessage());
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent serviceIntent = new Intent(context.getApplicationContext(), BackgroundPlaybackService.class);
            context.getApplicationContext().stopService(serviceIntent);
            sIsRunning = false;
        } catch (Exception e) {
            Log.e(TAG, "Could not stop BackgroundPlaybackService: %s", e.getMessage());
        }
    }
}

/*
 Copyright 2013 Sebastián Katzer

 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package de.appplant.cordova.plugin.background;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

/**
 * ForegroundService compliant with Android 15 (SDK 35) restrictions.
 * Prevents crash when started automatically from background.
 */
public class ForegroundService extends Service {

    public static final int NOTIFICATION_ID = -574543954;
    private static final String NOTIFICATION_TITLE = "App is running in background";
    private static final String NOTIFICATION_TEXT = "Doing heavy tasks.";
    private static final String NOTIFICATION_ICON = "icon";

    private final IBinder binder = new ForegroundBinder();
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    class ForegroundBinder extends Binder {
        ForegroundService getService() {
            return ForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT < 34 || isAppInForeground()) {
            keepAwake();
        } else {
            Log.w("ForegroundService",
                "Skipping startForeground: app not in foreground (Android 14+)");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If the app returns to foreground, ensure foreground service is started
        if (Build.VERSION.SDK_INT >= 34 && isAppInForeground() && !isForeground()) {
            keepAwake();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sleepWell();
    }

    @SuppressLint("WakelockTimeout")
    private void keepAwake() {
        try {
            JSONObject settings = BackgroundMode.getSettings();
            boolean isSilent = settings.optBoolean("silent", false);

            if (!isSilent) {
                Notification notification = makeNotification();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    );
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            }

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PARTIAL_WAKE_LOCK, "backgroundmode:wakelock");
            wakeLock.acquire();
        } catch (Exception e) {
            Log.e("ForegroundService", "Failed to start foreground service", e);
        }
    }

    private void sleepWell() {
        try {
            stopForeground(true);
            getNotificationManager().cancel(NOTIFICATION_ID);
            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
            stopSelf();
        } catch (Exception e) {
            Log.e("ForegroundService", "Error while stopping service", e);
        }
    }

    private Notification makeNotification() {
        return makeNotification(BackgroundMode.getSettings());
    }

    private Notification makeNotification(JSONObject settings) {
        Context context = getApplicationContext();
        NotificationManager nm = getNotificationManager();

        String channelId = "background_mode";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Background Mode",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder =
                new Notification.Builder(context, channelId)
                        .setContentTitle(settings.optString("title", NOTIFICATION_TITLE))
                        .setContentText(settings.optString("text", NOTIFICATION_TEXT))
                        .setSmallIcon(context.getResources().getIdentifier(
                                settings.optString("icon", NOTIFICATION_ICON),
                                "drawable",
                                context.getPackageName()))
                        .setContentIntent(contentIntent)
                        .setOngoing(true);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_MIN);
        }

        return builder.build();
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private boolean isAppInForeground() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        java.util.List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return false;

        final String packageName = getPackageName();
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && info.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isForeground() {
        return wakeLock != null && wakeLock.isHeld();
    }
}

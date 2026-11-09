package com.kodama.xingcetimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs the countdown independently of the UI, so the alarm still fires while
 * the screen is off or another app is in front. This replaces the desktop
 * version's always-on-top window.
 */
public class TimerService extends Service {

    public static final String ACTION_START = "com.kodama.xingcetimer.action.START";
    public static final String ACTION_STOP = "com.kodama.xingcetimer.action.STOP";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_MINUTES = "minutes";
    public static final String EXTRA_PIPELINE = "pipeline";
    public static final String EXTRA_INDEX = "index";

    private static final String CHANNEL_ID = "timer";
    private static final int NOTIFICATION_ID = 1001;
    private static final long ALERT_MS = 5000L;
    private static final long TICK_MS = 200L;

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Stage> stages = new ArrayList<>();

    private boolean running;
    private boolean paused;
    private boolean alerting;
    private boolean finishedAll;
    private boolean pipeline;
    private int index;
    private long endAt;
    private long pausedRemaining;
    private long alertDeadline;
    private long lastNotificationAt;

    private NotificationManager notificationManager;
    private boolean foregroundStarted;

    private MediaPlayer alarmPlayer;
    private ToneGenerator toneGenerator;
    private boolean usingTone;
    private long nextToneAt;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    public class LocalBinder extends Binder {
        public TimerService getService() {
            return TimerService.this;
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (alerting) {
                if (usingTone && toneGenerator != null && now >= nextToneAt) {
                    try {
                        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500);
                    } catch (Exception ignored) {
                    }
                    nextToneAt = now + 2200L;
                }
                if (!finishedAll && alertDeadline > 0 && now >= alertDeadline) {
                    stopAlarm();
                    index++;
                    beginStage();
                    return;
                }
            } else if (!paused && now >= endAt) {
                stageEnded();
            }
            updateNotification(false);
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XingceTimer:countdown");
            wakeLock.setReferenceCounted(false);
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finishSession();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        ArrayList<String> names = intent.getStringArrayListExtra(EXTRA_NAMES);
        double[] minutes = intent.getDoubleArrayExtra(EXTRA_MINUTES);
        if (names == null || minutes == null || names.isEmpty()
                || names.size() != minutes.length) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stages.clear();
        for (int i = 0; i < names.size(); i++) {
            if (minutes[i] <= 0) {
                stopSelf();
                return START_NOT_STICKY;
            }
            stages.add(new Stage(names.get(i), minutes[i]));
        }
        pipeline = intent.getBooleanExtra(EXTRA_PIPELINE, false);
        index = intent.getIntExtra(EXTRA_INDEX, 0);
        if (index < 0 || index >= stages.size()) {
            index = 0;
        }

        running = true;
        startForegroundCompat();
        acquireWakeLock();
        beginStage();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopAlarm();
        releaseWakeLock();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- state

    private void beginStage() {
        alerting = false;
        finishedAll = false;
        paused = false;
        alertDeadline = 0;
        stopAlarm();

        endAt = SystemClock.elapsedRealtime()
                + (long) (stages.get(index).minutes * 60000.0);
        running = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        updateNotification(true);
    }

    private void stageEnded() {
        alerting = true;
        paused = false;
        boolean hasMore = pipeline && index + 1 < stages.size();
        if (hasMore) {
            alertDeadline = SystemClock.elapsedRealtime() + ALERT_MS;
        } else {
            finishedAll = true;
            alertDeadline = 0;
            if (pipeline) {
                // "全部完成！" is rendered from finishedAll by the activity.
            }
        }
        startAlarm();
        updateNotification(true);
    }

    private void finishSession() {
        running = false;
        paused = false;
        alerting = false;
        finishedAll = false;
        handler.removeCallbacks(ticker);
        stopAlarm();
        releaseWakeLock();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        stopSelf();
    }

    public void togglePause() {
        if (!running || alerting) {
            return;
        }
        if (!paused) {
            pausedRemaining = Math.max(0, endAt - SystemClock.elapsedRealtime());
            paused = true;
        } else {
            endAt = SystemClock.elapsedRealtime() + pausedRemaining;
            paused = false;
        }
        updateNotification(true);
    }

    public void skip() {
        if (!running || alerting) {
            return;
        }
        if (pipeline && index + 1 < stages.size()) {
            index++;
            beginStage();
        } else {
            finishSession();
        }
    }

    public void acknowledge() {
        if (!running || !alerting) {
            return;
        }
        stopAlarm();
        if (finishedAll) {
            finishSession();
        } else {
            index++;
            beginStage();
        }
    }

    public void stopSession() {
        finishSession();
    }

    // ----------------------------------------------------------- getters

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isAlerting() {
        return alerting;
    }

    public boolean isFinishedAll() {
        return finishedAll;
    }

    public boolean isPipeline() {
        return pipeline;
    }

    public int getIndex() {
        return index;
    }

    public int getCount() {
        return stages.size();
    }

    public boolean hasNext() {
        return pipeline && index + 1 < stages.size();
    }

    public String getStageName() {
        return stages.isEmpty() ? "" : stages.get(Math.min(index, stages.size() - 1)).name;
    }

    public double getStageMinutes() {
        return stages.isEmpty() ? 0 : stages.get(Math.min(index, stages.size() - 1)).minutes;
    }

    public String getNextName() {
        return hasNext() ? stages.get(index + 1).name : null;
    }

    public double getNextMinutes() {
        return hasNext() ? stages.get(index + 1).minutes : 0;
    }

    public long getRemainingMs() {
        if (!running || alerting) {
            return 0;
        }
        if (paused) {
            return Math.max(0, pausedRemaining);
        }
        return Math.max(0, endAt - SystemClock.elapsedRealtime());
    }

    public long getAlertRemainingMs() {
        if (!alerting || alertDeadline <= 0) {
            return 0;
        }
        return Math.max(0, alertDeadline - SystemClock.elapsedRealtime());
    }

    // ------------------------------------------------------- notification

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        foregroundStarted = true;
    }

    private void updateNotification(boolean force) {
        if (!running || notificationManager == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotificationAt < 950L) {
            return;
        }
        lastNotificationAt = now;
        Notification n = buildNotification();
        if (foregroundStarted) {
            notificationManager.notify(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        String title = stages.isEmpty() ? "行测计时器" : stages.get(index).name;
        String text;
        if (alerting) {
            text = finishedAll ? "时间到，点按查看" : "时间到，即将进入下一项";
        } else if (paused) {
            text = String.format(Locale.US, "已暂停 · %s",
                    StageStore.formatDuration(getRemainingMs()));
        } else {
            String time = StageStore.formatDuration(getRemainingMs());
            text = pipeline
                    ? String.format(Locale.US, "%s · 第 %d/%d 段", time, index + 1, stages.size())
                    : time;
        }

        Intent open = new Intent(this, RunActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(0xFFFFB454)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    // -------------------------------------------------------------- alarm

    private void startAlarm() {
        stopAlarm();
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (uri != null) {
            try {
                MediaPlayer player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setDataSource(this, uri);
                player.setLooping(true);
                player.prepare();
                player.start();
                alarmPlayer = player;
            } catch (Exception ignored) {
                alarmPlayer = null;
            }
        }
        if (alarmPlayer == null) {
            usingTone = true;
            nextToneAt = SystemClock.elapsedRealtime() + 2200L;
            try {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500);
            } catch (Exception ignored) {
                toneGenerator = null;
            }
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 600, 350}, 0));
            } catch (Exception ignored) {
            }
        }
    }

    private void stopAlarm() {
        if (alarmPlayer != null) {
            try {
                alarmPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                alarmPlayer.release();
            } catch (Exception ignored) {
            }
            alarmPlayer = null;
        }
        if (toneGenerator != null) {
            try {
                toneGenerator.stopTone();
                toneGenerator.release();
            } catch (Exception ignored) {
            }
            toneGenerator = null;
        }
        usingTone = false;
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------ wake lock

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }
}

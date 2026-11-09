package com.kodama.xingcetimer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/** Full-screen countdown, driven by {@link TimerService}. */
public class RunActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TimerService service;
    private boolean bound;
    private long startedAt;
    private boolean finishing;

    private TextView seqText;
    private TextView stageText;
    private TextView timeText;
    private TextView nextText;
    private Button btnPause;
    private Button btnSkip;
    private Button btnStop;
    private Button btnAlert;

    private int blinkCounter;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TimerService.LocalBinder) binder).getService();
            bound = true;
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 200L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_run);

        seqText = findViewById(R.id.seqText);
        stageText = findViewById(R.id.stageText);
        timeText = findViewById(R.id.timeText);
        nextText = findViewById(R.id.nextText);
        btnPause = findViewById(R.id.btnPause);
        btnSkip = findViewById(R.id.btnSkip);
        btnStop = findViewById(R.id.btnStop);
        btnAlert = findViewById(R.id.btnAlert);

        btnPause.setOnClickListener(v -> {
            if (service != null) {
                service.togglePause();
                render();
            }
        });
        btnSkip.setOnClickListener(v -> {
            if (service != null) {
                service.skip();
                render();
            }
        });
        btnStop.setOnClickListener(v -> confirmStop());
        btnAlert.setOnClickListener(v -> {
            if (service != null) {
                service.acknowledge();
                render();
            }
        });

        goFullscreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startedAt = System.currentTimeMillis();
        bound = bindService(new Intent(this, TimerService.class), connection,
                Context.BIND_AUTO_CREATE);
        handler.post(poller);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(poller);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        service = null;
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            goFullscreen();
        }
    }

    private void goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void render() {
        TimerService s = service;
        if (s == null) {
            return;
        }
        if (!s.isRunning()) {
            // The service may still be starting right after MainActivity launched it.
            if (System.currentTimeMillis() - startedAt > 2500L) {
                finish();
            }
            return;
        }

        boolean alert = s.isAlerting();
        boolean paused = s.isPaused();
        boolean finishedAll = s.isFinishedAll();
        blinkCounter++;

        String seq;
        if (alert && finishedAll) {
            seq = s.isPipeline() ? "流水线完成" : "单独计时完成";
        } else {
            seq = s.isPipeline()
                    ? String.format(Locale.US, "流水线 第 %d/%d 项", s.getIndex() + 1, s.getCount())
                    : "单独计时";
        }
        if (paused) {
            seq = seq + " · 已暂停";
        }
        seqText.setText(seq);

        String stage;
        if (alert && finishedAll) {
            stage = s.isPipeline() ? "全部完成！" : s.getStageName() + " 时间到！";
        } else {
            stage = s.getStageName();
        }
        stageText.setText(stage);

        timeText.setText(alert ? "00:00" : StageStore.formatDuration(s.getRemainingMs()));
        if (alert) {
            boolean bright = (blinkCounter / 3) % 2 == 0;
            timeText.setTextColor(getColor(bright ? R.color.danger : R.color.text));
        } else if (paused) {
            timeText.setTextColor(getColor(R.color.muted));
        } else {
            timeText.setTextColor(getColor(R.color.text));
        }

        String next;
        if (alert) {
            if (finishedAll) {
                next = "点“知道了”停止铃声并返回";
            } else {
                int seconds = (int) Math.max(1, Math.ceil(s.getAlertRemainingMs() / 1000.0));
                next = String.format(Locale.US, "%d 秒后自动开始：%s（%s 分钟）",
                        seconds, s.getNextName(), StageStore.formatMinutes(s.getNextMinutes()));
            }
        } else if (s.isPipeline()) {
            next = s.hasNext()
                    ? String.format(Locale.US, "接下来：%s（%s 分钟）",
                    s.getNextName(), StageStore.formatMinutes(s.getNextMinutes()))
                    : "这是最后一项";
        } else {
            next = "到点会响铃提醒你";
        }
        nextText.setText(next);

        btnPause.setVisibility(alert ? View.GONE : View.VISIBLE);
        btnSkip.setVisibility(alert ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        btnAlert.setVisibility(alert ? View.VISIBLE : View.GONE);
        btnPause.setText(paused ? "继续" : "暂停");
        btnAlert.setText(finishedAll ? "知道了" : "立即开始下一项");
    }

    private void confirmStop() {
        if (finishing) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("确定要停止计时吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止", (dialog, which) -> {
                    finishing = true;
                    if (service != null) {
                        service.stopSession();
                    }
                    finish();
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        confirmStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

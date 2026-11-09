package com.kodama.xingcetimer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private final ArrayList<Stage> stages = new ArrayList<>();
    private StageAdapter adapter;
    private ListView list;
    private TextView totalValue;
    private TextView totalCaption;
    private TextView selectedInfo;
    private TextView emptyHint;
    private Button btnEdit;
    private Button btnDel;
    private Button btnUp;
    private Button btnDown;
    private Button btnSingle;

    private int selected = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.stageList);
        totalValue = findViewById(R.id.totalValue);
        totalCaption = findViewById(R.id.totalCaption);
        selectedInfo = findViewById(R.id.selectedInfo);
        emptyHint = findViewById(R.id.emptyHint);
        btnEdit = findViewById(R.id.btnEdit);
        btnDel = findViewById(R.id.btnDel);
        btnUp = findViewById(R.id.btnUp);
        btnDown = findViewById(R.id.btnDown);
        btnSingle = findViewById(R.id.btnSingle);

        stages.addAll(StageStore.load(this));
        if (state != null) {
            selected = state.getInt("selected", -1);
        }
        if (selected >= stages.size()) {
            selected = -1;
        }

        adapter = new StageAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected = position;
            adapter.notifyDataSetChanged();
            updateUi();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            selected = position;
            adapter.notifyDataSetChanged();
            updateUi();
            showEditDialog(position);
            return true;
        });

        findViewById(R.id.btnAdd).setOnClickListener(v -> showEditDialog(-1));
        btnEdit.setOnClickListener(v -> {
            if (selected >= 0) {
                showEditDialog(selected);
            }
        });
        btnDel.setOnClickListener(v -> confirmDelete());
        btnUp.setOnClickListener(v -> moveSelected(-1));
        btnDown.setOnClickListener(v -> moveSelected(1));
        btnSingle.setOnClickListener(v -> startSession(false));
        findViewById(R.id.btnPipe).setOnClickListener(v -> startSession(true));

        updateUi();
        requestNotificationPermission();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("selected", selected);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void updateUi() {
        double total = 0;
        for (Stage s : stages) {
            total += s.minutes;
        }
        totalValue.setText(String.format(Locale.US, "%s 分钟", StageStore.formatMinutes(total)));
        totalCaption.setText(String.format(Locale.US, "共 %d 项", stages.size()));

        boolean has = selected >= 0 && selected < stages.size();
        btnEdit.setEnabled(has);
        btnDel.setEnabled(has);
        btnSingle.setEnabled(has);
        btnUp.setEnabled(has && selected > 0);
        btnDown.setEnabled(has && selected < stages.size() - 1);

        if (has) {
            Stage s = stages.get(selected);
            selectedInfo.setText(String.format(Locale.US, "已选：%s · %s 分钟",
                    s.name, StageStore.formatMinutes(s.minutes)));
        } else {
            selectedInfo.setText(stages.isEmpty() ? "先添加一项" : "未选择（点按清单中的一项）");
        }
        emptyHint.setVisibility(stages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showEditDialog(final int position) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit_stage, null, false);
        final EditText name = content.findViewById(R.id.editName);
        final EditText minutes = content.findViewById(R.id.editMinutes);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        final Stage current = position >= 0 && position < stages.size() ? stages.get(position) : null;
        if (current != null) {
            name.setText(current.name);
            minutes.setText(StageStore.formatMinutes(current.minutes));
        } else {
            minutes.setText("10");
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(current == null ? "添加闹钟" : "修改闹钟")
                .setView(content)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String newName = name.getText().toString().trim();
                    if (newName.isEmpty()) {
                        name.setError("名称不能为空");
                        return;
                    }
                    double newMinutes;
                    try {
                        newMinutes = Double.parseDouble(minutes.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        minutes.setError("请输入 0.5 – 600 之间的分钟数");
                        return;
                    }
                    if (!(newMinutes >= 0.5) || newMinutes > 600
                            || Double.isNaN(newMinutes) || Double.isInfinite(newMinutes)) {
                        minutes.setError("请输入 0.5 – 600 之间的分钟数");
                        return;
                    }
                    if (current != null) {
                        stages.set(position, new Stage(newName, newMinutes));
                        selected = position;
                    } else {
                        stages.add(new Stage(newName, newMinutes));
                        selected = stages.size() - 1;
                    }
                    StageStore.save(MainActivity.this, stages);
                    adapter.notifyDataSetChanged();
                    list.setSelection(selected);
                    updateUi();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void confirmDelete() {
        if (selected < 0 || selected >= stages.size()) {
            return;
        }
        final int position = selected;
        Stage s = stages.get(position);
        new AlertDialog.Builder(this)
                .setMessage(String.format(Locale.US, "确定删除“%s”吗？", s.name))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    stages.remove(position);
                    StageStore.save(this, stages);
                    selected = stages.isEmpty() ? -1 : Math.min(position, stages.size() - 1);
                    adapter.notifyDataSetChanged();
                    updateUi();
                })
                .show();
    }

    private void moveSelected(int delta) {
        int target = selected + delta;
        if (selected < 0 || target < 0 || target >= stages.size()) {
            return;
        }
        Stage tmp = stages.get(selected);
        stages.set(selected, stages.get(target));
        stages.set(target, tmp);
        selected = target;
        StageStore.save(this, stages);
        adapter.notifyDataSetChanged();
        list.setSelection(selected);
        updateUi();
    }

    private void startSession(boolean pipeline) {
        if (stages.isEmpty()) {
            return;
        }
        int index = pipeline ? 0 : selected;
        if (index < 0 || index >= stages.size()) {
            return;
        }

        ArrayList<String> names = new ArrayList<>(stages.size());
        double[] minutes = new double[stages.size()];
        for (int i = 0; i < stages.size(); i++) {
            names.add(stages.get(i).name);
            minutes[i] = stages.get(i).minutes;
        }

        Intent intent = new Intent(this, TimerService.class)
                .setAction(TimerService.ACTION_START)
                .putStringArrayListExtra(TimerService.EXTRA_NAMES, names)
                .putExtra(TimerService.EXTRA_MINUTES, minutes)
                .putExtra(TimerService.EXTRA_PIPELINE, pipeline)
                .putExtra(TimerService.EXTRA_INDEX, index);
        startForegroundService(intent);
        startActivity(new Intent(this, RunActivity.class));
    }

    private class StageAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return stages.size();
        }

        @Override
        public Object getItem(int position) {
            return stages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_stage, parent, false);
            }
            Stage s = stages.get(position);
            TextView name = convertView.findViewById(R.id.stageName);
            TextView mins = convertView.findViewById(R.id.stageMinutes);
            name.setText(s.name);
            mins.setText(String.format(Locale.US, "%s 分钟", StageStore.formatMinutes(s.minutes)));
            convertView.setActivated(position == selected);
            return convertView;
        }
    }
}

package com.kodama.xingcetimer;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persists the stage list as "name<TAB>minutes" lines, the same format the
 * desktop version used, so an old stages.txt can be dropped in unchanged.
 */
public final class StageStore {

    private static final String FILE_NAME = "stages.txt";

    private StageStore() {
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static ArrayList<Stage> load(Context context) {
        ArrayList<Stage> list = new ArrayList<>();
        File f = file(context);
        if (!f.exists()) {
            return defaults();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = r.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String name = line.substring(0, tab).trim();
                String number = line.substring(tab + 1).trim();
                if (name.isEmpty()) {
                    continue;
                }
                double minutes;
                try {
                    minutes = Double.parseDouble(number);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (!(minutes > 0) || Double.isInfinite(minutes) || Double.isNaN(minutes)) {
                    continue;
                }
                list.add(new Stage(name, minutes));
            }
        } catch (Exception ignored) {
        }
        return list.isEmpty() ? defaults() : list;
    }

    public static void save(Context context, List<Stage> stages) {
        File target = file(context);
        File tmp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (Stage s : stages) {
                w.write(s.name.replace('\t', ' '));
                w.write('\t');
                w.write(formatMinutes(s.minutes));
                w.write('\n');
            }
        } catch (Exception ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(target);
    }

    public static ArrayList<Stage> defaults() {
        ArrayList<Stage> list = new ArrayList<>();
        list.add(new Stage("常识判断", 10));
        list.add(new Stage("言语理解与表达", 30));
        list.add(new Stage("数量关系", 15));
        list.add(new Stage("判断推理", 30));
        list.add(new Stage("资料分析", 30));
        list.add(new Stage("涂卡检查", 5));
        return list;
    }

    public static String formatMinutes(double minutes) {
        if (Math.abs(minutes - Math.rint(minutes)) < 0.0000001) {
            return Long.toString((long) Math.rint(minutes));
        }
        String s = String.format(Locale.US, "%.2f", minutes);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Ceiling to whole seconds, matching the desktop timer. */
    public static String formatDuration(long remainingMs) {
        long totalSec = Math.max(0, (remainingMs + 999) / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}

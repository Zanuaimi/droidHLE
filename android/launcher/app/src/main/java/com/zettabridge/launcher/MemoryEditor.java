package com.zettabridge.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;

import com.zettabridge.core.ZBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cheat-Engine-style value hunting in a translated game: scan the guest's writable memory for a
 * value, narrow the hits down as it changes in game, then set or freeze the ones left. A floating
 * button (its own window, so it shows over NativeActivity surfaces too) opens the panel.
 */
final class MemoryEditor {
    private static final int LIMIT = 2_000_000;
    private static final int SHOWN = 200;
    private static final long FREEZE_MS = 100;
    private static final String[] TYPES = {"4-byte integer", "Float"};

    private enum Filter { EXACT, CHANGED, UNCHANGED, INCREASED, DECREASED }

    private static final class Frozen {
        final int address;
        final int type;
        final double value;

        Frozen(int address, int type, double value) {
            this.address = address;
            this.type = type;
            this.value = value;
        }
    }

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Frozen> frozen = new ArrayList<>();  // main thread only
    private PopupWindow button;
    private int type = ZBridge.MEM_INT32;
    private int[] hits;      // null before the first scan
    private double[] values; // the hits' values when last read

    private final Runnable freezer = new Runnable() {
        @Override
        public void run() {
            for (Frozen f : frozen) ZBridge.memWrite(f.type, f.address, f.value);
            if (!frozen.isEmpty()) main.postDelayed(this, FREEZE_MS);
        }
    };

    private MemoryEditor(Activity activity) {
        this.activity = activity;
    }

    static void attach(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> new MemoryEditor(activity).showButton(decor));
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void showButton(View decor) {
        TextView view = new TextView(activity);
        view.setText("MEM");
        view.setTextColor(0xFFFFFFFF);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        view.setBackground(bg);
        int size = dp(48);
        button = new PopupWindow(view, size, size, false);
        final int[] pos = {dp(8), dp(96)};
        final float[] down = new float[4];
        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    down[2] = pos[0];
                    down[3] = pos[1];
                    return true;
                case MotionEvent.ACTION_MOVE:
                    pos[0] = (int) (down[2] + e.getRawX() - down[0]);
                    pos[1] = (int) (down[3] + e.getRawY() - down[1]);
                    button.update(pos[0], pos[1], -1, -1);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.abs(e.getRawX() - down[0]) < slop && Math.abs(e.getRawY() - down[1]) < slop) showPanel();
                    return true;
                default:
                    return false;
            }
        });
        button.showAtLocation(decor, Gravity.TOP | Gravity.START, pos[0], pos[1]);
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                button.dismiss();
                frozen.clear();
                main.removeCallbacks(freezer);
                worker.shutdownNow();
            }
        });
    }

    private void showPanel() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), 0);

        Spinner types = new Spinner(activity);
        types.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, TYPES));
        types.setSelection(type);
        body.addView(types);

        EditText value = new EditText(activity);
        value.setHint("Value");
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        body.addView(value);

        TextView status = new TextView(activity);
        status.setPadding(0, dp(8), 0, dp(8));
        ArrayAdapter<String> rows = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        ListView list = new ListView(activity);

        Runnable refresh = () -> refresh(status, rows);
        LinearLayout scans = row();
        scans.addView(button("New scan", v -> {
            Double target = parse(value);
            if (target == null) return;
            type = types.getSelectedItemPosition();
            status.setText("Scanning...");
            int scanType = type;
            double tolerance = tolerance(value.getText().toString(), scanType);
            run(() -> {
                int[] found = ZBridge.memScan(scanType, target, tolerance, LIMIT);
                double[] read = ZBridge.memRead(scanType, found);
                main.post(() -> {
                    hits = found;
                    values = read;
                    refresh.run();
                });
            });
        }), weight());
        scans.addView(button("Next scan", v -> {
            Double target = parse(value);
            if (target != null) narrow(Filter.EXACT, target, tolerance(value.getText().toString(), type), status, refresh);
        }), weight());
        body.addView(scans);

        LinearLayout changes = row();
        changes.addView(button("Changed", v -> narrow(Filter.CHANGED, 0, 0, status, refresh)), weight());
        changes.addView(button("Same", v -> narrow(Filter.UNCHANGED, 0, 0, status, refresh)), weight());
        changes.addView(button("Up", v -> narrow(Filter.INCREASED, 0, 0, status, refresh)), weight());
        changes.addView(button("Down", v -> narrow(Filter.DECREASED, 0, 0, status, refresh)), weight());
        body.addView(changes);

        body.addView(status);
        body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, v, position, id) -> {
            if (position < frozen.size()) {
                editValue(frozen.get(position).address, frozen.get(position).type, refresh);
            } else if (hits != null && position - frozen.size() < hits.length) {
                editValue(hits[position - frozen.size()], type, refresh);
            }
        });
        refresh.run();

        new AlertDialog.Builder(activity)
                .setTitle("Memory editor")
                .setView(body)
                .setPositiveButton("Close", null)
                .setNeutralButton("Reset", (d, w) -> {
                    hits = null;
                    values = null;
                })
                .show();
    }

    /** Keeps the hits that pass filter, comparing against their last values; reads them fresh. */
    private void narrow(Filter filter, double target, double tolerance, TextView status, Runnable refresh) {
        if (hits == null) {
            status.setText("Run a new scan first");
            return;
        }
        int[] from = hits;
        double[] before = values;
        int scanType = type;
        status.setText("Scanning...");
        run(() -> {
            double[] now = ZBridge.memRead(scanType, from);
            int n = 0;
            int[] keep = new int[from.length];
            double[] kept = new double[from.length];
            for (int i = 0; i < from.length; i++) {
                if (passes(filter, now[i], before[i], target, tolerance)) {
                    keep[n] = from[i];
                    kept[n++] = now[i];
                }
            }
            int[] narrowed = Arrays.copyOf(keep, n);
            double[] narrowedValues = Arrays.copyOf(kept, n);
            main.post(() -> {
                hits = narrowed;
                values = narrowedValues;
                refresh.run();
            });
        });
    }

    static boolean passes(Filter filter, double now, double before, double target, double tolerance) {
        if (Double.isNaN(now)) return false;
        switch (filter) {
            case EXACT: return Math.abs(now - target) <= tolerance;
            case CHANGED: return now != before;
            case UNCHANGED: return now == before;
            case INCREASED: return now > before;
            case DECREASED: return now < before;
            default: return false;
        }
    }

    /** Integers match exactly; floats match what was typed, rounded to its decimals (2.5 hits 2.45..2.55). */
    static double tolerance(String typed, int type) {
        if (type == ZBridge.MEM_INT32) return 0;
        int dot = typed.indexOf('.');
        int decimals = dot < 0 ? 0 : typed.length() - dot - 1;
        return 0.5 * Math.pow(10, -decimals);
    }

    private void refresh(TextView status, ArrayAdapter<String> rows) {
        rows.clear();
        for (Frozen f : frozen) rows.add(String.format(Locale.US, "* %08X  %s", f.address, format(f.value, f.type)));
        if (hits == null) {
            status.setText("Type a value you see in game, then New scan");
        } else {
            status.setText(String.format(Locale.US, "%,d results%s", hits.length,
                    hits.length >= LIMIT ? " (limit; narrow it down)" : hits.length > SHOWN ? ", first " + SHOWN + " shown" : ""));
            for (int i = 0; i < Math.min(hits.length, SHOWN); i++) {
                rows.add(String.format(Locale.US, "%08X  %s", hits[i], format(values[i], type)));
            }
        }
        rows.notifyDataSetChanged();
    }

    private void editValue(int address, int valueType, Runnable refresh) {
        double[] current = ZBridge.memRead(valueType, new int[] {address});
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(format(current[0], valueType));
        int pad = dp(20);
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int index = frozenIndex(address);
        new AlertDialog.Builder(activity)
                .setTitle(String.format(Locale.US, "%08X", address))
                .setView(box)
                .setPositiveButton("Set", (d, w) -> {
                    Double v = parse(input);
                    if (v != null) ZBridge.memWrite(valueType, address, v);
                    refresh.run();
                })
                .setNeutralButton(index >= 0 ? "Unfreeze" : "Freeze", (d, w) -> {
                    if (index >= 0) {
                        frozen.remove(index);
                    } else {
                        Double v = parse(input);
                        if (v == null) return;
                        frozen.add(new Frozen(address, valueType, v));
                        main.removeCallbacks(freezer);
                        freezer.run();
                    }
                    refresh.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int frozenIndex(int address) {
        for (int i = 0; i < frozen.size(); i++) {
            if (frozen.get(i).address == address) return i;
        }
        return -1;
    }

    private static String format(double v, int type) {
        if (Double.isNaN(v)) return "??";
        return type == ZBridge.MEM_INT32 ? Long.toString((long) v) : Float.toString((float) v);
    }

    private static Double parse(EditText input) {
        try {
            return Double.parseDouble(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            input.setError("Enter a number");
            return null;
        }
    }

    private void run(Runnable task) {
        if (!worker.isShutdown()) worker.execute(task);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }
}

package com.zettabridge.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Library screen (main process): a grid of imported apps. Tap to play; the gear opens per-app
 * settings (graphics, compatibility, debugging) and actions (home-screen shortcut, run report,
 * delete). UI built in code from framework widgets, themed by the system (One UI on Samsung).
 */
public class LibraryActivity extends Activity {
    private static final String TAG = "zb-launcher";
    private static final int REQUEST_IMPORT = 1;

    private final List<PluginRecord> plugins = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView subtitle;
    private TextView emptyView;
    private int accent;
    private int surface;
    private int secondaryText;
    private int primaryText;
    private int onAccent;

    @Override
    protected void onCreate(Bundle state) {
        setTheme(Build.VERSION.SDK_INT >= 29 ? android.R.style.Theme_DeviceDefault_DayNight
                                            : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(state);
        accent = themeColor(android.R.attr.colorAccent);
        surface = themeColor(android.R.attr.colorBackgroundFloating);
        secondaryText = themeColor(android.R.attr.textColorSecondary);
        primaryText = themeColor(android.R.attr.textColorPrimary);
        // Dark themes use a pale accent: put dark text on it there, white text otherwise.
        onAccent = Color.luminance(accent) > 0.5f ? Color.rgb(0x1B, 0x1B, 0x1F) : Color.WHITE;
        if (getActionBar() != null) getActionBar().hide();

        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(28), dp(20), 0);

        TextView title = new TextView(this);
        title.setText("Afterlife Manager");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(primaryText);
        column.addView(title);
        subtitle = new TextView(this);
        subtitle.setTextColor(secondaryText);
        subtitle.setPadding(0, dp(2), 0, dp(16));
        column.addView(subtitle);

        emptyView = new TextView(this);
        emptyView.setText("No games yet.\nImport an APK to add one.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(secondaryText);
        emptyView.setPadding(0, dp(96), 0, 0);
        column.addView(emptyView);

        GridView grid = new GridView(this);
        grid.setColumnWidth(dp(150));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(96));  // room under the import button
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return plugins.size();
            }

            @Override
            public Object getItem(int position) {
                return plugins.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return card(plugins.get(position));
            }
        };
        grid.setAdapter(adapter);
        column.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(column);

        Button importButton = new Button(this);
        importButton.setText("+  Import APK");
        importButton.setAllCaps(false);
        importButton.setTextColor(onAccent);
        importButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        importButton.setPadding(dp(24), 0, dp(24), 0);
        importButton.setBackground(rounded(accent, dp(28), true));
        importButton.setElevation(dp(6));
        importButton.setOnClickListener(v -> pickApk());
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56),
                Gravity.BOTTOM | Gravity.END);
        fab.setMargins(0, 0, dp(20), dp(24));
        frame.addView(importButton, fab);
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        plugins.clear();
        plugins.addAll(PluginStore.list(this));
        subtitle.setText(plugins.size() == 1 ? "1 game" : plugins.size() + " games");
        emptyView.setVisibility(plugins.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        repointPinnedShortcuts();
        publishLauncherShortcuts();
    }

    /**
     * Points shortcuts pinned by an older version at the router. They were pinned straight at the
     * guest entry point, which Android resolves to the running game's task instead of launching.
     */
    private void repointPinnedShortcuts() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            List<ShortcutInfo> updated = new ArrayList<>();
            for (ShortcutInfo pinned : sm.getPinnedShortcuts()) {
                for (PluginRecord r : plugins) {
                    if (!shortcutId(r).equals(pinned.getId())) continue;
                    ComponentName target = pinned.getIntent() != null ? pinned.getIntent().getComponent() : null;
                    if (target != null && PluginSwitchActivity.class.getName().equals(target.getClassName())) break;
                    updated.add(new ShortcutInfo.Builder(this, pinned.getId())
                            .setIntent(PluginSwitchActivity.intent(this, r.packageName))
                            .build());
                    break;
                }
            }
            if (!updated.isEmpty()) sm.updateShortcuts(updated);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot repoint the pinned shortcuts: " + e);
        }
    }

    /** The games also show when the ZettaBridge icon is long-pressed, up to the launcher's limit. */
    private void publishLauncherShortcuts() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null) return;
        try {
            List<ShortcutInfo> shortcuts = new ArrayList<>();
            for (PluginRecord r : plugins) {
                if (shortcuts.size() >= sm.getMaxShortcutCountPerActivity()) break;
                if (r.isLaunchable()) shortcuts.add(shortcut(r));
            }
            sm.setDynamicShortcuts(shortcuts);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot publish the launcher shortcuts: " + e);
        }
    }

    private View card(PluginRecord r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(16), dp(12), dp(12));
        card.setBackground(rounded(surface, dp(20), true));
        card.setOnClickListener(v -> launch(r));
        card.setOnLongClickListener(v -> {
            showSettings(r);
            return true;
        });

        FrameLayout top = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        Bitmap bmp = r.icon();
        if (bmp != null) {
            icon.setImageBitmap(bmp);
        } else {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        top.addView(icon, new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER));
        ImageButton gear = new ImageButton(this);
        gear.setImageResource(android.R.drawable.ic_menu_manage);
        gear.setImageTintList(ColorStateList.valueOf(secondaryText));
        gear.setBackground(rounded(Color.TRANSPARENT, dp(18), true));
        gear.setContentDescription("Settings for " + r.label);
        gear.setOnClickListener(v -> showSettings(r));
        top.addView(gear, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END));
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        TextView title = new TextView(this);
        title.setText(r.label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setTextColor(primaryText);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(8), 0, 0);
        card.addView(title);
        TextView status = new TextView(this);
        status.setText(r.isLaunchable() ? (r.isTranslated() ? "32-bit · translated" : "native") : r.status());
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(r.isLaunchable() ? secondaryText : Color.rgb(0xD3, 0x2F, 0x2F));
        status.setGravity(Gravity.CENTER);
        card.addView(status);
        return card;
    }

    /** Per-app settings and actions. Toggles save at once and apply the next time the game starts. */
    private void showSettings(PluginRecord r) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView info = new TextView(this);
        info.setText(r.packageName + (r.versionName != null ? "  ·  " + r.versionName : "") + "\n" + r.status());
        info.setTextColor(secondaryText);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.addView(info);

        AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, dp(4));
        actions.addView(action("Play", true, v -> {
            dialog[0].dismiss();
            launch(r);
        }), new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(44), 1f);
        second.setMarginStart(dp(8));
        actions.addView(action("Home screen", false, v -> pinShortcut(r)), second);
        body.addView(actions);

        section(body, "Graphics");
        toggle(body, r, "Anti-aliasing (4x MSAA)", "Smooth polygon edges", p -> p.msaa, (p, on) -> p.msaa = on);
        toggle(body, r, "Anisotropic filtering", "Sharper textures at an angle", p -> p.anisotropic,
                (p, on) -> p.anisotropic = on);
        toggle(body, r, "Full resolution", "Render at screen size; may misplace touches in some games",
                p -> p.fullResolution, (p, on) -> p.fullResolution = on);
        toggle(body, r, "High refresh rate", "Smoother on 120 Hz; fixed-timestep games run faster",
                p -> p.highRefresh, (p, on) -> p.highRefresh = on);
        section(body, "Compatibility");
        toggle(body, r, "Hide ads", "Blank out bundled ad SDK views", p -> p.hideAds, (p, on) -> p.hideAds = on);
        section(body, "Debugging");
        toggle(body, r, "Precise faults", "Exact crash locations; about 2x slower", p -> p.preciseFaults,
                (p, on) -> p.preciseFaults = on);
        toggle(body, r, "GL diagnostics", "Frame readbacks in the run report; very slow", p -> p.diagnostics,
                (p, on) -> p.diagnostics = on);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle(r.label)
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setNeutralButton("Run report", (d, w) -> showRuntimeReport(r))
                .setNegativeButton("Delete", (d, w) -> confirmDelete(r))
                .show();
    }

    private void toggle(LinearLayout body, PluginRecord r, String label, String hint, Predicate<PluginRecord> get,
                        BiConsumer<PluginRecord, Boolean> set) {
        Switch sw = new Switch(this);
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(label + "\n");
        int start = text.length();
        text.append(hint);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, text.length(), 0);
        text.setSpan(new android.text.style.ForegroundColorSpan(secondaryText), start, text.length(), 0);
        sw.setText(text);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setPadding(0, dp(10), 0, dp(10));
        sw.setChecked(get.test(r));
        sw.setOnCheckedChangeListener((button, on) -> {
            set.accept(r, on);
            try {
                r.save();
            } catch (IOException e) {
                Diagnostics.report(this, "cannot save the settings of " + r.label, e, false);
                return;
            }
            Toast.makeText(this, "Applies the next time " + r.label + " starts", Toast.LENGTH_SHORT).show();
        });
        body.addView(sw);
    }

    private void section(LinearLayout body, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextColor(accent);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setPadding(0, dp(16), 0, dp(2));
        body.addView(header);
    }

    private Button action(String text, boolean primary, Consumer<View> onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(primary ? onAccent : accent);
        GradientDrawable shape = (GradientDrawable) rounded(primary ? accent : Color.TRANSPARENT, dp(22), false);
        if (!primary) shape.setStroke(dp(1), accent);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), shape, null));
        b.setOnClickListener(onClick::accept);
        return b;
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[] {"application/vnd.android.package-archive", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importApk(data.getData());
        }
    }

    private void importApk(Uri uri) {
        // A running plugin keeps the old APK mapped: stop the guest process first.
        stopGuestProcess();
        AlertDialog progress = new AlertDialog.Builder(this).setMessage("Importing...").setCancelable(false).show();
        new Thread(() -> {
            String message;
            try {
                PluginRecord r = PluginStore.importApk(this, uri);
                message = "Imported " + r.label + " (" + r.status() + ")";
            } catch (Exception e) {
                Log.e(TAG, "import failed", e);
                message = "Import failed: " + e.getMessage();
            }
            String shown = message;
            runOnUiThread(() -> {
                progress.dismiss();
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "zb-import").start();
    }

    private void launch(PluginRecord r) {
        if (!r.isLaunchable()) {
            Toast.makeText(this, r.label + ": " + r.status(), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(PluginSwitchActivity.intent(this, r.packageName));
    }

    /**
     * What the last :guest run recorded, without a shell: OxygenOS drops third-party logcat
     * output, so this file is the only evidence a device run leaves behind. The text also goes to
     * the clipboard, ready to be sent back.
     */
    private void showRuntimeReport(PluginRecord r) {
        String report = Diagnostics.readRuntimeReport(this);
        if (report == null) {
            Toast.makeText(this, "No run report yet. Launch " + r.label + " once, then look again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File file = Diagnostics.runtimeReportFile(this);
        String text = report + (file != null ? "\nfile: " + file.getAbsolutePath() + "\n" : "");
        boolean copied = Diagnostics.copy(this, "Afterlife run report", text);

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int pad = dp(16);
        view.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(copied ? "Last run report (copied)" : "Last run report")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private static String shortcutId(PluginRecord r) {
        return "plugin:" + r.packageName;
    }

    private ShortcutInfo shortcut(PluginRecord r) {
        Bitmap bmp = r.icon();
        Icon icon = bmp != null ? Icon.createWithAdaptiveBitmap(adaptive(bmp))
                : Icon.createWithResource(this, android.R.drawable.sym_def_app_icon);
        return new ShortcutInfo.Builder(this, shortcutId(r))
                .setShortLabel(r.label)
                .setLongLabel(r.label)
                .setIcon(icon)
                .setIntent(PluginSwitchActivity.intent(this, r.packageName))
                .build();
    }

    /** Centers a legacy icon in the adaptive-icon safe zone (the inner 66%), on white. */
    private static Bitmap adaptive(Bitmap source) {
        int size = 216;  // 108 dp at 2x; launchers scale it
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(Color.WHITE);
        int inset = size / 6;
        canvas.drawBitmap(source, null, new android.graphics.Rect(inset, inset, size - inset, size - inset),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private void pinShortcut(PluginRecord r) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "The home screen does not support pinned shortcuts", Toast.LENGTH_LONG).show();
            return;
        }
        sm.requestPinShortcut(shortcut(r), null);
    }

    private void confirmDelete(PluginRecord r) {
        new AlertDialog.Builder(this)
                .setMessage("Delete " + r.label + " and its data?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    stopGuestProcess();
                    ShortcutManager sm = getSystemService(ShortcutManager.class);
                    if (sm != null) {
                        try {
                            sm.disableShortcuts(Collections.singletonList(shortcutId(r)), "App was deleted");
                        } catch (RuntimeException e) {
                            Log.w(TAG, "cannot disable shortcut: " + e);
                        }
                    }
                    PluginStore.delete(r);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Kills our own :guest process, if running (allowed for processes of the same uid). */
    private void stopGuestProcess() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null || am.getRunningAppProcesses() == null) return;
        for (ActivityManager.RunningAppProcessInfo proc : am.getRunningAppProcesses()) {
            if (proc.processName.endsWith(ZbApplication.GUEST_SUFFIX)) android.os.Process.killProcess(proc.pid);
        }
    }

    private android.graphics.drawable.Drawable rounded(int color, int radius, boolean ripple) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        if (!ripple) return shape;
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x33808080), shape, mask);
    }

    private int themeColor(int attr) {
        TypedArray a = obtainStyledAttributes(new int[] {attr});
        try {
            return a.getColor(0, Color.GRAY);
        } finally {
            a.recycle();
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}

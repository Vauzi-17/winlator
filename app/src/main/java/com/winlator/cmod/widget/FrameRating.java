package com.winlator.cmod.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.renderer.FpsLimiter;
import com.winlator.cmod.renderer.FrameGeneration;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The in-game HUD: a small monochrome card that can be dragged anywhere (it
 * snaps to the nearest side edge), tapped to switch template and long-pressed
 * (or its menu button tapped) for its menu.
 *
 * <p>Templates: Minimal (FPS), Compact (FPS, frame generation, frame-time line),
 * Full (everything) and Custom (what the user ticked). The menu also holds the
 * scale, the background opacity, the FPS limit and the frame generation
 * controls; it never grows past the screen and scrolls instead.
 *
 * <p>Cost: {@link #update()} runs once per game frame on the X server's thread
 * and only stores a timestamp; the card is refreshed twice a second, and its
 * views are rebuilt only when the template or the ticked items change.
 */
public class FrameRating extends FrameLayout implements Runnable {
    /** What the HUD needs from the activity for the controls in its menu. */
    public interface Host {
        FrameGeneration getFrameGeneration();
        void applyFrameGeneration();
        boolean isFrameGenerationAvailable();
        /** Where the HUD settings are kept: the shortcut's, else the container's. */
        String getHudScope();
        boolean canSaveToShortcut();
        void saveToShortcut();
    }

    public static final int MODE_MINIMAL = 0;
    public static final int MODE_COMPACT = 1;
    public static final int MODE_FULL = 2;
    public static final int MODE_CUSTOM = 3;

    private static final String[] ITEM_KEYS = {"fps", "graph", "fg", "game", "gpu", "ram", "temp", "battery", "driver"};
    private static final int[] ITEM_NAMES = {R.string.hud_item_fps, R.string.hud_item_graph, R.string.hud_item_fg,
        R.string.hud_item_game, R.string.hud_item_gpu, R.string.hud_item_ram, R.string.hud_item_temp,
        R.string.hud_item_battery, R.string.hud_item_driver};
    private static final String DEFAULT_ITEMS = "fps,graph,fg,game,ram,temp";
    private static final int[] LIMIT_CHIPS = {0, 30, 45, 60, 90, 120};
    private static final int GRAPH_SAMPLES = 60;
    private static final int HISTORY = 240;
    private static final String GPU_BUSY_FILE = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage";

    private static final int WHITE = 0xFFFFFFFF;
    private static final int LABEL = 0xFFBDBDBD;
    private static final int SOFT = 0xFFE0E0E0;
    private static final int LINE = 0xFF5C5C5C;
    private static final int CELL = 0xFF3D3D3D;

    private final Context context;
    private final SharedPreferences preferences;
    private final KeyValueSet displayConfig;
    private String rendererName;
    private String gpuName;
    private final String totalRAM;
    private Host host;
    private String scope = "default";

    // Written on the game's frame thread, read on the UI thread.
    private final Object statsLock = new Object();
    private final float[] history = new float[HISTORY];
    private int historyPos = 0;
    private int historyCount = 0;
    private long lastFrameNanos = 0;
    private long lastTime = 0;
    private int frameCount = 0;
    private volatile float lastFPS = 0;

    // UI thread only.
    private final float[] graphSamples = new float[GRAPH_SAMPLES];
    private final float[] sorted = new float[HISTORY];
    private int slowTick = 0;
    private boolean gpuReadable = true;
    private String gpuText = null;
    private String temperatureText = null;
    private String batteryText = null;

    private int mode = MODE_COMPACT;
    private int scale = 100;
    private int opacity = 70;
    private final Set<String> items = new HashSet<>();
    private float savedX = -1, savedY = -1;
    private boolean positioned = false;

    private final LinearLayout card;
    private final HashMap<String, TextView> values = new HashMap<>();
    private FrameTimeGraph graph;
    private View menu;

    public FrameRating(Context context, HashMap graphicsDriverConfig, String displayDriver, KeyValueSet displayConfig) {
        super(context);
        this.context = context;
        this.displayConfig = displayConfig;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.rendererName = processDisplayDriver(displayDriver);
        this.gpuName = GPUInformation.getRenderer(graphicsDriverConfig.get("version").toString(), context);
        this.totalRAM = getTotalRAM();

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPivotX(0);
        card.setPivotY(0);
        addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        card.setOnTouchListener(new CardTouchListener());

        loadSettings();
        buildCard();
    }

    /** Called once the activity knows the shortcut; the HUD settings are kept per shortcut. */
    public void setHost(Host host) {
        this.host = host;
        String hostScope = host != null ? host.getHudScope() : null;
        scope = hostScope != null ? hostScope : "default";
        loadSettings();
        buildCard();
        positioned = false;
        requestLayout();
    }

    // ---- settings ---------------------------------------------------------------------------

    private String key(String name) {
        return "hud." + scope + "." + name;
    }

    private void loadSettings() {
        mode = Math.max(MODE_MINIMAL, Math.min(MODE_CUSTOM, preferences.getInt(key("mode"), MODE_COMPACT)));
        scale = Math.max(75, Math.min(150, preferences.getInt(key("scale"), 100)));
        opacity = Math.max(0, Math.min(100, preferences.getInt(key("opacity"), 70)));
        savedX = preferences.getFloat(key("x"), -1);
        savedY = preferences.getFloat(key("y"), -1);
        items.clear();
        items.addAll(Arrays.asList(preferences.getString(key("items"), DEFAULT_ITEMS).split(",")));
    }

    private void saveSettings() {
        preferences.edit()
            .putInt(key("mode"), mode)
            .putInt(key("scale"), scale)
            .putInt(key("opacity"), opacity)
            .putFloat(key("x"), savedX)
            .putFloat(key("y"), savedY)
            .putString(key("items"), String.join(",", items))
            .apply();
    }

    // ---- measurement ------------------------------------------------------------------------

    /** One game frame reached the window. Called on the X server's thread. */
    public void update() {
        long now = System.nanoTime();
        synchronized (statsLock) {
            if (lastFrameNanos > 0) {
                float ms = (now - lastFrameNanos) / 1000000f;
                if (ms < 1000f) {
                    history[historyPos] = ms;
                    historyPos = (historyPos + 1) % HISTORY;
                    if (historyCount < HISTORY) historyCount++;
                }
            }
            lastFrameNanos = now;
        }

        long time = SystemClock.elapsedRealtime();
        if (lastTime == 0) lastTime = time;
        if (time >= lastTime + 500) {
            lastFPS = (float)(frameCount * 1000) / (time - lastTime);
            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    public void reset() {
        synchronized (statsLock) {
            historyCount = 0;
            historyPos = 0;
            lastFrameNanos = 0;
        }
        lastTime = 0;
        frameCount = 0;
        lastFPS = 0;
    }

    public void setRenderer(String renderer) {
        rendererName = renderer;
    }

    public void setGpuName(String gpuName) {
        this.gpuName = gpuName;
    }

    public String processDisplayDriver(String displayDriver) {
        if (displayDriver.equals("displayx"))
            return displayConfig.get("trueDisplayX").equals("1") ? "DisplayX+" : "DisplayX";
        return "EGL";
    }

    @Override
    public void run() {
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        refreshValues();
        if (menu != null) refreshMenuStatus();
    }

    private void refreshValues() {
        int n;
        float average = 0;
        float onePercentLow = 0;
        synchronized (statsLock) {
            n = Math.min(historyCount, GRAPH_SAMPLES);
            for (int i = 0; i < n; i++) {
                float sample = history[(historyPos - n + i + HISTORY) % HISTORY];
                graphSamples[i] = sample;
                average += sample;
            }
            int all = historyCount;
            for (int i = 0; i < all; i++) sorted[i] = history[(historyPos - all + i + HISTORY) % HISTORY];
            if (all > 0) {
                Arrays.sort(sorted, 0, all);
                float worst = sorted[Math.min(all - 1, (int)(all * 0.99f))];
                onePercentLow = worst > 0 ? 1000f / worst : 0;
            }
        }
        if (n > 0) average /= n;

        boolean fgRunning = FrameGeneration.getStatus() == FrameGeneration.STATUS_RUNNING;
        float fps = fgRunning ? XServerView.nativeGetFrameGenerationRate() : lastFPS;
        float gameFps = fgRunning ? XServerView.nativeGetFrameGenerationSourceRate() : lastFPS;

        if (graph != null) graph.setSamples(graphSamples, n, average);

        if ((slowTick++ % 4) == 0) readSlowStats();
        if (gpuReadable) readGpuBusy();

        setValue("fps", String.format(Locale.ENGLISH, "%.0f", fps));
        setValue("fg", fgText(fgRunning));
        setValue("fgLine", fgLine(fgRunning, gameFps));
        setValue("game", String.format(Locale.ENGLISH, "%.0f", gameFps));
        setValue("limit", FpsLimiter.getLimit() > 0 ? String.valueOf(FpsLimiter.getLimit()) : "Off");
        setValue("gpu", gpuText != null ? gpuText : "-");
        setValue("ram", getUsedRAM() + "/" + totalRAM);
        setValue("temp", temperatureText != null ? temperatureText : "-");
        setValue("battery", batteryText != null ? batteryText : "-");
        setValue("driver", rendererName + " · " + gpuName);
        setValue("frameTime", String.format(Locale.ENGLISH, "%.1f ms", average));
        setValue("low", onePercentLow > 0 ? String.format(Locale.ENGLISH, "1%% low %.0f", onePercentLow) : "1% low -");
    }

    private String fgText(boolean running) {
        FrameGeneration fg = host != null ? host.getFrameGeneration() : null;
        if (fg == null || !fg.enabled) return "Off";
        return running ? fg.multiplier + "x" : "Waiting";
    }

    private String fgLine(boolean running, float gameFps) {
        String fg = fgText(running);
        if (fg.equals("Off")) return "FG off";
        return String.format(Locale.ENGLISH, "FG %s · game %.0f", fg, gameFps);
    }

    private void setValue(String key, String value) {
        TextView view = values.get(key);
        if (view != null) view.setText(value);
    }

    private void readSlowStats() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        temperatureText = temperature != Integer.MIN_VALUE ? String.format(Locale.ENGLISH, "%.0f°C", temperature / 10f) : null;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int levelScale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        batteryText = level >= 0 && levelScale > 0 ? (level * 100 / levelScale) + "%" : null;
    }

    /** Adreno's GPU load. Most devices do not let apps read it; it is then hidden. */
    private void readGpuBusy() {
        try (BufferedReader reader = new BufferedReader(new FileReader(GPU_BUSY_FILE))) {
            String line = reader.readLine();
            if (line == null) throw new IllegalStateException("empty");
            String text = line.trim().replace(" ", "");
            gpuText = text.endsWith("%") ? text : text + "%";
        }
        catch (Exception e) {
            gpuReadable = false;
            gpuText = null;
            if (mode == MODE_FULL || (mode == MODE_CUSTOM && items.contains("gpu"))) post(this::buildCard);
        }
    }

    private String getTotalRAM() {
        ActivityManager activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return StringUtils.formatBytes(memoryInfo.totalMem);
    }

    private String getUsedRAM() {
        ActivityManager activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return StringUtils.formatBytes(memoryInfo.totalMem - memoryInfo.availMem, false);
    }

    // ---- the card ---------------------------------------------------------------------------

    private int dp(float value) {
        return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private TextView text(String value, float size, int color, boolean mono) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setMaxLines(1);
        view.setShadowLayer(dp(2), 0, dp(1), 0xE6000000);
        if (mono) view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return view;
    }

    private TextView value(String key, float size, int color, boolean mono) {
        TextView view = text("-", size, color, mono);
        values.put(key, view);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private View fpsRow(float size, String unitLabel) {
        LinearLayout row = row();
        row.setGravity(Gravity.BOTTOM);
        row.addView(value("fps", size, WHITE, true), wrap());
        TextView unit = text(unitLabel, 11, LABEL, false);
        unit.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = wrap();
        params.leftMargin = dp(6);
        row.addView(unit, params);
        return row;
    }

    private View menuButton(int size) {
        TextView button = text("⋮", 18, WHITE, false);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(context.getString(R.string.hud_menu));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF000000);
        background.setStroke(dp(1), LINE);
        background.setCornerRadius(dp(9));
        button.setBackground(background);
        button.setOnClickListener((v) -> toggleMenu());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return button;
    }

    private LinearLayout statRow(String label, String key) {
        LinearLayout row = row();
        row.addView(text(label, 12, LABEL, false), weighted());
        LinearLayout.LayoutParams params = wrap();
        params.leftMargin = dp(16);
        row.addView(value(key, 12, WHITE, true), params);
        return row;
    }

    private LinearLayout statCell(String label, String key) {
        LinearLayout cell = statRow(label, key);
        GradientDrawable border = new GradientDrawable();
        border.setStroke(dp(1), CELL);
        border.setCornerRadius(dp(8));
        cell.setBackground(border);
        cell.setPadding(dp(8), dp(5), dp(8), dp(5));
        return cell;
    }

    private void add(LinearLayout parent, View child, LinearLayout.LayoutParams params, int topMargin) {
        params.topMargin = parent.getChildCount() > 0 ? dp(topMargin) : 0;
        parent.addView(child, params);
    }

    private void buildCard() {
        card.removeAllViews();
        values.clear();
        graph = null;

        GradientDrawable background = new GradientDrawable();
        background.setColor((opacity * 255 / 100) << 24);
        background.setStroke(dp(1), 0x38FFFFFF);
        background.setCornerRadius(dp(14));
        card.setBackground(background);
        card.setScaleX(scale / 100f);
        card.setScaleY(scale / 100f);

        if (mode == MODE_MINIMAL) {
            card.setPadding(dp(12), dp(6), dp(12), dp(6));
            card.addView(fpsRow(22, "FPS"), wrap());
        }
        else if (mode == MODE_FULL) {
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout content = column();
            card.addView(content, new LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout header = row();
            header.addView(fpsRow(30, context.getString(R.string.hud_fps_on_screen)), weighted());
            header.addView(menuButton(32));
            add(content, header, fill(), 0);

            graph = new FrameTimeGraph(context, GRAPH_SAMPLES);
            add(content, graph, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)), 10);

            LinearLayout times = row();
            times.addView(value("frameTime", 11, LABEL, false), weighted());
            times.addView(value("low", 11, LABEL, false), wrap());
            add(content, times, fill(), 4);

            int[] names = {R.string.hud_item_game, R.string.fps_limit, R.string.hud_item_gpu,
                R.string.hud_item_ram, R.string.hud_item_temp, R.string.hud_item_battery};
            String[] keys = {"game", "limit", "gpu", "ram", "temp", "battery"};
            LinearLayout grid = null;
            int column = 0;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].equals("gpu") && !gpuReadable) continue;
                if (column % 2 == 0) {
                    grid = row();
                    add(content, grid, fill(), 6);
                }
                LinearLayout.LayoutParams params = weighted();
                if (column % 2 == 1) params.leftMargin = dp(6);
                grid.addView(statCell(context.getString(names[i]), keys[i]), params);
                column++;
            }

            add(content, value("fgLine", 11, SOFT, false), fill(), 10);
            add(content, value("driver", 11, SOFT, false), fill(), 2);
        }
        else if (mode == MODE_CUSTOM) {
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout content = column();
            content.setMinimumWidth(dp(150));
            card.addView(content, wrap());

            LinearLayout header = row();
            TextView title = text(context.getString(R.string.hud_custom).toUpperCase(Locale.ENGLISH), 10, LABEL, false);
            title.setLetterSpacing(0.12f);
            header.addView(title, weighted());
            header.addView(menuButton(28));
            add(content, header, fill(), 0);

            if (items.contains("fps")) add(content, fpsRow(26, "FPS"), wrap(), 6);
            if (items.contains("graph")) {
                graph = new FrameTimeGraph(context, GRAPH_SAMPLES);
                add(content, graph, new LinearLayout.LayoutParams(dp(150), dp(28)), 6);
            }
            for (int i = 2; i < ITEM_KEYS.length; i++) {
                String item = ITEM_KEYS[i];
                if (!items.contains(item) || (item.equals("gpu") && !gpuReadable)) continue;
                add(content, statRow(context.getString(ITEM_NAMES[i]), item), fill(), 6);
            }
        }
        else {
            card.setPadding(dp(12), dp(8), dp(8), dp(8));
            LinearLayout content = row();
            card.addView(content, wrap());

            LinearLayout left = column();
            left.addView(fpsRow(26, "FPS"), wrap());
            LinearLayout.LayoutParams lineParams = wrap();
            lineParams.topMargin = dp(3);
            left.addView(value("fgLine", 11, SOFT, false), lineParams);
            content.addView(left, wrap());

            graph = new FrameTimeGraph(context, GRAPH_SAMPLES);
            LinearLayout.LayoutParams graphParams = new LinearLayout.LayoutParams(dp(64), dp(28));
            graphParams.leftMargin = dp(12);
            graphParams.rightMargin = dp(12);
            content.addView(graph, graphParams);
            content.addView(menuButton(32));
        }
        refreshValues();
    }

    private void setMode(int newMode) {
        mode = newMode;
        saveSettings();
        buildCard();
        post(this::keepInside);
        if (menu != null) {
            bindTemplates();
            post(this::placeMenu);
        }
    }

    // ---- position ---------------------------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!positioned || changed) {
            positioned = true;
            card.setTranslationX(savedX >= 0 ? savedX * getWidth() : dp(8));
            card.setTranslationY(savedY >= 0 ? savedY * getHeight() : dp(8));
            keepInside();
            if (menu != null) placeMenu();
        }
    }

    private float cardWidth() {
        return card.getWidth() * card.getScaleX();
    }

    private float cardHeight() {
        return card.getHeight() * card.getScaleY();
    }

    /** Clamps the card to the screen and snaps it to a side edge when it is close to one. */
    private void keepInside() {
        if (getWidth() == 0) return;
        float margin = dp(8);
        float snap = dp(48);
        float w = cardWidth(), h = cardHeight();
        float x = Math.max(margin, Math.min(getWidth() - w - margin, card.getTranslationX()));
        float y = Math.max(margin, Math.min(getHeight() - h - margin, card.getTranslationY()));
        if (x < snap) x = margin;
        else if (x > getWidth() - w - snap) x = getWidth() - w - margin;
        card.setTranslationX(x);
        card.setTranslationY(y);
    }

    private void savePosition() {
        if (getWidth() == 0 || getHeight() == 0) return;
        savedX = card.getTranslationX() / getWidth();
        savedY = card.getTranslationY() / getHeight();
        saveSettings();
    }

    private class CardTouchListener implements View.OnTouchListener {
        private final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX, downY, startX, startY;
        private boolean dragging, longPressed;
        private final Runnable longPress = () -> {
            longPressed = true;
            toggleMenu();
        };

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = card.getTranslationX();
                    startY = card.getTranslationY();
                    dragging = false;
                    longPressed = false;
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                        removeCallbacks(longPress);
                    }
                    if (dragging) {
                        card.setTranslationX(startX + dx);
                        card.setTranslationY(startY + dy);
                        if (menu != null) placeMenu();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    if (dragging) {
                        keepInside();
                        savePosition();
                        if (menu != null) placeMenu();
                    }
                    else if (!longPressed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        setMode((mode + 1) % 4);
                    }
                    return true;
            }
            return false;
        }
    }

    // ---- menu -------------------------------------------------------------------------------

    private void toggleMenu() {
        if (menu != null) closeMenu();
        else openMenu();
    }

    private void closeMenu() {
        if (menu == null) return;
        removeView(menu);
        menu = null;
    }

    private void openMenu() {
        menu = LayoutInflater.from(context).inflate(R.layout.hud_menu, this, false);
        menu.setVisibility(INVISIBLE);
        addView(menu, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        bindMenu();
        post(() -> {
            if (menu == null) return;
            placeMenu();
            menu.setVisibility(VISIBLE);
        });
    }

    /**
     * Sizes the menu to the screen and puts it next to the card: below or above
     * it, whichever has more room, or centred when neither has enough. Its height
     * never exceeds the room it is given; what does not fit scrolls.
     */
    private void placeMenu() {
        if (menu == null || getWidth() == 0 || getHeight() == 0) return;
        int margin = dp(8);
        int width = Math.min(getWidth() - 2 * margin, dp(640));
        menu.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                     MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.UNSPECIFIED));
        int wanted = menu.getMeasuredHeight();

        float cardTop = card.getTranslationY();
        float cardBottom = cardTop + cardHeight();
        int below = (int)(getHeight() - cardBottom - 2 * margin);
        int above = (int)(cardTop - 2 * margin);
        int enough = Math.min(wanted, dp(200));

        int height;
        float y;
        if (below >= enough && below >= above) {
            height = Math.min(wanted, below);
            y = cardBottom + margin;
        }
        else if (above >= enough) {
            height = Math.min(wanted, above);
            y = cardTop - margin - height;
        }
        else {
            height = Math.min(wanted, getHeight() - 2 * margin);
            y = (getHeight() - height) / 2f;
        }
        float x = Math.max(margin, Math.min(getWidth() - width - margin, card.getTranslationX()));

        LayoutParams params = (LayoutParams)menu.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            menu.setLayoutParams(params);
        }
        menu.setTranslationX(x);
        menu.setTranslationY(y);
    }

    private TextView chip(String label, boolean selected, View.OnClickListener onClick) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setMaxLines(1);
        chip.setTextColor(selected ? 0xFF000000 : WHITE);
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(999));
        if (selected) background.setColor(WHITE);
        else background.setStroke(dp(1), LINE);
        chip.setBackground(background);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private void fillChips(LinearLayout row, String[] labels, int selected, IntConsumer onPick) {
        row.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1);
            if (i > 0) params.leftMargin = dp(6);
            row.addView(chip(labels[i], i == selected, (v) -> onPick.accept(index)), params);
        }
    }

    private void bindTemplates() {
        String[] names = {context.getString(R.string.hud_minimal), context.getString(R.string.hud_compact),
            context.getString(R.string.hud_full), context.getString(R.string.hud_custom)};
        fillChips(menu.findViewById(R.id.LLHudTemplates), names, mode, this::setMode);
    }

    private void bindLimits() {
        String[] labels = new String[LIMIT_CHIPS.length];
        int selected = -1;
        for (int i = 0; i < LIMIT_CHIPS.length; i++) {
            labels[i] = LIMIT_CHIPS[i] > 0 ? String.valueOf(LIMIT_CHIPS[i]) : "Off";
            if (LIMIT_CHIPS[i] == FpsLimiter.getLimit()) selected = i;
        }
        fillChips(menu.findViewById(R.id.LLHudLimits), labels, selected, (index) -> {
            FpsLimiter.setLimit(LIMIT_CHIPS[index]);
            bindLimits();
            refreshValues();
        });
    }

    private void bindItems() {
        LinearLayout container = menu.findViewById(R.id.LLHudItems);
        container.removeAllViews();
        LinearLayout itemRow = null;
        for (int i = 0; i < ITEM_KEYS.length; i++) {
            if (i % 2 == 0) {
                itemRow = row();
                container.addView(itemRow, fill());
            }
            final String item = ITEM_KEYS[i];
            CheckBox box = new CheckBox(context);
            box.setText(ITEM_NAMES[i]);
            box.setTextColor(WHITE);
            box.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            box.setButtonTintList(ColorStateList.valueOf(WHITE));
            box.setMinHeight(dp(40));
            box.setChecked(items.contains(item));
            box.setOnCheckedChangeListener((v, checked) -> {
                if (checked) items.add(item);
                else items.remove(item);
                mode = MODE_CUSTOM;
                saveSettings();
                buildCard();
                bindTemplates();
                post(this::keepInside);
                post(this::placeMenu);
            });
            itemRow.addView(box, weighted());
        }
    }

    private void bindMenu() {
        bindTemplates();
        bindLimits();
        bindItems();

        final TextView tvScale = menu.findViewById(R.id.TVHudScale);
        final SeekBar sbScale = menu.findViewById(R.id.SBHudScale);
        sbScale.setProgress((scale - 75) / 5);
        tvScale.setText(scale + "%");
        sbScale.setOnSeekBarChangeListener(new SeekBarChange((progress) -> {
            scale = 75 + progress * 5;
            tvScale.setText(scale + "%");
            card.setScaleX(scale / 100f);
            card.setScaleY(scale / 100f);
        }, () -> {
            saveSettings();
            keepInside();
            placeMenu();
        }));

        final TextView tvOpacity = menu.findViewById(R.id.TVHudOpacity);
        final SeekBar sbOpacity = menu.findViewById(R.id.SBHudOpacity);
        sbOpacity.setProgress(opacity / 5);
        tvOpacity.setText(opacity + "%");
        sbOpacity.setOnSeekBarChangeListener(new SeekBarChange((progress) -> {
            opacity = progress * 5;
            tvOpacity.setText(opacity + "%");
            if (card.getBackground() instanceof GradientDrawable)
                ((GradientDrawable)card.getBackground()).setColor((opacity * 255 / 100) << 24);
        }, this::saveSettings));

        bindFrameGeneration();

        View save = menu.findViewById(R.id.BTHudSaveToShortcut);
        boolean canSave = host != null && host.canSaveToShortcut();
        save.setVisibility(canSave ? VISIBLE : GONE);
        save.setOnClickListener((v) -> {
            host.saveToShortcut();
            AppUtils.showToast(context, R.string.hud_saved_to_shortcut);
        });
        menu.findViewById(R.id.BTHudDone).setOnClickListener((v) -> post(this::closeMenu));
    }

    private void bindFrameGeneration() {
        final FrameGeneration fg = host != null ? host.getFrameGeneration() : null;
        final boolean available = fg != null && host.isFrameGenerationAvailable();
        final CheckBox cbEnabled = menu.findViewById(R.id.CBHudFg);
        final CheckBox cbAdaptive = menu.findViewById(R.id.CBHudFgAdaptive);
        final SeekBar sbFlow = menu.findViewById(R.id.SBHudFlowScale);
        final TextView tvFlow = menu.findViewById(R.id.TVHudFlowScale);
        cbEnabled.setEnabled(available);
        cbAdaptive.setEnabled(available);
        sbFlow.setEnabled(available);
        if (fg == null) {
            refreshMenuStatus();
            return;
        }

        cbEnabled.setChecked(fg.enabled);
        cbEnabled.setOnCheckedChangeListener((v, checked) -> {
            fg.enabled = checked;
            host.applyFrameGeneration();
            refreshValues();
        });
        cbAdaptive.setChecked(fg.adaptive);
        cbAdaptive.setOnCheckedChangeListener((v, checked) -> {
            fg.adaptive = checked;
            host.applyFrameGeneration();
        });
        bindMultiplier(fg, available);

        sbFlow.setProgress(fg.flowScale - FrameGeneration.MIN_FLOW_SCALE);
        tvFlow.setText(String.format(Locale.ENGLISH, "%.2f", fg.flowScale / 100f));
        sbFlow.setOnSeekBarChangeListener(new SeekBarChange((progress) -> {
            fg.flowScale = FrameGeneration.MIN_FLOW_SCALE + progress;
            tvFlow.setText(String.format(Locale.ENGLISH, "%.2f", fg.flowScale / 100f));
        }, host::applyFrameGeneration));
        refreshMenuStatus();
    }

    private void bindMultiplier(FrameGeneration fg, boolean available) {
        LinearLayout row = menu.findViewById(R.id.LLHudFgMultiplier);
        fillChips(row, new String[]{"2x", "3x", "4x"}, fg.multiplier - FrameGeneration.MIN_MULTIPLIER, (index) -> {
            if (!available) return;
            fg.multiplier = FrameGeneration.MIN_MULTIPLIER + index;
            host.applyFrameGeneration();
            bindMultiplier(fg, true);
            refreshValues();
        });
        row.setAlpha(available ? 1f : 0.4f);
    }

    private void refreshMenuStatus() {
        if (menu == null) return;
        TextView status = menu.findViewById(R.id.TVHudFgStatus);
        if (host == null || !host.isFrameGenerationAvailable())
            status.setText(R.string.frame_gen_needs_displayx);
        else
            status.setText(FrameGeneration.getStatusText(context));
    }

    private static class SeekBarChange implements SeekBar.OnSeekBarChangeListener {
        private final IntConsumer onChange;
        private final Runnable onRelease;

        SeekBarChange(IntConsumer onChange, Runnable onRelease) {
            this.onChange = onChange;
            this.onRelease = onRelease;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) onChange.accept(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            onRelease.run();
        }
    }
}

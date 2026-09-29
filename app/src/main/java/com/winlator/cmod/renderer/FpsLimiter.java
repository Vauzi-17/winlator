package com.winlator.cmod.renderer;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.widget.XServerView;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Caps the game's own frame rate by pacing when its swapchain images come back.
 *
 * <p>A Vulkan or GL swapchain presenting through X11 (DRI3 + Present) reuses an
 * image only after the server reports it idle, so holding each idle event back
 * to the next slot of a fixed timeline keeps the game at that rate without
 * touching the game itself. The DisplayX layer path is paced the same way in
 * native code. With frame generation on, the limit applies to the game's real
 * frames: 30 with 2x shows 60 on screen.
 */
public final class FpsLimiter {
    public static final int[] VALUES = {0, 20, 24, 25, 30, 40, 45, 50, 60, 72, 90, 120, 144};

    private static volatile int limit = 0;
    private static long nextNanos = 0;
    private static ScheduledExecutorService scheduler;

    private FpsLimiter() {}

    public static int getLimit() {
        return limit;
    }

    public static synchronized void setLimit(int fps) {
        limit = Math.max(0, fps);
        nextNanos = 0;
        XServerView.nativeSetFpsLimit(limit);
    }

    /** The container's limit, overridden by the shortcut's when it sets one. */
    public static int fromConfig(Container container, Shortcut shortcut) {
        String value = container != null ? container.getExtra("fpsLimit", "0") : "0";
        if (shortcut != null) value = shortcut.getExtra("fpsLimit", value);
        try {
            return Math.max(0, Integer.parseInt(value));
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String label(int fps) {
        return fps > 0 ? fps + " FPS" : "Off";
    }

    /** Index into {@link #VALUES}, or 0 (Off) for a value not in the list. */
    public static int indexOf(int fps) {
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i] == fps) return i;
        return 0;
    }

    public static String[] labels() {
        String[] labels = new String[VALUES.length];
        for (int i = 0; i < VALUES.length; i++) labels[i] = label(VALUES[i]);
        return labels;
    }

    /**
     * Runs {@code release} (the idle and complete events of one present) on the
     * limiter's timeline: right away when there is no limit or the game is
     * behind it, otherwise at its slot.
     */
    public static void schedule(Runnable release) {
        long delay = reserve();
        if (delay <= 0) {
            release.run();
            return;
        }
        executor().schedule(release, delay, TimeUnit.NANOSECONDS);
    }

    private static synchronized long reserve() {
        int fps = limit;
        if (fps <= 0) return 0;
        long interval = 1000000000L / fps;
        long now = System.nanoTime();
        // After a stall (loading, pause) start a fresh timeline instead of
        // letting a burst of frames through to catch up.
        if (nextNanos < now - interval) nextNanos = now;
        long release = nextNanos;
        nextNanos += interval;
        return release - now;
    }

    private static synchronized ScheduledExecutorService executor() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor((r) -> {
                Thread thread = new Thread(r, "FpsLimiter");
                thread.setDaemon(true);
                return thread;
            });
        }
        return scheduler;
    }
}

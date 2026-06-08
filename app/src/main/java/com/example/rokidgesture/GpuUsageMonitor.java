package com.example.rokidgesture;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Best-effort GPU utilisation probe.
 *
 * Reads vendor sysfs nodes that don't require root on most chipsets:
 *  - Qualcomm Adreno: kgsl-3d0/gpubusy ("busy total" counters since last read)
 *  - ARM Mali       : utilization / gpu_busy_percentage (direct percent)
 *
 * If nothing is readable on this device, percent() returns -1 forever and the
 * UI can show "N/A".
 */
final class GpuUsageMonitor {
    private static final String TAG = "GpuMonitor";
    private static final long POLL_INTERVAL_MS = 500L;

    private static final String[] PERCENT_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/devices/platform/mali.0/utilization",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
    };

    private static final String[] BUSY_TOTAL_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpubusy",
    };

    private File source;
    private boolean parsePercent;

    private HandlerThread thread;
    private Handler handler;
    private volatile float percent = -1f;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            sample();
            Handler h = handler;
            if (h != null) h.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("GpuMonitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            findSource();
            pollTask.run();
        });
    }

    void stop() {
        Handler h = handler;
        HandlerThread t = thread;
        handler = null;
        thread = null;
        if (h != null) h.removeCallbacks(pollTask);
        if (t != null) t.quitSafely();
    }

    /** @return GPU utilisation in [0, 100], or -1 if unavailable. */
    float percent() {
        return percent;
    }

    private void findSource() {
        for (String path : PERCENT_PATHS) {
            File f = new File(path);
            if (f.canRead()) {
                source = f;
                parsePercent = true;
                Log.i(TAG, "Using GPU source (percent): " + path);
                return;
            }
        }
        for (String path : BUSY_TOTAL_PATHS) {
            File f = new File(path);
            if (f.canRead()) {
                source = f;
                parsePercent = false;
                Log.i(TAG, "Using GPU source (busy/total): " + path);
                return;
            }
        }
        Log.w(TAG, "No readable GPU sysfs node — percent() will stay at -1");
    }

    private void sample() {
        File src = source;
        if (src == null) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(src))) {
            String line = reader.readLine();
            if (line == null) return;
            line = line.trim();
            if (parsePercent) {
                // Some nodes append "%" or extra fields.
                int space = line.indexOf(' ');
                if (space > 0) line = line.substring(0, space);
                if (line.endsWith("%")) line = line.substring(0, line.length() - 1);
                percent = clamp(Float.parseFloat(line));
            } else {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) return;
                long busy = Long.parseLong(parts[0]);
                long total = Long.parseLong(parts[1]);
                if (total <= 0L) return;
                percent = clamp(busy * 100f / total);
            }
        } catch (Throwable error) {
            // Silent — the node may have been revoked or returned junk this tick.
        }
    }

    private static float clamp(float value) {
        if (value < 0f) return 0f;
        if (value > 100f) return 100f;
        return value;
    }
}

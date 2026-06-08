package com.example.rokidgesture;

final class FpsMeter {
    private static final long WINDOW_MS = 500L;

    private long recognizerWindowStartMs;
    private int recognizerCountInWindow;
    private volatile float recognizerFps;

    private long uiWindowStartMs;
    private int uiCountInWindow;
    private volatile float uiFps;

    void tickRecognizer() {
        long now = System.nanoTime() / 1_000_000L;
        if (recognizerWindowStartMs == 0L) {
            recognizerWindowStartMs = now;
            return;
        }
        recognizerCountInWindow++;
        long elapsed = now - recognizerWindowStartMs;
        if (elapsed >= WINDOW_MS) {
            recognizerFps = recognizerCountInWindow * 1000f / elapsed;
            recognizerCountInWindow = 0;
            recognizerWindowStartMs = now;
        }
    }

    void tickUi() {
        long now = System.nanoTime() / 1_000_000L;
        if (uiWindowStartMs == 0L) {
            uiWindowStartMs = now;
            return;
        }
        uiCountInWindow++;
        long elapsed = now - uiWindowStartMs;
        if (elapsed >= WINDOW_MS) {
            uiFps = uiCountInWindow * 1000f / elapsed;
            uiCountInWindow = 0;
            uiWindowStartMs = now;
        }
    }

    void reset() {
        recognizerWindowStartMs = 0L;
        recognizerCountInWindow = 0;
        recognizerFps = 0f;
        uiWindowStartMs = 0L;
        uiCountInWindow = 0;
        uiFps = 0f;
    }

    float recognizerFps() {
        return recognizerFps;
    }

    float uiFps() {
        return uiFps;
    }
}

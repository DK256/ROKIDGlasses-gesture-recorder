package com.example.rokidgesture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;
import java.util.Locale;

public final class GestureOverlayView extends View {
    private static final int[][] BONES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},
            {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {0, 9}, {9, 10}, {10, 11}, {11, 12},
            {0, 13}, {13, 14}, {14, 15}, {15, 16},
            {0, 17}, {17, 18}, {18, 19}, {19, 20},
            {5, 9}, {9, 13}, {13, 17}
    };

    private static final int[] CONTOUR = {0, 1, 2, 3, 4, 8, 12, 16, 20, 19, 18, 17, 0};
    private static final long SKELETON_RENDER_INTERVAL_MS = 42L;
    private static final long TEXT_RENDER_INTERVAL_MS = 166L;
    private static final boolean GESTURE_ROTATE_CCW_90 = true;
    private static final boolean GESTURE_FORCE_MIRROR = true;
    private static final boolean GESTURE_FORCE_MIRROR_Y = true;

    private final Paint contourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contourPath = new Path();

    private volatile HandsResult result = HandsResult.EMPTY;
    private volatile HandsResult textSnapshot = HandsResult.EMPTY;
    private volatile boolean mirrorPreview;
    private volatile boolean recording;
    private volatile boolean infoExpanded;

    private FpsMeter fpsMeter;
    private GpuUsageMonitor gpuMonitor;

    private float density = 1f;
    private long lastSkeletonRenderAtMs;
    private long lastTextRenderAtMs;
    private boolean renderScheduled;
    private boolean textDirty;
    private int drawWidth;
    private int drawHeight;
    private final Runnable renderRunnable = this::dispatchRender;

    public GestureOverlayView(Context context) {
        super(context);
        init();
    }

    public GestureOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        density = getResources().getDisplayMetrics().density;

        contourPaint.setStyle(Paint.Style.STROKE);
        contourPaint.setStrokeCap(Paint.Cap.ROUND);
        contourPaint.setStrokeJoin(Paint.Join.ROUND);
        contourPaint.setStrokeWidth(dp(1.2f));
        contourPaint.setColor(Color.WHITE);

        bonePaint.setStyle(Paint.Style.STROKE);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);
        bonePaint.setStrokeJoin(Paint.Join.ROUND);
        bonePaint.setColor(Color.WHITE);

        jointPaint.setStyle(Paint.Style.FILL);
        jointPaint.setColor(Color.WHITE);

        statusDotPaint.setStyle(Paint.Style.FILL);
        statusDotPaint.setColor(Color.WHITE);

        hudPaint.setColor(Color.WHITE);
        hudPaint.setTextSize(dp(9f));
        hudPaint.setFakeBoldText(true);
        hudPaint.setTypeface(Typeface.MONOSPACE);

        hudDimPaint.setColor(Color.argb(180, 255, 255, 255));
        hudDimPaint.setTextSize(dp(8f));
        hudDimPaint.setTypeface(Typeface.MONOSPACE);

        recDotPaint.setStyle(Paint.Style.FILL);
        recDotPaint.setColor(Color.WHITE);

        zonePaint.setStyle(Paint.Style.STROKE);
        zonePaint.setStrokeCap(Paint.Cap.ROUND);
        zonePaint.setStrokeJoin(Paint.Join.ROUND);
        zonePaint.setStrokeWidth(dp(1.4f));
        zonePaint.setColor(Color.WHITE);

        zoneEdgePaint.setStyle(Paint.Style.STROKE);
        zoneEdgePaint.setStrokeCap(Paint.Cap.ROUND);
        zoneEdgePaint.setStrokeWidth(dp(1.4f));
        zoneEdgePaint.setColor(Color.WHITE);

        zoneHintPaint.setColor(Color.WHITE);
        zoneHintPaint.setTextSize(dp(8.5f));
        zoneHintPaint.setFakeBoldText(true);
        zoneHintPaint.setTypeface(Typeface.MONOSPACE);
        zoneHintPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float value) {
        return value * density;
    }

    void setMirrorPreview(boolean mirrorPreview) {
        this.mirrorPreview = mirrorPreview;
    }

    void setMonitors(FpsMeter fpsMeter, GpuUsageMonitor gpuMonitor) {
        this.fpsMeter = fpsMeter;
        this.gpuMonitor = gpuMonitor;
    }

    void setRecording(boolean recording) {
        if (this.recording == recording) return;
        this.recording = recording;
        textDirty = true;
        requestRender(false);
    }

    void toggleInfoExpanded() {
        infoExpanded = !infoExpanded;
        textDirty = true;
        requestRender(false);
    }

    boolean isInfoExpanded() {
        return infoExpanded;
    }

    void setResult(HandsResult result) {
        this.result = result == null ? HandsResult.EMPTY : result;
        textDirty = true;
        requestRender(true);
    }

    private void requestRender(boolean throttle) {
        long now = SystemClock.uptimeMillis();
        long skeletonDueAt = lastSkeletonRenderAtMs + SKELETON_RENDER_INTERVAL_MS;
        long textDueAt = textDirty ? lastTextRenderAtMs + TEXT_RENDER_INTERVAL_MS : Long.MAX_VALUE;
        long dueAt = Math.min(skeletonDueAt, textDueAt);

        if (!throttle) {
            if (renderScheduled) {
                removeCallbacks(renderRunnable);
                renderScheduled = false;
            }
            dispatchRender();
            return;
        }

        if (lastSkeletonRenderAtMs == 0L || now >= dueAt) {
            if (renderScheduled) {
                removeCallbacks(renderRunnable);
                renderScheduled = false;
            }
            dispatchRender();
            return;
        }

        if (!renderScheduled) {
            renderScheduled = true;
            postOnAnimationDelayed(renderRunnable, Math.max(1L, dueAt - now));
        }
    }

    private void dispatchRender() {
        renderScheduled = false;
        long now = SystemClock.uptimeMillis();
        lastSkeletonRenderAtMs = now;
        if (textDirty && (lastTextRenderAtMs == 0L || now - lastTextRenderAtMs >= TEXT_RENDER_INTERVAL_MS)) {
            textSnapshot = result;
            lastTextRenderAtMs = now;
            textDirty = false;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (fpsMeter != null) fpsMeter.tickUi();

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        drawWidth = viewH;
        drawHeight = viewW;

        canvas.save();
        canvas.translate(viewW, 0f);
        canvas.rotate(90f);

        HandsResult skeletonSnapshot = result;
        HandsResult hudSnapshot = textSnapshot == null ? HandsResult.EMPTY : textSnapshot;
        List<HandResult> hands = skeletonSnapshot == null ? null : skeletonSnapshot.visibleHands();
        boolean hasHands = hands != null && !hands.isEmpty();

        drawSafeZone(canvas, hands, hasHands, false, drawWidth, drawHeight);
        if (hasHands) {
            for (HandResult hand : hands) {
                drawContour(canvas, hand.landmarks);
                drawBones(canvas, hand.landmarks);
                if (hudSnapshot.handCount() < 2) {
                    drawJoints(canvas, hand.landmarks);
                }
            }
        }
        drawHud(canvas, hudSnapshot, hudSnapshot.hasAnyHand());
        drawRecIndicator(canvas, drawWidth);

        canvas.restore();
    }

    private void drawContour(Canvas canvas, List<HandResult.Point3> points) {
        contourPath.reset();
        for (int i = 0; i < CONTOUR.length; i++) {
            int idx = CONTOUR[i];
            if (idx >= points.size()) return;
            float x = sx(points.get(idx));
            float y = sy(points.get(idx));
            if (i == 0) {
                contourPath.moveTo(x, y);
            } else {
                contourPath.lineTo(x, y);
            }
        }
        canvas.drawPath(contourPath, contourPaint);
    }

    private void drawBones(Canvas canvas, List<HandResult.Point3> points) {
        int n = points.size();
        bonePaint.setStrokeWidth(dp(1.6f));
        for (int[] bone : BONES) {
            if (bone[0] >= n || bone[1] >= n) continue;
            HandResult.Point3 a = points.get(bone[0]);
            HandResult.Point3 b = points.get(bone[1]);
            canvas.drawLine(sx(a), sy(a), sx(b), sy(b), bonePaint);
        }
    }

    private void drawJoints(Canvas canvas, List<HandResult.Point3> points) {
        int n = points.size();
        float radius = dp(2f);
        for (int i = 0; i < n; i++) {
            HandResult.Point3 point = points.get(i);
            canvas.drawCircle(sx(point), sy(point), radius, jointPaint);
        }
    }

    private void drawHud(Canvas canvas, HandsResult snapshot, boolean hasHands) {
        float pad = dp(6f);
        float dotR = dp(2.4f);
        float dotCx = pad + dotR;
        float lineH = dp(11f);
        float dimLineH = dp(10f);
        float topBaseline = pad + dp(8f);

        if (hasHands) {
            statusDotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(dotCx, topBaseline - dp(3f), dotR, statusDotPaint);
        } else {
            statusDotPaint.setStyle(Paint.Style.STROKE);
            statusDotPaint.setStrokeWidth(dp(0.8f));
            canvas.drawCircle(dotCx, topBaseline - dp(3f), dotR, statusDotPaint);
        }

        float textLeft = dotCx + dotR + dp(5f);

        float recognizerFps = fpsMeter == null ? 0f : fpsMeter.recognizerFps();
        float uiFps = fpsMeter == null ? 0f : fpsMeter.uiFps();
        float gpu = gpuMonitor == null ? -1f : gpuMonitor.percent();
        String gpuText = gpu < 0f ? "--" : String.format(Locale.US, "%4.1f%%", gpu);
        String line1 = String.format(Locale.US, "REC %4.1f  UI %4.1f  GPU %s", recognizerFps, uiFps, gpuText);
        canvas.drawText(line1, textLeft, topBaseline, hudPaint);

        String gestureLine;
        if (hasHands && snapshot != null) {
            HandResult leftHand = findHand(snapshot, "Left");
            HandResult rightHand = findHand(snapshot, "Right");
            if (snapshot.handCount() >= 2) {
                String leftText = compactHandSummary("L", leftHand);
                String rightText = compactHandSummary("R", rightHand);
                gestureLine = leftText + " | " + rightText;
            } else {
                HandResult primary = snapshot.primaryHand();
                String handChar = (primary.handedness == null || primary.handedness.isEmpty())
                        ? "?" : primary.handedness.substring(0, 1);
                gestureLine = String.format(Locale.US, "%dH %s %s",
                        snapshot.handCount(), handChar, primary.label());
            }
        } else {
            gestureLine = "-  NO HAND";
        }
        canvas.drawText(gestureLine, textLeft, topBaseline + lineH, hudPaint);

        String srcLine;
        if (snapshot != null && snapshot.imageWidth > 0 && snapshot.imageHeight > 0) {
            srcLine = String.format(Locale.US, "src %dx%d  t %d",
                    snapshot.imageWidth, snapshot.imageHeight, snapshot.timestampMs);
        } else {
            srcLine = "src --";
        }
        canvas.drawText(srcLine, textLeft, topBaseline + lineH + dimLineH, hudDimPaint);
    }

    private void drawRecIndicator(Canvas canvas, int viewW) {
        if (!recording) return;
        if (viewW <= 0) return;
        float pad = dp(6f);
        float recR = dp(3f);
        float recCx = viewW - pad - recR;
        float recCy = pad + recR;
        canvas.drawCircle(recCx, recCy, recR, recDotPaint);
    }

    private void drawSafeZone(Canvas canvas, List<HandResult> hands, boolean hasHands, boolean dualHandLandscape, int viewW, int viewH) {
        if (viewW <= 0 || viewH <= 0) return;

        float padLeft = dualHandLandscape ? dp(22f) : dp(36f);
        float padRight = dualHandLandscape ? dp(22f) : dp(36f);
        float padTop = dualHandLandscape ? dp(20f) : dp(28f);
        float padBottom = dualHandLandscape ? dp(12f) : dp(14f);

        float left = padLeft;
        float top = padTop;
        float right = viewW - padRight;
        float bottom = viewH - padBottom;
        if (right - left < dp(40f) || bottom - top < dp(40f)) return;

        float arm = Math.min(dp(18f), Math.min(right - left, bottom - top) * 0.18f);

        int idleAlpha = hasHands ? 90 : 220;
        int alphaL = idleAlpha, alphaT = idleAlpha, alphaR = idleAlpha, alphaB = idleAlpha;
        boolean nearEdge = false;
        if (hasHands && hands != null) {
            for (HandResult hand : hands) {
                if (hand == null || hand.landmarks.isEmpty()) continue;
                HandResult.Point3 wrist = hand.landmarks.get(0);
                float wx = mirrorPreview ? wrist.x : 1f - wrist.x;
                float wy = wrist.y;
                if (wx < 0.08f) { alphaL = 230; nearEdge = true; }
                if (wx > 0.92f) { alphaR = 230; nearEdge = true; }
                if (wy < 0.08f) { alphaT = 230; nearEdge = true; }
                if (wy > 0.92f) { alphaB = 230; nearEdge = true; }
            }
        }

        drawCornerBracket(canvas, left, top, arm, arm, Math.max(alphaL, alphaT));
        drawCornerBracket(canvas, right, top, -arm, arm, Math.max(alphaR, alphaT));
        drawCornerBracket(canvas, left, bottom, arm, -arm, Math.max(alphaL, alphaB));
        drawCornerBracket(canvas, right, bottom, -arm, -arm, Math.max(alphaR, alphaB));

        float cx = (left + right) * 0.5f;
        float cy = (top + bottom) * 0.5f;
        float tick = dp(4f);
        zonePaint.setAlpha(hasHands ? 70 : 140);
        canvas.drawLine(cx - tick, cy, cx + tick, cy, zonePaint);
        canvas.drawLine(cx, cy - tick, cx, cy + tick, zonePaint);
        zonePaint.setAlpha(255);

        if (!hasHands) {
            zoneHintPaint.setAlpha(180);
            canvas.drawText("PUT HAND IN FRAME", cx, bottom + dp(13f), zoneHintPaint);
        } else if (nearEdge) {
            zoneHintPaint.setAlpha(220);
            canvas.drawText("NEAR EDGE", cx, bottom + dp(13f), zoneHintPaint);
        }
    }

    private void drawCornerBracket(Canvas canvas, float cornerX, float cornerY,
                                   float dx, float dy, int alpha) {
        zoneEdgePaint.setAlpha(alpha);
        canvas.drawLine(cornerX, cornerY, cornerX + dx, cornerY, zoneEdgePaint);
        canvas.drawLine(cornerX, cornerY, cornerX, cornerY + dy, zoneEdgePaint);
        zoneEdgePaint.setAlpha(255);
    }

    private float sx(HandResult.Point3 point) {
        int width = drawWidth > 0 ? drawWidth : getWidth();
        float x = gestureX(point);
        return x * width;
    }

    private float sy(HandResult.Point3 point) {
        int height = drawHeight > 0 ? drawHeight : getHeight();
        float y = gestureY(point);
        return y * height;
    }

    private float gestureX(HandResult.Point3 point) {
        float x = mirrorPreview ? 1f - point.x : point.x;
        float y = point.y;
        if (GESTURE_ROTATE_CCW_90) {
            float rotatedX = y;
            float rotatedY = 1f - x;
            x = rotatedX;
            y = rotatedY;
        }
        if (GESTURE_FORCE_MIRROR) {
            x = 1f - x;
        }
        return clamp01(x);
    }

    private float gestureY(HandResult.Point3 point) {
        float x = mirrorPreview ? 1f - point.x : point.x;
        float y = point.y;
        if (GESTURE_ROTATE_CCW_90) {
            float rotatedX = y;
            float rotatedY = 1f - x;
            x = rotatedX;
            y = rotatedY;
        }
        if (GESTURE_FORCE_MIRROR_Y) {
            y = 1f - y;
        }
        return clamp01(y);
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }

    private HandResult findHand(HandsResult snapshot, String handedness) {
        if (snapshot == null) return HandResult.EMPTY;
        for (HandResult hand : snapshot.visibleHands()) {
            if (hand.handedness != null && hand.handedness.equalsIgnoreCase(handedness) && hand.hasHand()) {
                return hand;
            }
        }
        return HandResult.EMPTY;
    }

    private String compactHandSummary(String prefix, HandResult hand) {
        if (hand == null || !hand.hasHand()) {
            return prefix + ":--";
        }
        String gestureName = hand.gestureDisplayName();
        if (gestureName.length() > 6) {
            gestureName = gestureName.substring(0, 6);
        }
        return String.format(Locale.US, "%s:%s%02.0f", prefix, gestureName, hand.gestureScore * 100f);
    }
}

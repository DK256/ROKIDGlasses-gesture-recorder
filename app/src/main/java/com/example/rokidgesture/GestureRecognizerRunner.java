package com.example.rokidgesture;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.processors.ClassifierOptions;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

final class GestureRecognizerRunner implements AutoCloseable {
    interface Listener {
        void onGestureResult(HandsResult result);
        void onGestureError(Throwable error);
    }

    private static final String TAG = "GestureRunner";
    private static final String MODEL_ASSET = "gesture_recognizer.task";

    private final GestureRecognizer recognizer;
    private final Listener listener;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile int pendingWidth;
    private volatile int pendingHeight;
    private volatile Image pendingImageFrame;
    private volatile MPImage pendingImage;
    private volatile boolean closed;
    private YuvToRgbConverter yuvConverter;
    private int yuvConverterRotation = -1;
    private long lastTimestampMs = -1L;
    private long recognizeCount;
    private long resultCount;

    GestureRecognizerRunner(Context context, Listener listener) {
        this.listener = listener;
        this.recognizer = createRecognizer(context);
    }

    private GestureRecognizer createRecognizer(Context context) {
        try {
            GestureRecognizer gpu = build(context, Delegate.GPU);
            Log.i(TAG, "Gesture recognizer running on GPU");
            return gpu;
        } catch (Throwable gpuError) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU: " + gpuError.getMessage());
            return build(context, Delegate.CPU);
        }
    }

    private GestureRecognizer build(Context context, Delegate delegate) {
        Log.i(TAG, "Creating gesture recognizer with " + delegate + " delegate, model=" + MODEL_ASSET);
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build();

        ClassifierOptions cannedGestureOptions = ClassifierOptions.builder()
                .setMaxResults(4)
                .setCategoryDenylist(Collections.singletonList("None"))
                .build();

        GestureRecognizer.GestureRecognizerOptions options =
                GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.4f)
                        .setMinHandPresenceConfidence(0.4f)
                        .setMinTrackingConfidence(0.4f)
                        .setCannedGesturesClassifierOptions(cannedGestureOptions)
                        .setResultListener(this::handleResult)
                        .setErrorListener(this::handleError)
                        .build();

        return GestureRecognizer.createFromOptions(context, options);
    }

    boolean canAcceptFrame() {
        return !closed && !busy.get();
    }

    boolean recognize(Image image, int rotationDegrees, long timestampMs) {
        if (closed || image == null) {
            return false;
        }
        if (!busy.compareAndSet(false, true)) {
            return false;
        }

        try {
            if (timestampMs <= lastTimestampMs) {
                timestampMs = lastTimestampMs + 1L;
            }
            lastTimestampMs = timestampMs;

            int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
            if (yuvConverter == null || yuvConverterRotation != normalizedRotation) {
                yuvConverter = new YuvToRgbConverter(normalizedRotation);
                yuvConverterRotation = normalizedRotation;
            }
            Bitmap bitmap = yuvConverter.toBitmap(image).copy(Bitmap.Config.ARGB_8888, false);
            pendingWidth = bitmap.getWidth();
            pendingHeight = bitmap.getHeight();
            pendingImageFrame = image;
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            ImageProcessingOptions imageOptions = ImageProcessingOptions.builder()
                    .setRotationDegrees(0)
                    .build();
            pendingImage = mpImage;
            recognizeCount++;
            if (recognizeCount == 1 || recognizeCount % 30 == 0) {
                Log.i(TAG, "recognizeAsync #" + recognizeCount + " image=" + image.getWidth() + "x" + image.getHeight()
                        + " rotation=" + normalizedRotation + " bitmap=" + pendingWidth + "x" + pendingHeight
                        + " timestamp=" + timestampMs);
            }
            recognizer.recognizeAsync(mpImage, imageOptions, timestampMs);
            return true;
        } catch (Throwable error) {
            releasePending();
            busy.set(false);
            handleError(error);
            return false;
        }
    }

    private void handleResult(GestureRecognizerResult result, MPImage ignoredInput) {
        try {
            if (!closed) {
                resultCount++;
                if (resultCount == 1 || resultCount % 30 == 0) {
                    Log.i(TAG, "result #" + resultCount + " hands=" + result.landmarks().size());
                }
                listener.onGestureResult(HandsResult.fromMediaPipe(result, pendingWidth, pendingHeight));
            }
        } catch (Throwable error) {
            Log.e(TAG, "Listener crashed while handling result", error);
        } finally {
            releasePending();
            busy.set(false);
        }
    }

    private void handleError(Throwable error) {
        Log.e(TAG, "Gesture recognizer failed", error);
        releasePending();
        busy.set(false);
        try {
            if (!closed) {
                listener.onGestureError(error);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releasePending() {
        MPImage image = pendingImage;
        pendingImage = null;
        if (image != null) {
            try {
                image.close();
            } catch (Throwable ignored) {
            }
        }
        Image frame = pendingImageFrame;
        pendingImageFrame = null;
        if (frame != null) {
            try {
                frame.close();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            recognizer.close();
        } catch (Throwable error) {
            Log.w(TAG, "recognizer.close failed", error);
        }
        releasePending();
    }
}

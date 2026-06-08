package com.example.rokidgesture;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public final class MainActivity extends Activity
        implements CameraController.FrameSink, GestureRecognizerRunner.Listener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA = 100;
    private static final String EXTRA_CAMERA_ID = "camera_id";
    private static final long TAP_TIMEOUT_MS = 250L;
    private static final long LONG_PRESS_TIMEOUT_MS = 450L;

    private GestureOverlayView gestureOverlay;
    private CameraController cameraController;
    private GestureRecognizerRunner gestureRunner;
    private LandmarkRecorder recorder;
    private LandmarkSmoother smoother;
    private FpsMeter fpsMeter;
    private GpuUsageMonitor gpuMonitor;

    private long totalFrames;
    private long sessionFrames;
    private long touchDownAtMs;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        gestureOverlay = findViewById(R.id.gestureOverlay);

        smoother = new LandmarkSmoother();
        recorder = new LandmarkRecorder();
        fpsMeter = new FpsMeter();
        gpuMonitor = new GpuUsageMonitor();
        gestureOverlay.setMonitors(fpsMeter, gpuMonitor);

        gestureOverlay.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownAtMs = event.getEventTime();
                    gestureOverlay.toggleInfoExpanded();
                    return true;
                case MotionEvent.ACTION_UP:
                    long pressDurationMs = event.getEventTime() - touchDownAtMs;
                    if (pressDurationMs >= LONG_PRESS_TIMEOUT_MS) {
                        gestureOverlay.toggleInfoExpanded();
                        toggleRecording();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });

        if (hasCameraPermission()) {
            initCameraAndRecognizer();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gpuMonitor != null) gpuMonitor.start();
        if (hasCameraPermission() && gestureRunner == null) {
            initCameraAndRecognizer();
        }
    }

    @Override
    protected void onPause() {
        stopRecording();
        if (cameraController != null) {
            cameraController.stop();
        }
        GestureRecognizerRunner runner = gestureRunner;
        gestureRunner = null;
        if (runner != null) {
            try {
                runner.close();
            } catch (Throwable error) {
                Log.w(TAG, "Failed to close gesture runner", error);
            }
        }
        if (gpuMonitor != null) gpuMonitor.stop();
        if (fpsMeter != null) fpsMeter.reset();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recorder.isRecording()) {
            try {
                recorder.stop();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean canAcceptFrame() {
        return gestureRunner != null && gestureRunner.canAcceptFrame();
    }

    @Override
    public void onFrame(Image image, int rotationDegrees, long timestampMs) {
        GestureRecognizerRunner runner = gestureRunner;
        if (runner == null) {
            if (image != null) image.close();
            return;
        }
        totalFrames++;
        if (totalFrames == 1 || totalFrames % 30 == 0) {
            Log.i(TAG, "Total frames: " + totalFrames);
        }
        if (!runner.recognize(image, rotationDegrees, timestampMs)) {
            if (image != null) image.close();
        }
    }

    @Override
    public void onCameraStatus(String status) {
        Log.i(TAG, "Camera: " + status);
    }

    @Override
    public void onCameraError(Throwable error) {
        Log.e(TAG, "Camera error", error);
        runOnUiThread(() ->
                Toast.makeText(this, "Camera error: " + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    @Override
    public void onMirrorChanged(boolean mirrored) {
        gestureOverlay.setMirrorPreview(mirrored);
    }

    @Override
    public void onGestureResult(HandsResult result) {
        if (fpsMeter != null) fpsMeter.tickRecognizer();
        HandsResult smoothed = smoother.smooth(result);

        if (recorder.isRecording() && smoothed.hasAnyHand()) {
            recorder.write(smoothed);
            sessionFrames++;
        }

        if (totalFrames % 60 == 0) {
            HandResult primary = smoothed.primaryHand();
            Log.i(TAG, "Gesture result: hands=" + smoothed.handCount() + " label=" + primary.label());
        }

        gestureOverlay.setResult(smoothed);
    }

    @Override
    public void onGestureError(Throwable error) {
        Log.e(TAG, "Gesture recognizer error", error);
    }

    private void toggleRecording() {
        if (recorder.isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        try {
            File file = recorder.start(this);
            sessionFrames = 0;
            gestureOverlay.setRecording(true);
            String msg = String.format(Locale.US, "REC %s", file.getName());
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Recording started -> " + file.getAbsolutePath());
        } catch (IOException error) {
            Log.e(TAG, "Failed to start recording", error);
            Toast.makeText(this, "Recording failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        if (!recorder.isRecording()) {
            return;
        }
        try {
            File file = recorder.stop();
            gestureOverlay.setRecording(false);
            String msg = String.format(Locale.US, "Saved %d frames to %s", sessionFrames, file.getName());
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Recording stopped. Frames: " + sessionFrames + " -> " + file.getAbsolutePath());
        } catch (IOException error) {
            Log.e(TAG, "Failed to stop recording", error);
            gestureOverlay.setRecording(false);
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCameraAndRecognizer();
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void initCameraAndRecognizer() {
        if (gestureRunner == null) {
            try {
                gestureRunner = new GestureRecognizerRunner(this, this);
            } catch (Throwable error) {
                Log.e(TAG, "Failed to load gesture recognizer model", error);
                Toast.makeText(this, "Model load failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (cameraController == null) {
            cameraController = new CameraController(this, this);
        }

        String forcedCameraId = getIntent().getStringExtra(EXTRA_CAMERA_ID);
        cameraController.start(forcedCameraId);
    }
}

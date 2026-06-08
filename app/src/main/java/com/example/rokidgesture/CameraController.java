package com.example.rokidgesture;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CameraController {
    interface FrameSink {
        boolean canAcceptFrame();
        void onFrame(Image image, int rotationDegrees, long timestampMs);
        void onCameraStatus(String status);
        void onCameraError(Throwable error);
        void onMirrorChanged(boolean mirrored);
    }

    private static final String TAG = "CameraController";
    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 240;
    private static final int RECONNECT_DELAY_MS = 1000;

    private final Context context;
    private final FrameSink frameSink;
    private YuvToRgbConverter converter = new YuvToRgbConverter(0);

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Size frameSize = new Size(TARGET_WIDTH, TARGET_HEIGHT);
    private String forcedCameraId;
    private boolean mirrored;
    private volatile boolean stopped;
    private int frameCount;
    private long lastNullLogMs;
    private int rotationDegrees;

    CameraController(Context context, FrameSink frameSink) {
        this.context = context.getApplicationContext();
        this.frameSink = frameSink;
    }

    void start(String forcedCameraId) {
        this.forcedCameraId = forcedCameraId;
        this.stopped = false;
        startThread();
        Log.i(TAG, "start() handler=" + (cameraHandler != null));
        Handler handler = cameraHandler;
        if (handler != null) {
            handler.post(this::openCamera);
        }
    }

    void stop() {
        stopped = true;
        Handler handler = cameraHandler;
        if (handler != null) {
            // Tear the camera down on its own thread to avoid racing with
            // onImageAvailable / capture callbacks.
            handler.post(this::closeCameraResources);
        } else {
            closeCameraResources();
        }
        stopThread();
    }

    private void closeCameraResources() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop capture session", e);
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void startThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("RokidCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try { cameraThread.join(); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        Log.i(TAG, "openCamera() called, stopped=" + stopped);
        if (stopped) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            frameSink.onCameraError(new SecurityException("CAMERA permission is not granted."));
            return;
        }

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraSelection selection = selectCamera(manager);
            String cameraId = selection.cameraId;
            frameSize = selection.size;
            mirrored = selection.facing == CameraCharacteristics.LENS_FACING_FRONT;
            // Sensor frames may be rotated relative to a portrait display surface;
            // rotate to upright so MediaPipe sees a normal-looking image.
            int displayRotation = ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getRotation();
            int screenDegrees = 0;
            switch (displayRotation) {
                case android.view.Surface.ROTATION_0: screenDegrees = 0; break;
                case android.view.Surface.ROTATION_90: screenDegrees = 90; break;
                case android.view.Surface.ROTATION_180: screenDegrees = 180; break;
                case android.view.Surface.ROTATION_270: screenDegrees = 270; break;
            }
            rotationDegrees = (selection.sensorOrientation - screenDegrees + 360) % 360;
            Log.i(TAG, "Sensor: " + selection.sensorOrientation + "° Display: " + screenDegrees + "° → Rotate: " + rotationDegrees + "°");
            frameSink.onMirrorChanged(mirrored);
            frameSink.onCameraStatus(String.format(Locale.US, "camera=%s %dx%d", cameraId, frameSize.getWidth(), frameSize.getHeight()));

            imageReader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Throwable error) {
            Log.e(TAG, "openCamera failed: " + error.getMessage());
            frameSink.onCameraStatus("openCamera failed: " + error.getMessage());
            Handler handler = cameraHandler;
            if (!stopped && handler != null) {
                handler.postDelayed(this::openCamera, RECONNECT_DELAY_MS);
            }
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.i(TAG, "CameraDevice onOpened");
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "Camera disconnected, retrying in " + RECONNECT_DELAY_MS + "ms...");
            camera.close();
            cameraDevice = null;
            captureSession = null;
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            frameSink.onCameraStatus("disconnected, retrying...");
            Handler handler = cameraHandler;
            if (!stopped && handler != null) {
                handler.postDelayed(CameraController.this::openCamera, RECONNECT_DELAY_MS);
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "CameraDevice error: " + error);
            camera.close();
            cameraDevice = null;
            Handler handler = cameraHandler;
            if (!stopped && handler != null) {
                handler.postDelayed(CameraController.this::openCamera, RECONNECT_DELAY_MS);
            }
        }
    };

    private void createCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) return;
            android.view.Surface analysisSurface = imageReader.getSurface();

            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(analysisSurface);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(Collections.singletonList(analysisSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                            } catch (Exception error) {
                                frameSink.onCameraError(error);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            frameSink.onCameraError(new RuntimeException("Camera capture session configuration failed."));
                        }
                    }, cameraHandler);
        } catch (Throwable error) {
            frameSink.onCameraError(error);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                long now = System.currentTimeMillis();
                if (now - lastNullLogMs > 3000) {
                    Log.w(TAG, "acquireLatestImage returned null (camera stalled)");
                    lastNullLogMs = now;
                }
                return;
            }

            frameCount++;
            if (frameCount == 1 || frameCount % 60 == 0) {
                Log.i(TAG, "Frame #" + frameCount + " size=" + image.getWidth() + "x" + image.getHeight());
            }

            if (!frameSink.canAcceptFrame()) return;

            long timestampMs = image.getTimestamp() / 1_000_000L;
            frameSink.onFrame(image, rotationDegrees, timestampMs);
            image = null;
        } catch (Throwable error) {
            Log.e(TAG, "onImageAvailable error", error);
            frameSink.onCameraError(error);
        } finally {
            if (image != null) image.close();
        }
    }

    private CameraSelection selectCamera(CameraManager manager) throws Exception {
        if (forcedCameraId != null && !forcedCameraId.trim().isEmpty()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(forcedCameraId);
            return new CameraSelection(forcedCameraId, chooseSize(characteristics), getFacing(characteristics), getSensorOrientation(characteristics));
        }

        List<CameraSelection> selections = new ArrayList<>();
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            selections.add(new CameraSelection(cameraId, chooseSize(characteristics), getFacing(characteristics), getSensorOrientation(characteristics)));
        }

        selections.sort((a, b) -> Integer.compare(facingRank(a.facing), facingRank(b.facing)));
        if (selections.isEmpty()) throw new IllegalStateException("No camera found.");
        return selections.get(0);
    }

    private static int getFacing(CameraCharacteristics c) {
        Integer f = c.get(CameraCharacteristics.LENS_FACING);
        return f == null ? -1 : f;
    }

    private static int facingRank(int facing) {
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return 0;
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return 1;
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return 2;
        return 3;
    }

    private static Size chooseSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(TARGET_WIDTH, TARGET_HEIGHT);
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return new Size(TARGET_WIDTH, TARGET_HEIGHT);
        return Arrays.stream(sizes)
                .min(Comparator.comparingInt(s ->
                        Math.abs(s.getWidth() - TARGET_WIDTH) + Math.abs(s.getHeight() - TARGET_HEIGHT)
                                + Math.abs(s.getWidth() * TARGET_HEIGHT - s.getHeight() * TARGET_WIDTH) / 100))
                .orElse(new Size(TARGET_WIDTH, TARGET_HEIGHT));
    }

    private static int getSensorOrientation(CameraCharacteristics c) {
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return orientation == null ? 0 : orientation;
    }

    private static final class CameraSelection {
        final String cameraId;
        final Size size;
        final int facing;
        final int sensorOrientation;
        CameraSelection(String cameraId, Size size, int facing, int sensorOrientation) {
            this.cameraId = cameraId;
            this.size = size;
            this.facing = facing;
            this.sensorOrientation = sensorOrientation;
        }
    }
}

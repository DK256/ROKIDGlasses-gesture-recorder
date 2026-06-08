package com.example.rokidgesture;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

final class YuvToRgbConverter {
    private final int rotation; // 0, 90, 180, 270
    private int[] argbBuffer;
    private byte[] yRowBuf;
    private byte[] uRowBuf;
    private byte[] vRowBuf;
    private Bitmap reusableBitmap;
    private int reusableWidth;
    private int reusableHeight;

    YuvToRgbConverter(int rotation) {
        int normalized = rotation % 360;
        if (normalized < 0) normalized += 360;
        this.rotation = normalized;
    }

    Bitmap toBitmap(Image image) {
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        boolean transpose = (rotation == 90 || rotation == 270);
        int dstW = transpose ? srcH : srcW;
        int dstH = transpose ? srcW : srcH;
        int pixelCount = dstW * dstH;

        if (argbBuffer == null || argbBuffer.length < pixelCount) {
            argbBuffer = new int[pixelCount];
        }

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer();
        ByteBuffer uPlane = planes[1].getBuffer();
        ByteBuffer vPlane = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // Row-sized scratch buffers — local byte[] is far cheaper than per-pixel
        // ByteBuffer.get(int) bounds checks.
        if (yRowBuf == null || yRowBuf.length < yRowStride) yRowBuf = new byte[yRowStride];
        if (uRowBuf == null || uRowBuf.length < uvRowStride) uRowBuf = new byte[uvRowStride];
        if (vRowBuf == null || vRowBuf.length < uvRowStride) vRowBuf = new byte[uvRowStride];

        int lastUvY = -1;
        for (int y = 0; y < srcH; y++) {
            int yPos = yRowStride * y;
            if (yPos + yRowStride > yPlane.limit()) {
                // Last row may have less than a full stride of bytes.
                yPlane.position(yPos);
                yPlane.get(yRowBuf, 0, yPlane.remaining());
            } else {
                yPlane.position(yPos);
                yPlane.get(yRowBuf, 0, yRowStride);
            }

            int uvY = y >> 1;
            if (uvY != lastUvY) {
                int uvPos = uvRowStride * uvY;
                if (uvPos + uvRowStride > uPlane.limit()) {
                    uPlane.position(uvPos);
                    uPlane.get(uRowBuf, 0, uPlane.remaining());
                    vPlane.position(uvPos);
                    vPlane.get(vRowBuf, 0, vPlane.remaining());
                } else {
                    uPlane.position(uvPos);
                    uPlane.get(uRowBuf, 0, uvRowStride);
                    vPlane.position(uvPos);
                    vPlane.get(vRowBuf, 0, uvRowStride);
                }
                lastUvY = uvY;
            }

            // Per-row destination origin/step depending on rotation.
            int dstBase;
            int dstStep;
            switch (rotation) {
                case 90:
                    dstBase = srcH - 1 - y;
                    dstStep = dstW;
                    break;
                case 180:
                    dstBase = (dstH - 1 - y) * dstW + (dstW - 1);
                    dstStep = -1;
                    break;
                case 270:
                    dstBase = y + (dstH - 1) * dstW;
                    dstStep = -dstW;
                    break;
                default:
                    dstBase = y * dstW;
                    dstStep = 1;
                    break;
            }

            for (int x = 0; x < srcW; x++) {
                int yValue = yRowBuf[x] & 0xff;
                int uvOffset = (x >> 1) * uvPixelStride;
                int uValue = uRowBuf[uvOffset] & 0xff;
                int vValue = vRowBuf[uvOffset] & 0xff;

                int c = yValue - 16;
                if (c < 0) c = 0;
                int d = uValue - 128;
                int e = vValue - 128;

                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;

                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;

                argbBuffer[dstBase + x * dstStep] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        if (reusableBitmap == null || reusableWidth != dstW || reusableHeight != dstH) {
            reusableBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
            reusableWidth = dstW;
            reusableHeight = dstH;
        }
        reusableBitmap.setPixels(argbBuffer, 0, dstW, 0, 0, dstW, dstH);
        return reusableBitmap;
    }
}

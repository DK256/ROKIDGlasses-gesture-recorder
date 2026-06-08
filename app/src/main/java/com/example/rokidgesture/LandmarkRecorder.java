package com.example.rokidgesture;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

final class LandmarkRecorder {
    private static final String TAG = "LandmarkRecorder";

    private BufferedWriter writer;
    private File outputFile;
    private int frameCount;

    boolean isRecording() {
        return writer != null;
    }

    File start(Context context) throws IOException {
        stop();
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "gesture-recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create " + dir);
        }

        outputFile = new File(dir, String.format(Locale.US, "gesture-%d.jsonl", System.currentTimeMillis()));
        writer = new BufferedWriter(new FileWriter(outputFile, false));
        frameCount = 0;
        return outputFile;
    }

    int frameCount() {
        return frameCount;
    }

    File outputFile() {
        return outputFile;
    }

    void write(HandsResult result) {
        if (writer == null || result == null || !result.hasAnyHand()) {
            return;
        }

        try {
            JSONObject packet = new JSONObject();
            packet.put("timestampMs", result.timestampMs);
            packet.put("imageWidth", result.imageWidth);
            packet.put("imageHeight", result.imageHeight);
            packet.put("handCount", result.handCount());

            HandResult primary = result.primaryHand();
            packet.put("gesture", primary.gesture);
            packet.put("gestureScore", primary.gestureScore);
            packet.put("handedness", primary.handedness);
            packet.put("landmarks", encodePoints(primary.landmarks));

            JSONArray hands = new JSONArray();
            List<HandResult> visibleHands = result.visibleHands();
            for (HandResult hand : visibleHands) {
                JSONObject item = new JSONObject();
                item.put("gesture", hand.gesture);
                item.put("gestureScore", hand.gestureScore);
                item.put("handedness", hand.handedness);
                item.put("landmarks", encodePoints(hand.landmarks));
                hands.put(item);
            }
            packet.put("hands", hands);

            writer.write(packet.toString());
            writer.newLine();
            frameCount++;
        } catch (JSONException | IOException error) {
            Log.e(TAG, "Failed to write gesture frame", error);
        }
    }

    private JSONArray encodePoints(List<HandResult.Point3> landmarks) throws JSONException {
        JSONArray points = new JSONArray();
        for (HandResult.Point3 point : landmarks) {
            JSONArray item = new JSONArray();
            item.put(point.x);
            item.put(point.y);
            item.put(point.z);
            points.put(item);
        }
        return points;
    }

    File stop() throws IOException {
        File finished = outputFile;
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        return finished;
    }
}

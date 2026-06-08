package com.example.rokidgesture;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class HandResult {
    static final HandResult EMPTY = new HandResult(
            Collections.emptyList(),
            "",
            0f,
            "",
            0L,
            0,
            0
    );

    final List<Point3> landmarks;
    final String gesture;
    final float gestureScore;
    final String handedness;
    final long timestampMs;
    final int imageWidth;
    final int imageHeight;

    HandResult(
            List<Point3> landmarks,
            String gesture,
            float gestureScore,
            String handedness,
            long timestampMs,
            int imageWidth,
            int imageHeight
    ) {
        this.landmarks = landmarks;
        this.gesture = gesture;
        this.gestureScore = gestureScore;
        this.handedness = handedness;
        this.timestampMs = timestampMs;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    static HandResult fromMediaPipeHand(
            List<NormalizedLandmark> source,
            List<Category> gestures,
            List<Category> handedness,
            long timestampMs,
            int imageWidth,
            int imageHeight
    ) {
        List<Point3> points = new ArrayList<>(source.size());
        for (NormalizedLandmark landmark : source) {
            points.add(new Point3(landmark.x(), landmark.y(), landmark.z()));
        }

        String gestureName = "";
        float gestureScore = 0f;
        if (gestures != null && !gestures.isEmpty()) {
            Category selected = selectGestureCategory(gestures);
            gestureName = selected.categoryName();
            gestureScore = selected.score();
        }

        String handednessName = "";
        if (handedness != null && !handedness.isEmpty()) {
            handednessName = handedness.get(0).categoryName();
        }

        return new HandResult(points, gestureName, gestureScore, handednessName, timestampMs, imageWidth, imageHeight);
    }

    boolean hasHand() {
        return landmarks.size() >= 21;
    }

    private static Category selectGestureCategory(List<Category> gestures) {
        Category fallback = gestures.get(0);
        for (Category category : gestures) {
            if (category == null) continue;
            String name = category.categoryName();
            if (name != null && !name.isEmpty() && !"None".equalsIgnoreCase(name)) {
                return category;
            }
        }
        return fallback;
    }

    String gestureDisplayName() {
        if (gesture == null || gesture.isEmpty() || "None".equalsIgnoreCase(gesture)) {
            return "TRACKING";
        }
        return gesture.replace('_', ' ');
    }

    String label() {
        if (!hasHand()) {
            return "NO HAND";
        }
        return String.format(Locale.US, "%s %.0f%%", gestureDisplayName(), gestureScore * 100f);
    }

    static final class Point3 {
        final float x;
        final float y;
        final float z;

        Point3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}

final class HandsResult {
    static final HandsResult EMPTY = new HandsResult(Collections.emptyList(), 0L, 0, 0);

    final List<HandResult> hands;
    final long timestampMs;
    final int imageWidth;
    final int imageHeight;

    HandsResult(List<HandResult> hands, long timestampMs, int imageWidth, int imageHeight) {
        this.hands = hands;
        this.timestampMs = timestampMs;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    static HandsResult fromMediaPipe(GestureRecognizerResult result, int imageWidth, int imageHeight) {
        if (result == null || result.landmarks().isEmpty()) {
            return EMPTY;
        }

        List<HandResult> hands = new ArrayList<>(result.landmarks().size());
        for (int i = 0; i < result.landmarks().size(); i++) {
            List<Category> gestures = i < result.gestures().size() ? result.gestures().get(i) : Collections.emptyList();
            List<Category> handedness = i < result.handedness().size() ? result.handedness().get(i) : Collections.emptyList();
            hands.add(HandResult.fromMediaPipeHand(
                    result.landmarks().get(i),
                    gestures,
                    handedness,
                    result.timestampMs(),
                    imageWidth,
                    imageHeight
            ));
        }

        hands.sort(HAND_ORDER);
        return new HandsResult(Collections.unmodifiableList(hands), result.timestampMs(), imageWidth, imageHeight);
    }

    boolean hasAnyHand() {
        for (HandResult hand : hands) {
            if (hand != null && hand.hasHand()) {
                return true;
            }
        }
        return false;
    }

    int handCount() {
        int count = 0;
        for (HandResult hand : hands) {
            if (hand != null && hand.hasHand()) {
                count++;
            }
        }
        return count;
    }

    HandResult primaryHand() {
        for (HandResult hand : hands) {
            if (hand != null && hand.hasHand()) {
                return hand;
            }
        }
        return HandResult.EMPTY;
    }

    List<HandResult> visibleHands() {
        if (hands.isEmpty()) {
            return Collections.emptyList();
        }
        List<HandResult> visible = new ArrayList<>(hands.size());
        for (HandResult hand : hands) {
            if (hand != null && hand.hasHand()) {
                visible.add(hand);
            }
        }
        return visible;
    }

    private static final Comparator<HandResult> HAND_ORDER = (a, b) -> {
        int rankA = handednessRank(a);
        int rankB = handednessRank(b);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        float ax = wristX(a);
        float bx = wristX(b);
        return Float.compare(ax, bx);
    };

    private static int handednessRank(HandResult hand) {
        if (hand == null || hand.handedness == null) return 2;
        if ("Left".equalsIgnoreCase(hand.handedness)) return 0;
        if ("Right".equalsIgnoreCase(hand.handedness)) return 1;
        return 2;
    }

    private static float wristX(HandResult hand) {
        if (hand == null || hand.landmarks.isEmpty()) return Float.MAX_VALUE;
        return hand.landmarks.get(0).x;
    }
}

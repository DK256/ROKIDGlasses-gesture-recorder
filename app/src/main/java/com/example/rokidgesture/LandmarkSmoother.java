package com.example.rokidgesture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LandmarkSmoother {
    private static final float ALPHA = 0.42f;
    private HandResult previousLeft = HandResult.EMPTY;
    private HandResult previousRight = HandResult.EMPTY;
    private HandResult previousUnknown = HandResult.EMPTY;

    HandsResult smooth(HandsResult current) {
        if (current == null || !current.hasAnyHand()) {
            previousLeft = HandResult.EMPTY;
            previousRight = HandResult.EMPTY;
            previousUnknown = HandResult.EMPTY;
            return HandsResult.EMPTY;
        }

        List<HandResult> visibleHands = current.visibleHands();
        List<HandResult> smoothedHands = new ArrayList<>(visibleHands.size());
        boolean sawLeft = false;
        boolean sawRight = false;
        boolean sawUnknown = false;

        for (HandResult hand : visibleHands) {
            String slot = slotFor(hand);
            HandResult previous = previousFor(slot);
            HandResult smoothed = smoothHand(previous, hand);
            smoothedHands.add(smoothed);
            setPrevious(slot, smoothed);

            if ("Left".equals(slot)) {
                sawLeft = true;
            } else if ("Right".equals(slot)) {
                sawRight = true;
            } else {
                sawUnknown = true;
            }
        }

        if (!sawLeft) previousLeft = HandResult.EMPTY;
        if (!sawRight) previousRight = HandResult.EMPTY;
        if (!sawUnknown) previousUnknown = HandResult.EMPTY;

        return new HandsResult(Collections.unmodifiableList(smoothedHands), current.timestampMs, current.imageWidth, current.imageHeight);
    }

    private HandResult smoothHand(HandResult previous, HandResult current) {
        if (current == null || !current.hasHand()) {
            return HandResult.EMPTY;
        }

        if (previous == null || !previous.hasHand() || previous.landmarks.size() != current.landmarks.size()) {
            return current;
        }

        List<HandResult.Point3> smoothed = new ArrayList<>(current.landmarks.size());
        for (int i = 0; i < current.landmarks.size(); i++) {
            HandResult.Point3 next = current.landmarks.get(i);
            HandResult.Point3 last = previous.landmarks.get(i);
            smoothed.add(new HandResult.Point3(
                    lerp(last.x, next.x, ALPHA),
                    lerp(last.y, next.y, ALPHA),
                    lerp(last.z, next.z, ALPHA)
            ));
        }

        return new HandResult(
                smoothed,
                current.gesture,
                current.gestureScore,
                current.handedness,
                current.timestampMs,
                current.imageWidth,
                current.imageHeight
        );
    }

    private HandResult previousFor(String slot) {
        if ("Left".equals(slot)) return previousLeft;
        if ("Right".equals(slot)) return previousRight;
        return previousUnknown;
    }

    private void setPrevious(String slot, HandResult hand) {
        if ("Left".equals(slot)) {
            previousLeft = hand;
        } else if ("Right".equals(slot)) {
            previousRight = hand;
        } else {
            previousUnknown = hand;
        }
    }

    private String slotFor(HandResult hand) {
        if (hand == null || hand.handedness == null) return "Unknown";
        if ("Left".equalsIgnoreCase(hand.handedness)) return "Left";
        if ("Right".equalsIgnoreCase(hand.handedness)) return "Right";
        return "Unknown";
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}

# Rokid Gesture Recorder

Native Android implementation for realtime hand gesture detection on Rokid Glasses.

## Pipeline

```text
Rokid camera -> Android Camera2 ImageReader -> MediaPipe GestureRecognizer LIVE_STREAM
             -> 21 landmarks + gesture label -> native overlay view + JSONL recording
```

The app uses:

- Camera2 for the glasses camera preview and YUV frame stream.
- MediaPipe Tasks Vision `GestureRecognizer` in `LIVE_STREAM` mode.
- A custom `GestureOverlayView` for hand contour, matchstick-style bones, and red joints.
- `LandmarkRecorder` to store timestamped gesture packets as `.jsonl` files.

## Model

Download the model before building:

```sh
sh scripts/download-model.sh
```

or manually place `gesture_recognizer.task` into `app/src/main/assets/`.

## Build

Open this directory in Android Studio and run the `app` target on Rokid Glasses.

If using command line, install Android Gradle tooling first, then run:

```sh
gradle :app:assembleDebug
```

## Camera Selection

The camera picker prefers `EXTERNAL`, then `BACK`, then `FRONT`. On some Rokid firmware builds the glasses POV camera may report a different facing value. To force a specific camera, launch with:

```sh
adb shell am start -n com.example.rokidgesture/.MainActivity --es camera_id 0
```
<img width="1228" height="1666" alt="image" src="https://github.com/user-attachments/assets/18ff554f-5697-414d-844f-27cce386fa93" />
<img width="1260" height="1700" alt="image" src="https://github.com/user-attachments/assets/80770d4b-9d55-4612-b6cc-583db2d69013" />
<img width="2366" height="1810" alt="image" src="https://github.com/user-attachments/assets/0beebb48-3944-4020-81a2-03268214a117" />

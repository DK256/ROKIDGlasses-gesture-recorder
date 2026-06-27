---
name: mirror-gesture-y-axis
overview: 按用户反馈，在现有手势骨架坐标变换基础上追加一次 Y 轴镜像，保持当前 UI/HUD/安全框不变。
todos:
  - id: update-y-mirror
    content: 在 GestureOverlayView 增加手势 Y 轴镜像
    status: completed
  - id: verify-ui-scope
    content: 确认 HUD 和安全框不受影响
    status: completed
    dependencies:
      - update-y-mirror
  - id: build-debug
    content: 执行 assembleDebug 验证构建
    status: completed
    dependencies:
      - verify-ui-scope
  - id: install-if-needed
    content: 按需安装最新 Debug APK
    status: completed
    dependencies:
      - build-debug
---

## User Requirements

- 在当前 UI 布局保持正确不变的前提下，仅调整手势骨架画面的坐标显示。
- 已有手势骨架变换为“逆时针旋转 90 度 + X 方向镜像”，现在需要在此基础上对 Y 轴也再镜像一次。
- 修改后 HUD、状态文字、安全框、录制指示点等界面元素不应被旋转、翻转或重新布局。

## Product Overview

这是一个 ROKID 眼镜手势识别与录制应用，界面上叠加显示手势骨架、状态信息和辅助安全区域。当前需求只修正手势骨架方向，使用户看到的手势位置与实际画面方向一致。

## Core Features

- 手势骨架继续按现有逻辑逆时针旋转 90 度。
- 手势骨架同时进行 X 轴和 Y 轴镜像。
- 非手势 UI 元素保持当前视觉效果不变。
- 修改后重新编译验证，并可继续安装到设备测试。

## Tech Stack Selection

- 项目类型：Android 原生应用。
- 语言与构建：Java、Gradle Android 项目。
- 图形绘制：现有 `GestureOverlayView` 基于 Android `Canvas` 绘制手势骨架与 HUD。
- 复用现有架构：继续在 `/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/app/src/main/java/com/example/rokidgesture/GestureOverlayView.java` 内处理坐标变换，不引入新模块或新依赖。

## Implementation Approach

采用最小范围修改策略，只调整手势点位坐标映射函数，不修改 `onDraw()` 的 Canvas 旋转、不修改 Camera/MediaPipe 输入方向、不改 HUD 与安全框绘制流程。
当前 `sx()` 和 `sy()` 已通过 `gestureX()`、`gestureY()` 单独计算手势骨架坐标，因此只需在 `gestureY()` 中增加一次 `y = 1f - y`，或添加语义清晰的 `GESTURE_FORCE_MIRROR_Y` 常量后统一控制 Y 轴镜像。

关键决策：

- 保持 UI 正确性：不动 `canvas.translate(viewW, 0f)` 与 `canvas.rotate(90f)`，避免影响整屏 UI。
- 保持变更可回滚：用独立常量控制 Y 镜像，便于后续根据眼镜实测快速切换。
- 保持性能稳定：坐标变换为每个点常数级浮点计算，复杂度仍为 O(n)，n 为手势关键点数量，性能影响可忽略。

## Implementation Notes

- 仅修改 `GestureOverlayView.java` 中手势坐标变换相关代码。
- 不修改 `drawHud()`、`drawSafeZone()`、`drawRecIndicator()`，避免 UI 方向回归。
- 若添加 `GESTURE_FORCE_MIRROR_Y`，命名应与现有 `GESTURE_FORCE_MIRROR` 保持一致；也可将原常量重命名为 `GESTURE_FORCE_MIRROR_X`，但为降低影响，建议保留旧常量并新增 Y 常量。
- 构建验证使用项目根目录下 `./gradlew assembleDebug`。
- 如用户继续要求安装，再使用已生成的 `app/build/outputs/apk/debug/app-debug.apk` 执行 `adb install -r`。

## Architecture Design

当前链路为：

Camera/MediaPipe 识别结果 → `MainActivity.onGestureResult()` → `GestureOverlayView.setResult()` → `onDraw()` → `drawContour()` / `drawBones()` / `drawJoints()` → `sx()` / `sy()` 坐标映射。

本次只影响最后的坐标映射阶段：

- `gestureX()`：保留当前逆时针 90 度与 X 镜像。
- `gestureY()`：在当前逆时针 90 度后增加 Y 镜像。
- HUD 和安全框继续使用原有绘制坐标，不进入新的手势 Y 镜像逻辑。

## Directory Structure

```text
/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/
└── app/
    └── src/
        └── main/
            └── java/
                └── com/
                    └── example/
                        └── rokidgesture/
                            └── GestureOverlayView.java  # [MODIFY] 手势叠加绘制视图。新增或应用 Y 轴镜像逻辑，仅作用于手势骨架点位坐标；保持 HUD、安全框、录制点和整体 Canvas UI 方向不变。
```

## Key Code Structures

无需新增复杂接口或数据结构。核心变更为在 `gestureY(HandResult.Point3 point)` 返回前对归一化 Y 坐标执行一次镜像，并继续使用 `clamp01()` 限制坐标范围。
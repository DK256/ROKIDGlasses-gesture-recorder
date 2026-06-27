---
name: fix-rotation-and-mediapipe
overview: 修复两个问题：(1) GestureOverlayView 所有绘制内容顺时针旋转 90 度匹配 reverseLandscape；(2) 排查修复 MediaPipe 手势识别未运行
todos:
  - id: fix-ui-rotation
    content: 修改 GestureOverlayView.onDraw() 添加 canvas.rotate(90f, cx, cy) 旋转变换
    status: completed
  - id: fix-mediapipe-rotation
    content: 修复 GestureRecognizerRunner：在 MediaImageBuilder 中设置 rotationDegrees 传递给 MediaPipe
    status: completed
  - id: enhance-debug-logging
    content: 增强 GestureRecognizerRunner/CameraController/MainActivity 关键路径的调试日志输出
    status: completed
  - id: rebuild-and-install
    content: Clean 构建 APK 并通过 ADB 安装到 ROKID Glasses
    status: completed
    dependencies:
      - fix-ui-rotation
      - fix-mediapipe-rotation
      - enhance-debug-logging
  - id: verify-with-logcat
    content: 通过 adb logcat 验证 MediaPipe 初始化状态和手势识别结果
    status: completed
    dependencies:
      - rebuild-and-install
---

## 产品概述

ROKID Glasses 手势识别录制应用，通过 Camera2 API 采集摄像头画面、MediaPipe 识别手部关键点和手势，在眼镜屏幕上叠加显示骨骼可视化和关键点数据。

## 核心问题

### 问题一：UI 需要顺时针旋转 90 度

当前 `GestureOverlayView.onDraw()` 直接绘制所有内容（HUD文字、手部骨架连线、关节点圆点、关键点坐标数据列表、安全区域角标、录制指示灯），没有任何旋转变换。由于 AndroidManifest 设置了 `reverseLandscape`，在 ROKID Glasses 设备上显示时，所有画面（文字和框线）的方向不正确，需要整体顺时针旋转 90 度。

### 问题二：MediaPipe 手势识别引擎未正常运行

手势识别没有跑起来，可能原因包括：

1. GPU delegate 初始化失败后 CPU fallback 也失败（ROKID Glasses 的 OpenGL ES 支持有限）
2. `rotationDegrees` 计算错误导致 MediaPipe 推理异常
3. 模型文件加载或识别流程中的静默错误未被充分暴露
4. 相机帧传递链路中 canAcceptFrame busy 锁导致帧被丢弃

## 目标效果

- 所有叠加层内容（骨架/HUD/数据列/角标）顺时针旋转 90 度显示正确方向
- MediaPipe 正常检测手部并输出关键点数据和手势分类

## 技术栈

- 语言：Java 17 / 构建工具：Gradle 8.10.2 + AGP 8.7.3 / compileSdk/targetSdk = 34
- 相机 API：Camera2 (android.hardware.camera2) / AI 引擎：MediaPipe Tasks Vision 0.10.26
- 目标设备：ROKID Glasses (Android, reverseLandscape)

## 实现方案

### 问题一修复：GestureOverlayView 旋转 90°

**策略**：在 `onDraw()` 入口处对 Canvas 施加顺时针 90° 旋转变换，围绕视图中心点旋转。

**实现细节**：

```
onDraw(canvas):
  ① canvas.save()
  ② canvas.rotate(90f, cx, cy)   // cx = viewW/2, cy = viewH/2
  ③ [原有全部绘制逻辑保持不变]
  ④ canvas.restore()
```

**关键考量**：

- 旋转中心使用 `(viewW/2f, viewH/2f)` — 视图几何中心
- 旋转后坐标系互换：原来的 X 轴变为向下，Y 轴变为向左。但所有现有 `sx()`/`sy()` 坐标映射函数无需修改，因为它们基于 `getWidth()/getHeight()` 返回原始尺寸，Canvas 内部变换会自动处理
- `drawPoseColumn()` 中 `colLeft = viewW * 0.56f` 在旋转后的坐标系中位置会自然调整；如果视觉上需要微调可以后续优化
- 使用 `canvas.save()/canvas.restore()` 确保不影响系统其他绘制
- `drawRecIndicator` 中 `recCx = viewW - pad - recR`（右上角定位）也会随旋转自动修正到正确的屏幕角落

**修改文件**：仅 `GestureOverlayView.java` 的 `onDraw()` 方法

### 问题二修复：MediaPipe 诊断与修复

**根因分析优先级**：

**A. rotationDegrees 计算（最可能）**
`CameraController.openCamera()` 第142-152行：

```java
int displayRotation = getDefaultDisplay().getRotation(); // reverseLandscape → ROTATION_270
// ...
rotationDegrees = (sensorOrientation - screenDegrees + 360) % 360;
```

对于 ROKID Glasses 外部摄像头 + reverseLandscape 显示模式：

- 外部摄像头 sensorOrientation 通常为 0° 或 90°（取决于设备）
- reverseLandscape 下 displayRotation = ROTATION_270 → screenDegrees = 270
- 如果 sensorOrientation=0，则 rotationDegrees = (0 - 270 + 360) % 360 = 90°
- 这个 90° 传入 `GestureRecognizerRunner.recognize()` 再传入 `recognizer.recognizeAsync(mpImage, timestampMs)` — 注意：**当前代码实际上并没有将 rotationDegrees 传给 MediaPipe API！**

查看 `GestureRecognizerRunner.recognize()` 第85-112行：

```java
boolean recognize(Image image, int rotationDegrees, long timestampMs) {
    // rotationDegrees 只用于计算 pendingWidth/pendingHeight（处理 90/270 交换）
    pendingWidth = ((rotationDegrees == 90 || rotationDegrees == 270) ? image.getHeight() : image.getWidth());
    pendingHeight = ((rotationDegrees == 90 || rotationDegrees == 270) ? image.getWidth() : image.getHeight());
    MPImage mpImage = new MediaImageBuilder(image).build();
    recognizer.recognizeAsync(mpImage, timestampMs);  // ← rotationDegrees 未传给 MediaPipe!
}
```

**发现**：rotationDegrees 用于宽高交换计算但没有通过 `MediaImageBuilder` 设置图像方向信息给 MediaPipe。这可能导致 MediaPipe 收到方向错误的图像帧从而无法正确检测手部。

**修复方案 A**：在构建 MPImage 时设置正确的旋转信息：

```java
MPImage mpImage = new MediaImageBuilder(image)
    .setRotationDegrees(rotationDegrees)  // 添加此行
    .build();
```

**B. GPU delegate 失败静默**
`GestureRecognizerRunner.createRecognizer()` 尝试 GPU 后 fallback CPU，如果都失败会抛异常到 `MainActivity.initCameraAndRecognizer()` 并显示 Toast。但 Toast 可能在设备上不明显。需要增强日志。

**C. canAcceptFrame 竞态**
如果 recognizer 初始化耗时较长（GPU delegate），相机首帧到达时 `busy` 为 false 但 `closed` 为 false，应该能接受。但如果初始化完全失败则 `gestureRunner == null`，MainActivity.canAcceptFrame() 返回 false，帧被丢弃——这是预期行为但日志不够明确。

**D. 模型文件验证**
确保 assets 中的 `gesture_recognizer.task` 文件存在且可读（~8MB）。构建配置已有 `noCompress += ["task"]`。

## 实现注意事项

### 旋转修复

- 仅修改 GestureOverlayView.java 的 onDraw() 方法头部
- 保持所有 draw* 方法不变
- 测试确认 HUD 文字可读、骨架位置正确、数据列在合适区域

### MediaPipe 修复

- 在 GestureRecognizerRunner.recognize() 中为 MPImage 设置 rotationDegrees
- 增强 createRecognizer 的日志输出（GPU/CPU 初始化详情）
- 增强 MainActivity.onFrame/onGestureResult 日志便于排查
- 确保 CameraController 的 rotation 计算日志清晰
- 构建安装后通过 logcat 验证 MediaPipe 初始化状态和推理结果

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 深入分析 MediaPipe Tasks Vision API 中 MediaImageBuilder.setRotationDegrees() 的正确用法和参数范围
- Expected outcome: 确认 rotationDegrees 参数的正确传递方式，避免 API 误用
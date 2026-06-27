---
name: fix-build-and-ui-for-rokid-glasses
overview: 修复 ROKID Glasses 手势识别应用的构建问题（compileSdk/AGP 兼容性、签名配置）并补全 UI 功能（添加摄像头预览背景）以匹配目标截图效果
todos:
  - id: fix-frame-sink
    content: 修改 FrameSink 接口和 CameraController：新增 onPreviewFrame 回调，在 onImageAvailable 中调用 YuvToRgbConverter.toBitmap() 并触发回调
    status: pending
  - id: fix-overlay-preview
    content: 修改 GestureOverlayView：新增 setPreviewFrame 方法和 drawPreviewBackground 绘制逻辑，在 onDraw 最顶层绘制摄像头画面
    status: pending
    dependencies:
      - fix-frame-sink
  - id: fix-main-activity
    content: 修改 MainActivity：实现 onPreviewFrame 回调，将 Bitmap 传递给 gestureOverlay.setPreviewFrame()
    status: pending
    dependencies:
      - fix-frame-sink
  - id: fix-build-config
    content: 调整 build.gradle：targetSdk 降至 34 提升设备兼容性
    status: pending
  - id: rebuild-install
    content: 重新构建 APK并通过 ADB 安装到 ROKID Glasses
    status: pending
    dependencies:
      - fix-overlay-preview
      - fix-main-activity
      - fix-build-config
---

## 产品概述

ROKID Glasses 手势识别录制应用，通过摄像头实时采集画面、MediaPipe 识别手部关键点和手势，在眼镜屏幕上叠加显示骨骼可视化和关键点数据，并支持录制保存。

## 核心功能

- 摄像头实时预览画面作为背景（**当前缺失：显示黑屏**）
- MediaPipe 手部关键点检测（21点/手，支持双手）
- 手势分类识别与置信度显示
- 手部骨骼骨架可视化（轮廓+连线+关节点）
- 左侧关键点坐标数据列表（idx, name, x, y, z）
- 右上角 HUD 状态栏（REC fps / UI fps / GPU% / 手势状态）
- 安全区域角标提示
- JSONL 格式录制导出

## 关键问题确认

**代码逻辑本身没问题**，但存在两个层面的问题：

1. **UI 缺失摄像头背景渲染**（核心功能缺陷）：

- `CameraController.java:44` 创建了 `YuvToRgbConverter` 但从未调用 `toBitmap()`
- `GestureOverlayView.onDraw()` 只绘制叠加层（骨架/HUD/数据），没有绘制摄像头帧作为背景
- `MainActivity.onFrame()` 将 Image 直接传给 MediaPipe 推理，未做预览用途的 Bitmap 转换
- 结果：应用运行时显示纯黑背景+叠加层，而非截图中的实时摄像头画面+叠加层

2. **构建配置风险**：

- compileSdk=36 / targetSdk=36 过于激进，ROKID Glasses 设备可能运行较低 Android 版本导致兼容问题

## 技术栈

- 语言: Java 17
- 构建工具: Gradle 8.10.2 + AGP 8.7.3
- 相机 API: Camera2 (android.hardware.camera2)
- AI 引擎: MediaPipe Tasks Vision 0.10.26 (手势识别)
- 目标设备: ROKID Glasses (Android)

## 实现方案

### 核心策略：在现有架构上补全摄像头预览通道

利用已存在的 `YuvToRgbConverter`，将 Camera2 的 YUV 帧转为 Bitmap，通过 volatile 引用传递给 GestureOverlayView 在 onDraw 中作为底层背景绘制。这是最小改动方案，完全复用现有基础设施。

### 数据流变更

```
当前: Camera2 YUV Image → MediaPipe 推理 → HandsResult → Overlay 绘制叠加层（黑底）
修复后: Camera2 YUV Image → [并行] → YUV→Bitmap(预览) → Overlay 背景绘制
                              └→ MediaPipe 推理 → HandsResult → Overlay 叠加层绘制
```

### 关键设计决策

- **线程安全**: Camera 帧在 "RokidCamera" 线程转换，通过 `volatile Bitmap` + `synchronized` 传递到 UI 线程的 onDraw()
- **性能节流**: 不每帧都转换 YUV→Bitmap（约每 100ms 一帧，~10fps 预览够用），避免 CPU 过载；手势推理仍全速运行
- **内存复用**: 复用 YuvToRgbConverter 内部的 reusableBitmap 机制，避免频繁 GC
- **构建兼容性**: 降低 targetSdk 到 34 确保设备兼容，保留 compileSdk=36（有 suppressUnsupportedCompileSdk 兜底）

### 架构设计

```
CameraController (RokidCamera 线程)
  ├─ onImageAvailable() 
  │    ├─ converter.toBitmap(image) → frameSink.onPreviewFrame(bitmap)  [新增]
  │    └─ frameSink.onFrame(image, ...) → MediaPipe 推理  [原有]
  │
MainActivity (主线程 / FrameSink 实现)
  ├─ onPreviewFrame(bitmap) → gestureOverlay.setPreviewFrame(bitmap)  [新增]
  ├─ onFrame(...) → gestureRunner.recognize(...)  [原有]
  
GestureOverlayView.onDraw() (UI 线程)
  ├─ ① drawPreviewBackground(canvas)  // 新增：绘制摄像头画面背景
  ├─ ② drawSafeZone(...)
  ├─ ③ drawContour/drawBones/drawJoints(...)
  ├─ ④ drawHud(...)
  ├─ ⑤ drawPoseColumn(...)
  └─ ⑥ drawRecIndicator(...)
```

## 目录结构

```
/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/
├── app/build.gradle                              # [MODIFY] 降低 targetSdk 到 34
├── app/src/main/java/com/example/rokidgesture/
│   ├── CameraController.java                     # [MODIFY] 在 onImageAvailable 中调用 converter.toBitmap 并回调 onPreviewFrame
│   ├── MainActivity.java                         # [MODIFY] 实现 onPreviewFrame 方法并传递给 GestureOverlayView
│   ├── GestureOverlayView.java                   # [MODIFY] 新增 setPreviewFrame 方法 + drawPreviewBackground 绘制
│   └── YuvToRgbConverter.java                    # [无修改] 已具备完整功能，直接复用
└── app/src/main/res/layout/
    └── activity_main.xml                        # [无修改] 布局结构不变
```

## 实现注意事项

- CameraController.FrameSink 接口需新增 `void onPreviewFrame(Bitmap bitmap)` 回调方法，所有实现者需要补充实现
- `onImageAvailable` 中应在传递给 MediaPipe 之前或同时进行 Bitmap 转换，注意 Image 对象生命周期管理（同一 Image 可用于两处：一次转 Bitmap 给预览，一次传 MediaPipe 推理——但 Image.close() 需要在两者都完成后才调用）
- 实际上更安全的做法是：先 acquireLatestImage，clone 或 convert 后立即 close image，然后将 bitmap 和原始 image 分别处理。但由于 MediaPipe 也需要这个 Image，需要在 recognize 返回后才 close。所以最佳方案是：在 onImageAvailable 中用 converter.toBitmap() 转完后传 bitmap 出去，image 本身继续走原有流程传给 gestureRunner。
- GestureOverlayView.setPreviewFrame 用 volatile Bitmap 存储，onDraw 中同步读取并绘制，绘制完成后不释放（由下一帧覆盖）
- 缩放绘制：320x240 的预览 Bitmap 需要拉伸填满整个 canvas（保持宽高比居中裁切或拉伸填充）
- 构建：降低 targetSdk 到 34 确保 ROKID Glasses 设备兼容性
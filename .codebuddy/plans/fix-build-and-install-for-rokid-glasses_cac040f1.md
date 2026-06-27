---
name: fix-build-and-install-for-rokid-glasses
overview: 只修复 build.gradle 构建配置（targetSdk 降至 34 提升兼容性）并重新通过 ADB 安装到 ROKID Glasses 设备，不修改 UI 代码
todos:
  - id: fix-build-config
    content: 修复 build.gradle 和 gradle.properties：将 compileSdk/targetSdk 从 36 降至 34，移除 suppressUnsupportedCompileSdk workaround
    status: completed
  - id: rebuild-install
    content: clean 构建 debug APK 并通过 adb install -r 安装到 ROKID Glasses 设备
    status: completed
    dependencies:
      - fix-build-config
---

## Product Overview

ROKID Glasses 手势识别录制应用。通过 Camera2 API 采集摄像头画面、MediaPipe 识别手部 21 个关键点和手势分类，在眼镜屏幕上叠加显示骨骼可视化（轮廓/骨架连线/关节点）和关键点坐标数据列表（idx name x y z），支持 HUD 状态栏（FPS/GPU%/手势状态）、安全区域角标、JSONL 格式录制导出。

## Core Features

- MediaPipe Gesture Recognizer 实时手部关键点检测（21点/手，支持双手）
- 手势分类识别与置信度显示（过滤 None 类别）
- 手部骨骼骨架可视化（白色轮廓线 + 骨架连线 + 关键点圆点）
- 左侧关键点坐标数据列表（00 WRIST ~ 20 P_TIP 的 x y z 坐标）
- 右上角 HUD 状态栏（REC fps / UI fps / GPU% / L:手势 R:手势 置信度）
- 安全区域四角括号 + 中心十字准星提示
- JSONL 格式录制导出（含时间戳/图像尺寸/手势类别/全部关键点坐标）
- 触摸交互：短触切换信息展开/收起，长按切换录制状态

## 用户明确要求

- **代码逻辑没问题**，构建配置有问题
- **不需要摄像头实时画面作为背景**，保持当前黑底+骨架叠加层即可
- 最终效果需匹配目标截图：黑底背景 + 手部骨骼可视化 + 左侧数据列 + 右上角 HUD

## Tech Stack

| 项目 | 值 |
| --- | --- |
| 语言 | Java 17 |
| 构建工具 | Gradle 8.10.2 + AGP 8.7.3 |
| 相机 API | Camera2 (android.hardware.camera2) |
| AI 引擎 | MediaPipe Tasks Vision 0.10.26 (Gesture Recognizer) |
| 目标设备 | ROKID Glasses (Android, 反向横屏 reverseLandscape) |
| minSdk | 26 (Android 8.0) |


## 构建问题根因分析

### 当前 build.gradle 配置

```
compileSdk = 36    // Android 16（过于激进）
targetSdk  = 36    // Android 16（设备可能不兼容）
AGP        = 8.7.3
Gradle     = 8.10.2
gradle.properties: android.suppressUnsupportedCompileSdk=36  // workaround 标记
```

### 问题

1. **compileSdk=36 / targetSdk=36 对应 Android 16**，这是一个非常新的版本。ROKID Glasses 设备运行的 Android 版本很可能低于此值（通常 AR 眼镜设备运行 Android 11~14，即 SDK 30~34）。`targetSdk` 声明过高会导致：

- 应用被施加高版本的行为变更限制（如分区存储、精确闹钟权限等），可能导致运行时 crash 或功能异常
- 某些 API 在低版本系统上不存在或行为不同

2. **`suppressUnsupportedCompileSdk=36`** 是一个 workaround，说明开发者已知 SDK 36 可能有兼容性问题

3. **之前构建虽然成功并安装了 APK**，但应用可能在设备上无法正常运行（crash 或功能异常）

### 修复方案

将 `targetSdk` 从 36 降低到 **34**（Android 14），同时将 `compileSdk` 降低到 **34** 以确保稳定构建环境：

- SDK 34 是目前广泛支持的稳定版本
- 与 ROKID Glasses 可能的 Android 版本（12~14）完全兼容
- 移除 `suppressUnsupportedCompileSdk=36` workaround
- 不影响现有代码逻辑（代码未使用任何 SDK 35+/36+ 独有 API）

## 数据流架构（无需修改，仅供参考确认）

```
Camera2 ImageReader (YUV_420_888, 320x240, "RokidCamera"线程)
       ↓ onImageAvailable()
       ↓ frameSink.onFrame(image, rotationDegrees, timestampMs)
       ↓ [主线程] MainActivity.onFrame() → gestureRunner.recognize(image, ...)
MediaPipe GestureRecognizer (LIVE_STREAM模式, GPU/CPU双委托)
       ↓ 异步回调 handleResult()
HandsResult.fromMediaPipe() → 解析21个关键点 + 手势分类
       ↓ LandmarkSmoother.smooth() [EMA α=0.42]
GestureOverlayView.setResult(result) → onDraw():
  ① drawSafeZone()      安全区域角标 + 准星
  ② drawContour()       手部白色轮廓线
  ③ drawBones()         白色骨架连线 (23根骨头)
  ④ drawJoints()        白色关节圆点 (单手模式)
  ⑤ drawHud()           右上角状态栏 (REC fps/UI fps/GPU%/手势)
  ⑥ drawPoseColumn()    左侧关键点坐标数据表 (21行 x y z)
  ⑦ drawRecIndicator()  录制红点指示器
```

## 目录结构

```
/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/
├── app/build.gradle                              # [MODIFY] 降低 targetSdk/compileSdk 到 34
├── gradle.properties                             # [MODIFY] 移除 suppressUnsupportedCompileSdk workaround
└── app/src/main/java/com/example/rokidgesture/
    ├── MainActivity.java                         # [无修改]
    ├── CameraController.java                     # [无修改]
    ├── GestureOverlayView.java                   # [无修改] — 已包含完整UI匹配截图
    ├── GestureRecognizerRunner.java              # [无修改]
    ├── HandResult.java                           # [无修改]
    ├── LandmarkRecorder.java                     # [无修改]
    ├── LandmarkSmoother.java                     # [无修改]
    ├── FpsMeter.java                             # [无修改]
    ├── GpuUsageMonitor.java                      # [无修改]
    └── YuvToRgbConverter.java                    # [无修改] — 暂不使用，保留即可
```

## 实现注意事项

- 仅修改 2 个文件的各 1-2 行配置
- 不改动任何 Java 源码逻辑
- 构建后执行 `./gradlew clean assembleDebug` 确保 clean build
- 安装命令：`adb install -r app/build/outputs/apk/debug/app-debug.apk`
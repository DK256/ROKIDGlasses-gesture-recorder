---
name: complete-mainactivity-and-layout
overview: 创建 MainActivity.java 和 activity_main.xml 布局文件，将已有的各个底层组件（CameraController、GestureRecognizerRunner、GestureOverlayView、LandmarkRecorder、LandmarkSmoother）组装成完整可用的手势录制应用。
todos:
  - id: create-layout-dir
    content: 创建 res/layout/ 目录
    status: completed
  - id: create-layout-xml
    content: 创建 activity_main.xml 布局文件
    status: completed
    dependencies:
      - create-layout-dir
  - id: create-main-activity
    content: 创建 MainActivity.java 主入口文件
    status: completed
    dependencies:
      - create-layout-xml
  - id: update-manifest-permissions
    content: 更新 AndroidManifest.xml 添加 WRITE_EXTERNAL_STORAGE 权限
    status: completed
---

## 产品概述

Rokid Glass 手势录制应用，基于 Android Camera2 和 MediaPipe GestureRecognizer 实现实时手势检测与录制。

## 核心功能

- 通过 Android Camera2 API 获取 Rokid 眼镜相机帧流（YUV 格式）
- MediaPipe GestureRecognizer LIVE_STREAM 模式实时识别手势
- 自定义 GestureOverlayView 绘制手部轮廓、骨骼线和关节点
- LandmarkRecorder 录制手势数据为 JSONL 格式
- 触摸屏幕切换录制状态，HUD 显示当前帧率和录制信息
- LandmarkSmoother EMA 平滑算法减少 landmark 抖动

## 技术栈

- **平台**: Android Native (minSdk 26, compileSdk 35)
- **语言**: Java 17
- **相机**: Android Camera2 API (YUV_420_888)
- **手势识别**: MediaPipe Tasks Vision 0.10.26 (GestureRecognizer LIVE_STREAM)
- **构建工具**: Android Gradle Plugin 8.7.3

## 实现方案

项目已有7个完整实现的核心组件，缺失的 `MainActivity.java` 作为胶水层，协调各组件工作：

```
数据流: Camera2 ImageReader → YuvToRgbConverter → Bitmap
       → GestureRecognizerRunner.recognizeAsync() → MediaPipe (后台线程)
       → GestureRecognizerResult → HandResult.fromMediaPipe()
       → LandmarkSmoother.smooth() → GestureOverlayView.setResult()
       → (录制时) LandmarkRecorder.write()
```

**MainActivity 职责**:

1. 实现 `CameraController.FrameSink` 和 `GestureRecognizerRunner.Listener` 接口
2. 运行时权限请求（CAMERA + WRITE_EXTERNAL_STORAGE）
3. 生命周期管理：onResume 启动相机，onPause 停止相机和录制
4. 触摸事件处理：切换录制状态，更新 HUD 显示
5. 帧率控制：通过 `GestureRecognizerRunner.canAcceptFrame()` 避免 MediaPipe 队列堆积

**关键设计决策**:

- 使用 app-specific 外部存储目录 (`getExternalFilesDir()`) 存储录制文件，Android 10+ 无需 `WRITE_EXTERNAL_STORAGE` 权限，但为兼容性仍申请
- 横屏 landscape 模式适配 Rokid Glass 显示
- FrameLayout 层叠布局：底层 TextureView（相机预览）+ 上层 GestureOverlayView（绘制层）

## 目录结构

```
app/src/main/
├── java/com/example/rokidgesture/
│   ├── MainActivity.java              # [NEW] 主入口，协调所有组件
│   ├── CameraController.java          # [EXISTING] Camera2 控制
│   ├── GestureRecognizerRunner.java   # [EXISTING] MediaPipe 识别
│   ├── GestureOverlayView.java        # [EXISTING] 绘制层
│   ├── HandResult.java                # [EXISTING] 数据模型
│   ├── LandmarkRecorder.java          # [EXISTING] 录制器
│   ├── LandmarkSmoother.java          # [EXISTING] 平滑器
│   └── YuvToRgbConverter.java         # [EXISTING] YUV转RGB
├── res/
│   └── layout/
│       └── activity_main.xml          # [NEW] 布局文件（TextureView + GestureOverlayView）
└── AndroidManifest.xml                # [EXISTING] 引用 MainActivity
```

## 实现注意事项

- **权限处理**: Android 6.0+ 需要运行时申请 CAMERA 权限，WRITE_EXTERNAL_STORAGE 仅用于 Android 9 及以下
- **生命周期安全**: onPause 时必须先停止录制再关闭相机，避免文件损坏
- **性能优化**: 通过 `canAcceptFrame()` 丢弃 MediaPipe 忙时的帧，保持预览流畅
- **错误处理**: 相机错误回调时显示 Toast，录制错误时自动停止录制
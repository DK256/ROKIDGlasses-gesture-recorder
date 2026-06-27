---
name: rebuild-and-run-rokid-app
overview: 在已完成两个问题修复的前提下，准备重新编译 Android 项目并在设备上运行/安装调试包。
todos:
  - id: build-debug-apk
    content: 在项目根目录执行 Gradle Debug 编译
    status: completed
  - id: install-apk
    content: 确认 ADB 设备连接并安装最新 APK
    status: completed
    dependencies:
      - build-debug-apk
  - id: launch-app
    content: 启动 com.example.rokidgesture 应用
    status: completed
    dependencies:
      - install-apk
  - id: verify-runtime
    content: 查看关键日志确认相机和 MediaPipe 正常运行
    status: completed
    dependencies:
      - launch-app
---

## User Requirements

用户要求在前面两个问题已经修复后，重新编译项目并运行到设备上。

## Product Overview

当前项目是 ROKID Glasses 手势识别记录 Android 应用，界面保持黑底骨架和 HUD 显示，不渲染实时摄像头画面。

## Core Features

- 保留已修复的 MediaPipe 画面方向补偿，当前补偿常量为 270 度。
- 保留已删除的右上角 `idx name x y z` 姿态详情信息。
- 重新编译 Debug APK。
- 将应用安装或重新安装到已连接设备。
- 启动应用并观察基础运行状态，确认应用可打开、相机与 MediaPipe 链路开始运行。

## Tech Stack Selection

- 项目类型：Android 原生应用。
- 构建系统：Gradle Wrapper，使用项目根目录 `/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/gradlew`。
- 主要语言：Java。
- 运行目标：已连接的 ROKID Glasses / Android 设备。
- 验证工具：ADB 安装、启动应用，并可通过 logcat 观察关键运行日志。

## Implementation Approach

本次不进行新的代码修改，只执行编译、安装和运行验证。先在项目根目录执行 Debug 构建，确认 APK 生成成功；然后通过 ADB 检查设备连接，安装最新 Debug APK；最后启动包名 `com.example.rokidgesture` 的主 Activity，并查看关键日志确认运行状态。

关键决策：

- 不做无关 refactor，避免影响已修复的 MediaPipe 方向和 UI 删除逻辑。
- 使用现有 Gradle Wrapper，避免本机 Gradle 版本差异。
- 优先执行 `assembleDebug`，不执行 `clean`，减少构建耗时；若出现缓存相关异常再考虑 clean rebuild。
- 运行验证以启动成功、无崩溃、MediaPipe/Camera 日志出现为准。

## Implementation Notes

- 当前相关修改文件包括：
- `/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/app/src/main/java/com/example/rokidgesture/CameraController.java`
- `/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/app/src/main/java/com/example/rokidgesture/GestureOverlayView.java`
- 编译命令应在项目根目录执行：`./gradlew assembleDebug`。
- APK 预期路径：`/Users/iotarray/Desktop/ROKIDGlasses-gesture-recorder/app/build/outputs/apk/debug/app-debug.apk`。
- 安装前应确认 `adb devices` 中设备为可用状态。
- 启动应用时使用包名 `com.example.rokidgesture`。
- logcat 只筛选关键 Tag，避免日志噪声，例如 `MainActivity`、`CameraController`、`GestureRunner`。

## Architecture Design

本次任务不改变应用架构，仅验证当前构建产物：

- Gradle 构建层生成 Debug APK。
- ADB 部署层安装 APK 到 Android/ROKID 设备。
- Android Runtime 启动 `MainActivity`。
- 应用运行后 Camera2 采集帧，MediaPipe Runner 推理，Overlay 绘制骨架和 HUD。

## Directory Structure

```
ROKIDGlasses-gesture-recorder/
├── app/
│   ├── build.gradle
│   │   # [AFFECTED] Android 应用构建配置。仅参与编译，不计划修改。
│   └── src/main/java/com/example/rokidgesture/
│       ├── CameraController.java
│       │   # [AFFECTED] 已包含 MediaPipe 竖置屏幕方向补偿 270 度。本次只验证编译和运行效果。
│       ├── GestureOverlayView.java
│       │   # [AFFECTED] 已删除右上角 idx/name/x/y/z 显示逻辑。本次只验证 UI 运行效果。
│       ├── GestureRecognizerRunner.java
│       │   # [AFFECTED] MediaPipe 推理链路相关文件。本次只通过运行日志确认链路启动。
│       └── MainActivity.java
│           # [AFFECTED] 应用入口和相机/识别器启动链路。本次只启动验证。
├── gradlew
│   # [AFFECTED] 使用项目自带 Gradle Wrapper 执行构建。
└── gradle.properties
    # [AFFECTED] 构建属性文件。仅参与编译，不计划修改。
```
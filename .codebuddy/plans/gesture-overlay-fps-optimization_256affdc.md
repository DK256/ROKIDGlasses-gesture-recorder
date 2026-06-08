---
name: gesture-overlay-fps-optimization
overview: 从渲染质量降级、绘制简化、硬件加速、相机分辨率降低、帧率节流五个角度优化手势覆盖层帧率。
todos:
  - id: optimize-gesture-overlay
    content: 优化 GestureOverlayView 绘制质量：关闭所有 Paint 抗锯齿、砍掉骨骼/关节/轮廓的多层绘制（只保留主体单层）、移除对焦引导线 drawFocusGuide、轮廓 quadTo 改 lineTo、开启 LAYER_TYPE_HARDWARE
    status: completed
  - id: lower-camera-resolution
    content: 降低 CameraController 目标采集分辨率（320×240→240×180），减少图像管线计算量
    status: completed
  - id: reuse-bitmap-buffer
    content: 优化 YuvToRgbConverter：int[] 缓冲区改为成员变量复用、无旋转场景复用 Bitmap 避免反复分配
    status: completed
  - id: throttle-overlay-redraw
    content: 在 MainActivity 增加跳帧计数器，每 2 帧刷新一次 GestureOverlayView
    status: completed
  - id: pool-landmark-objects
    content: 优化 LandmarkSmoother：预分配 ArrayList 容量、减少 Point3 对象创建
    status: completed
  - id: build-and-install
    content: 编译安装到眼镜设备验证帧率改善效果
    status: completed
    dependencies:
      - optimize-gesture-overlay
      - lower-camera-resolution
      - reuse-bitmap-buffer
      - throttle-overlay-redraw
      - pool-landmark-objects
---

## 产品概述

对现有 Android 手势录制应用的手势覆盖层渲染进行帧率优化，在不改变功能逻辑的前提下，从绘制质量降级、相机管线精简、帧率控制三个维度提升整体流畅度。

## 核心功能

- **绘制质量降级**：关闭所有 Paint 的抗锯齿、砍掉骨骼/关节/轮廓的多层绘制（只保留单层主体）、移除无用的对焦引导线、简化轮廓路径曲线
- **相机管线优化**：降低目标采集分辨率、复用 Bitmap 避免每帧分配新内存
- **帧率节流**：overlay 每 2 帧刷新一次、识别器跳帧处理
- **内存分配优化**：YuvToRgbConverter 复用 int[] 缓冲区、LandmarkSmoother 减少对象创建

## 技术栈

- 语言：Java
- 平台：Android (Camera2 API + MediaPipe Gesture Recognizer)
- 现有框架：无第三方 UI 框架，纯 Canvas 自定义 View 绘制

## 实现方案

### 总体策略

采用"降低画质优先、减少绘制调用、节流刷新频率"三层递进策略。先砍掉最耗 GPU 的抗锯齿和多层叠加效果（预期减少 60%+ 绘制调用），再降低相机输入分辨率减轻整个管线压力，最后通过跳帧机制进一步降低 GPU 负载。

### 优化点分解

#### 1. GestureOverlayView 绘制降级（最大收益）

- **关闭 ANTI_ALIAS_FLAG**：9 个 Paint 全部去掉该 flag，抗锯齿是 GPU 最大开销来源
- **骨骼单层化**：去掉 boneShadowPaint（阴影层）和 boneHighlightPaint（高光层），只保留 bonePaint（主体层），每帧减少 46 次 drawLine
- **关节单层化**：去掉 jointHighlightPaint，只保留 jointPaint，每帧减少 21 次 drawCircle
- **轮廓单层化**：去掉 contourGlowPaint 发光层，只保留 contourPaint 描边
- **移除对焦引导线**：去掉 drawFocusGuide 方法，节省 8 次 drawLine 调用
- **轮廓路径简化**：quadTo 改为 lineTo，减少贝塞尔曲线计算
- **开启硬件层缓存**：在构造中 setLayerType(LAYER_TYPE_HARDWARE, null)，让 View 渲染到硬件纹理，减少每帧重绘开销

#### 2. CameraController 分辨率降低

- 目标分辨率从 320×240 降至 240×180，减少 YUV→RGB 转换的计算量和 Bitmap 内存占用约 43%

#### 3. YuvToRgbConverter 内存复用

- int[] argb 缓冲区改为成员变量，仅在分辨率变化时重新分配
- 无旋转时直接复用 Bitmap（通过 setPixels 更新内容），避免每次 createBitmap

#### 4. MainActivity 帧率节流

- 增加 frameSkipCounter，每 2 帧调用一次 gestureOverlay.setResult()
- 在 GestureRecognizerRunner.canAcceptFrame() 中增加帧间隔控制

#### 5. LandmarkSmoother 对象池化

- 预分配 ArrayList 容量，复用 Point3 对象数组，减少 GC 压力

### 性能预期

| 指标 | 优化前 | 优化后 |
| --- | --- | --- |
| 每帧 draw 调用 | ~125 次 | ~30 次 |
| ANTI_ALIAS | 全部开启 | 全部关闭 |
| 骨骼绘制层数 | 3 层 | 1 层 |
| 关节绘制层数 | 2 层 | 1 层 |
| 相机分辨率 | 320×240 | 240×180 |
| Overlay 刷新率 | 每帧 | 每 2 帧 |
| Bitmap 分配 | 每帧 new | 复用 |


### 实现注意事项

- **向后兼容**：所有修改仅影响渲染质量和性能，不改变手势识别逻辑和录制功能
- **降级安全**：若优化后线条过细，可适当调大单层线宽（如骨骼从 7f 增加到 9f）
- **内存泄漏防范**：YuvToRgbConverter 复用的 Bitmap 和 int[] 在 converter 重建时正确释放
- **线程安全**：跳帧计数器使用 volatile 或 AtomicInteger
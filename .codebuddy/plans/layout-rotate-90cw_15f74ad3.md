---
name: layout-rotate-90cw
overview: 将整个页面内容顺时针旋转90度，包括Canvas绑制变换和触摸坐标反向映射，确保无拉伸/裁剪/黑边
todos:
  - id: rotate-canvas-draw
    content: 在 GestureOverlayView.onDraw() 中添加 canvas.save()/translate(getHeight(),0)/rotate(90)/restore 包装，实现全局顺时针旋转90度
    status: completed
  - id: fix-landscape-check
    content: 修改 isDualHandLandscape() 的宽高比较条件，适配旋转后的逻辑坐标系
    status: completed
  - id: fix-touch-coords
    content: 在 MainActivity 触摸监听器中添加坐标逆变换 (tx,ty)→(vh-ty, tx)
    status: completed
  - id: build-install-verify
    content: 编译安装到眼镜设备验证旋转效果和交互正确性
    status: completed
    dependencies:
      - rotate-canvas-draw
      - fix-landscape-check
      - fix-touch-coords
---

## Product Overview

将手势录制应用的全部显示内容顺时针旋转 90 度，使画面适配 Rokid AR 眼镜的横屏显示方向。

## Core Features

- **Canvas 旋转绘制**：在 GestureOverlayView.onDraw() 中通过 Canvas 变换矩阵（save → translate → rotate 90° → restore）实现全局顺时针旋转 90°，所有现有绘制代码（手部骨架、HUD、姿态面板、安全区域、录制指示器）无需逐一修改
- **坐标映射正确性**：旋转后逻辑坐标系与物理屏幕坐标系的映射关系为 `逻辑(x,y) → 屏幕(y, viewH-x)`；sx()/sy() 等归一化坐标映射方法保持不变（它们操作的是逻辑坐标系），Canvas 变换自动完成视觉旋转
- **触摸交互适配**：MainActivity 中的 OnTouchListener 需添加逆变换：`屏幕(tx,ty) → 逻辑(vh-ty, tx)`，确保点击等交互事件命中正确的逻辑区域
- **布局判断修正**：isDualHandLandscape() 方法中宽高比较需翻转，因为旋转后逻辑宽度等于物理高度
- **无拉伸/裁剪/黑边**：通过 Canvas 旋转变换而非缩放实现，内容完全填充可视区域

## Tech Stack

- Java (Android SDK)，目标 API 为 Android AR 眼镜设备
- 自定义 View + Canvas 2D 绘制体系
- Camera2 API（仅用于后台采帧，无预览 Surface）

## Tech Architecture

### 核心变换原理

```
物理屏幕: W × H（例如眼镜横屏 1280×360）
逻辑视口: H × W（旋转后的内容坐标系）

Canvas 变换序列:
  canvas.save()
  canvas.translate(H, 0)     // 将原点移到右侧边缘
  canvas.rotate(90)           // 顺时针旋转90度
  // 此时的绘制坐标系：
  //   逻辑(0,0)     → 屏幕位置(0, 0)
  //   逻辑(x, 0)   → 屏幕位置(0, x)    [原x轴→屏幕y轴正方向]
  //   逻辑(0, y)   → 屏幕位置(y, H)    [原y轴→屏幕x轴负方向]
  //   逻辑(x, y)   → 屏幕位置(y, H-x)
  
  ... 所有原有 drawXxx() 调用不变 ...
  
  canvas.restore()
```

### 触摸逆变换

```
用户触摸点: (tx, ty) 在物理屏幕上
对应逻辑点: (lx, ly) = (H - ty, tx)

即：屏幕右上区域 → 逻辑左上区域
```

## Implementation Details

### 关键修改点

#### 1. GestureOverlayView.onDraw() — 添加 Canvas 旋转包装

在 `super.onDraw(canvas)` 之后、所有自定义绘制之前插入：

```java
canvas.save();
canvas.translate(getHeight(), 0);
canvas.rotate(90f);
// ... 原有所有绘制调用 ...
canvas.restore();
```

**性能影响**：Canvas save/restore + translate/rotate 是 GPU 硬件加速的矩阵操作，每帧开销 < 0.1ms，可忽略不计。所有子方法（drawContour/drawBones/drawJoints/drawHud 等）**零修改**——它们继续在逻辑坐标系中绘制。

#### 2. isDualHandLandscape() — 宽高语义翻转

```java
// 原代码: getWidth() >= getHeight()
// 修改后: getHeight() >= getWidth()  （逻辑宽度 = 物理高度）
```

#### 3. MainActivity.setOnTouchListener — 触摸逆变换

当前 toggleRecording 仅依赖 ACTION_DOWN 事件类型（不依赖精确坐标），因此功能上可正常运行。但为保证正确性和后续扩展性，添加坐标逆变换：

```java
gestureOverlay.setOnTouchListener((v, event) -> {
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
        int vw = v.getWidth(), vh = v.getHeight();
        float lx = vh - event.getY();  // 逆变换 x
        float ly = event.getX();       // 逆变换 y
        toggleRecording();
        return true;
    }
    return false;
});
```

### 不需要修改的部分

- **CameraController**：无 TextureView/SurfaceView 预览，纯后台 ImageReader 采帧，与显示无关
- **YuvToRgbConverter**：Bitmap 生产者，不参与 UI 渲染
- **GestureRecognizerRunner / MediaPipe**：识别引擎，输入输出均为数据非渲染
- **sx()/sy() 坐标映射**：返回逻辑像素值，由 Canvas 统一变换处理
- **所有 drawXxx() 私有方法**：零改动，它们操作的是逻辑坐标系
- **activity_main.xml 布局**：FrameLayout 全屏填充，无需调整

## Architecture Design

```
┌─────────────── 物理屏幕 (W × H) ───────────────┐
│                                               │
│  ┌──────────────────────────┐                 │
│  │                          │  ↑              │
│  │   逻辑视口 (H × W)       │  │              │
│  │   (顺时针旋转90°显示)     │  H              │
│  │                          │  ↓              │
│  │  HUD ← 骨架 ← 姿态面板   │                 │
│  │  安全区边框               │                 │
│  └──────────────────────────┘                 │
│  ←──────────────── W ──────────→             │
└───────────────────────────────────────────────┘
```
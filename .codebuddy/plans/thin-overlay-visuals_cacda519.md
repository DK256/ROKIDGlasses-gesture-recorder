---
name: thin-overlay-visuals
overview: 将手势叠加层的轮廓线、骨骼线条和关节圆点变细，提高可视清晰度。修改 GestureOverlayView.java 中的 strokeWidth 和 radius 参数。
todos:
  - id: thin-overlay-params
    content: 修改 GestureOverlayView.java 中的轮廓线、骨骼线、关节圆点绘制参数，整体缩小约 50%
    status: completed
  - id: rebuild-install
    content: 重新编译 APK 并安装到 Rokid Glass 验证效果
    status: completed
    dependencies:
      - thin-overlay-params
---

## 用户需求

缩小手势叠加层（GestureOverlayView）中所有绘制元素的粗细，包括：轮廓线、骨骼线、关节圆点等。整体缩至约原先的 50%，使手势轮廓和骨骼细节更清晰可辨。

## 核心改动

- 轮廓线主线条从 5f 缩至约 2.5f，发光层从 18f 缩至约 9f
- 骨骼线宽度公式从 24f 缩至约 12f，阴影和高光同步缩小
- 关节圆点半径从 18f/13f 缩至约 9f/7f，范围从 8f-19f 缩至约 4f-10f
- 引导框角标线（2f）和 HUD 文字（34f/30f）保持不变

## 技术方案

### 实现方式

直接修改 `GestureOverlayView.java` 中的硬编码绘制参数，将轮廓线、骨骼线、关节半径按约 50% 比例缩小。改动集中在 `init()` 方法和 `drawBones()`、`drawJoints()` 方法中。

### 具体参数对照

| 参数 | 修改前 | 修改后 |
| --- | --- | --- |
| contourPaint.strokeWidth | 5f | 2.5f |
| contourGlowPaint.strokeWidth | 18f | 9f |
| 骨骼宽度公式 | 24f - depth*18f | 12f - depth*9f |
| 骨骼阴影增量 | +5f | +3f |
| 骨骼高光最小值 | Math.max(4f, ...) | Math.max(2f, ...) |
| 骨骼高光偏移 | -2f | -1f |
| 手腕半径 | 18f | 9f |
| 其他关节半径 | 13f - clamp(z)*10f | 7f - clamp(z)*5f |
| 关节半径范围 | clamp(8f, 19f) | clamp(4f, 10f) |
| 引导框线宽 | 2f | 不变 |
| HUD 文字 | 34f/30f | 不变 |


### 影响面

仅修改 `GestureOverlayView.java` 单一文件，无其他依赖影响，无新增文件。
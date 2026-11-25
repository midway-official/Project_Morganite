# Morganite 图形 ABI 1

**简体中文** · [English](graphics.md)

每帧依次提交顶点、索引图元设置和逐像素作业。320×240 输出含小端 RGBA8、FP32 深度和 32 位胜出图元序号（0 为背景）。清屏色为不透明黑，深度初值为 1。

顶点由八个 FP32 字组成，依次是 XYZW 和 RGBA。顶点程序按行主序应用模型、视图和投影矩阵。场景包含 16 字节头、192 字节矩阵、每顶点八个字及每三角形三个零基 uint32 索引。头部四字分别为 magic `0x3153474d`、顶点数、三角形数、着色标志。标志位 0 选择位移顶点程序，位 1 选择交换红蓝的片元程序。Linux 示例每场景支持 1–32 个顶点、1–32 个三角形。所有指针采用驱动提供的设备地址。

输入要求 w 有限且为正，x、y ∈ [−w,w]，z ∈ [0,w]。屏幕 y 朝下，投影位置在 1/16 像素网格上就近取偶；像素中心为 (16x+8,16y+8)。设置阶段统一朝向。正有向面积下，dy<0 或 dy=0 且 dx>0 的边包含边界样本。退化图元覆盖零像素。一个线程负责一个像素，按提交顺序遍历；严格较小的 z/w 才更新，等深保留第一个图元。对颜色分别插值 color/w 和 1/w 再相除。片元程序从 r20..r23 接收线性 RGBA，可用 r24..r30 作临时寄存器，结果仍写回 r20..r23。库按就近取偶转 UNORM8。倒数使用四轮 Newton 迭代和融合残差修正。

CPU 参考使用双精度、整数边函数及独立的逐三角形循环。所有像素须匹配覆盖和胜者；RGBA 每通道容差 1，深度绝对误差上限 2e-6，共享边、等深和退化用例另做精确检查。归档含 GPU 与 CPU 原始平面。[CPU 参考](../graphics/reference.py)

```bash
source scripts/toolchain.sh
bash scripts/test_gpu.sh
python3 graphics/test_render.py rtl build/render-320
bash linux/build_userspace.sh
python3 linux/build_kernel.py
# 在 LA32R shell 中执行
/bin/mgpu render /gpu/cube_a.scene /tmp/frame
```

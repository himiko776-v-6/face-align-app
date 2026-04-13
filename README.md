# Face Align - 人脸对齐 App

使用 Qwen3.5 视觉模型进行人脸位置检测和引导。

## 功能

- 摄像头实时预览
- 目标框显示（用户需要将人脸放入框内）
- Qwen 视觉模型分析人脸位置
- 中文引导提示（"请向左移动"、"请向上移动"等）
- 检测成功后给出确认

## 构建

项目使用 GitHub Actions 自动编译 APK。

推送到 main 分支后，Actions 会自动构建并发布 APK。

## 配置

首次使用需要输入通义千问 API Key（从阿里云百炼平台获取）。

## 技术栈

- Kotlin + Jetpack Compose
- CameraX
- OkHttp
- Qwen-VL-Plus API
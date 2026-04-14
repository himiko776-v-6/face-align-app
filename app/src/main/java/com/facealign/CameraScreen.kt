package com.facealign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.guava.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "FaceAlign"

// 人脸对齐状态
enum class AlignState {
    NO_FACE,        // 未检测到人脸
    TOO_FAR,        // 太远
    TOO_CLOSE,      // 太近
    MOVE_LEFT,      // 向左移
    MOVE_RIGHT,     // 向右移
    MOVE_UP,        // 向上移
    MOVE_DOWN,      // 向下移
    ALIGNED         // 对齐成功
}

@Composable
fun CameraScreen(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    
    // 状态
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var alignState by remember { mutableStateOf(AlignState.NO_FACE) }
    var capturedPhoto by remember { mutableStateOf<String?>(null) }
    var faceRect by remember { mutableStateOf<Rect?>(null) }
    
    // 主线程 Handler，用于更新 UI 状态
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    
    // 相机组件
    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    
    // ML Kit 人脸检测器
    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题
        Text(
            "人脸对齐",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        
        if (!hasPermission) {
            Spacer(modifier = Modifier.height(32.dp))
            Text("需要相机权限", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("授予权限")
            }
        } else if (cameraError != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Text("错误: $cameraError", color = MaterialTheme.colorScheme.error)
        } else {
            // 相机预览区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
                
                // 引导框叠加层
                FaceGuideOverlay(
                    alignState = alignState,
                    faceRect = faceRect
                )
            }
            
            // 引导文字
            val guideText = when (alignState) {
                AlignState.NO_FACE -> "未检测到人脸"
                AlignState.TOO_FAR -> "请靠近一些"
                AlignState.TOO_CLOSE -> "请离远一些"
                AlignState.MOVE_LEFT -> "请向左移动"
                AlignState.MOVE_RIGHT -> "请向右移动"
                AlignState.MOVE_UP -> "请向上移动"
                AlignState.MOVE_DOWN -> "请向下移动"
                AlignState.ALIGNED -> "✓ 对齐成功！正在拍照..."
            }
            
            Text(
                guideText,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (alignState == AlignState.ALIGNED) Color(0xFF4CAF50) else Color.White
            )
        }
    }
    
    // 初始化相机并启动分析
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            Log.d(TAG, "Starting camera init")
            
            try {
                val provider = ProcessCameraProvider.getInstance(context).await()
                Log.d(TAG, "Provider obtained")
                
                // 创建预览
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                
                // 创建图像捕获
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // 创建图像分析
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processFrame(imageProxy, faceDetector, mainHandler) { rect, state ->
                                faceRect = rect
                                alignState = state
                                
                                // 对齐成功，自动拍照
                                if (state == AlignState.ALIGNED && imageCapture != null && capturedPhoto == null) {
                                    capturePhoto(imageCapture!!, context, cameraExecutor) { path ->
                                        capturedPhoto = path
                                        Log.d(TAG, "Photo saved: $path")
                                    }
                                }
                            }
                        }
                    }
                
                withContext(Dispatchers.Main) {
                    provider.unbindAll()
                    
                    try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageCapture,
                            imageAnalysis
                        )
                        Log.d(TAG, "Front camera bound with analysis")
                    } catch (e: Exception) {
                        Log.w(TAG, "Front failed, trying back: ${e.message}")
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                            imageAnalysis
                        )
                        Log.d(TAG, "Back camera bound with analysis")
                    }
                    
                    cameraReady = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                cameraError = e.message
            }
        }
    }
    
    // 清理
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            faceDetector.close()
        }
    }
}

// 处理每一帧（带主线程回调）
private fun processFrame(
    imageProxy: ImageProxy,
    faceDetector: com.google.mlkit.vision.face.FaceDetector,
    mainHandler: Handler,
    onResult: (Rect?, AlignState) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    
    val rotation = imageProxy.imageInfo.rotationDegrees
    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
    
    // 计算旋转后的实际图像尺寸
    val actualWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
    val actualHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
    
    faceDetector.process(inputImage)
        .addOnSuccessListener { faces ->
            if (faces.isEmpty()) {
                mainHandler.post {
                    onResult(null, AlignState.NO_FACE)
                }
            } else {
                val face = faces[0]
                val faceBounds = face.boundingBox
                
                // 计算对齐状态（使用旋转后的尺寸）
                val state = calculateAlignment(faceBounds, actualWidth, actualHeight, rotation)
                
                // 传递原始 boundingBox，坐标转换在 FaceGuideOverlay 中完成
                mainHandler.post {
                    onResult(faceBounds, state)
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e(TAG, "Face detection failed", e)
            mainHandler.post {
                onResult(null, AlignState.NO_FACE)
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

// 转换坐标到显示坐标系
private fun transformToDisplay(
    rect: Rect,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int
): Rect {
    // 对于前置摄像头 270° 旋转（竖屏拍摄）：
    // 参考 GraphicOverlay.kt 的 calculateRect 方法：
    // 1. 旋转映射：left = boundingBox.right（反转），right = boundingBox.left
    // 2. 前置摄像头镜像：水平翻转 X 坐标
    //
    // 简化后的公式（270度 + 前置镜像）：
    // displayX = imageHeight - imageY（反转 Y）
    // displayY = imageX
    //
    // 即：left = imageHeight - bottom, right = imageHeight - top, top = left, bottom = right
    return when (rotation) {
        270 -> Rect(
            imageHeight - rect.bottom,  // left = 480 - bottom（Y 反转 + 镜像）
            rect.left,                  // top = left（X → Y）
            imageHeight - rect.top,     // right = 480 - top（Y 反转 + 镜像）
            rect.right                  // bottom = right（X → Y）
        )
        90 -> Rect(
            rect.top,
            imageWidth - rect.right,
            rect.bottom,
            imageWidth - rect.left
        )
        180 -> Rect(
            imageWidth - rect.right,
            imageHeight - rect.bottom,
            imageWidth - rect.left,
            imageHeight - rect.top
        )
        else -> rect
    }
}

// 计算人脸是否对齐
private fun calculateAlignment(
    faceBounds: Rect,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int
): AlignState {
    // 目标框区域（画面中央）
    val targetLeft = imageWidth * 0.2f
    val targetRight = imageWidth * 0.8f
    val targetTop = imageHeight * 0.15f
    val targetBottom = imageHeight * 0.85f
    
    // 转换人脸坐标（考虑旋转）
    val faceLeft: Float
    val faceRight: Float
    val faceTop: Float
    val faceBottom: Float
    
    if (rotation == 270) {
        // 前置摄像头，需要水平镜像
        faceLeft = imageWidth - faceBounds.right.toFloat()
        faceRight = imageWidth - faceBounds.left.toFloat()
        faceTop = faceBounds.top.toFloat()
        faceBottom = faceBounds.bottom.toFloat()
    } else if (rotation == 90) {
        faceLeft = faceBounds.top.toFloat()
        faceRight = faceBounds.bottom.toFloat()
        faceTop = imageHeight - faceBounds.right.toFloat()
        faceBottom = imageHeight - faceBounds.left.toFloat()
    } else {
        faceLeft = faceBounds.left.toFloat()
        faceRight = faceBounds.right.toFloat()
        faceTop = faceBounds.top.toFloat()
        faceBottom = faceBounds.bottom.toFloat()
    }
    
    val faceWidth = faceRight - faceLeft
    val faceHeight = faceBottom - faceTop
    val faceCenterX = (faceLeft + faceRight) / 2
    val faceCenterY = (faceTop + faceBottom) / 2
    
    val targetCenterX = (targetLeft + targetRight) / 2
    val targetCenterY = (targetTop + targetBottom) / 2
    val targetWidth = targetRight - targetLeft
    val targetHeight = targetBottom - targetTop
    
    // 容差阈值
    val positionTolerance = targetWidth * 0.15f
    val sizeToleranceLow = 0.6f
    val sizeToleranceHigh = 1.4f
    
    // 检查人脸大小
    val widthRatio = faceWidth / targetWidth
    val heightRatio = faceHeight / targetHeight
    val avgRatio = (widthRatio + heightRatio) / 2
    
    if (avgRatio < sizeToleranceLow) {
        return AlignState.TOO_FAR
    }
    if (avgRatio > sizeToleranceHigh) {
        return AlignState.TOO_CLOSE
    }
    
    // 检查位置偏移
    val offsetX = faceCenterX - targetCenterX
    val offsetY = faceCenterY - targetCenterY
    
    // 优先检查左右（前置摄像头方向相反）
    if (rotation == 270) {
        if (offsetX > positionTolerance) {
            return AlignState.MOVE_LEFT
        }
        if (offsetX < -positionTolerance) {
            return AlignState.MOVE_RIGHT
        }
    } else {
        if (offsetX > positionTolerance) {
            return AlignState.MOVE_RIGHT
        }
        if (offsetX < -positionTolerance) {
            return AlignState.MOVE_LEFT
        }
    }
    
    // 检查上下
    if (offsetY > positionTolerance) {
        return AlignState.MOVE_DOWN
    }
    if (offsetY < -positionTolerance) {
        return AlignState.MOVE_UP
    }
    
    return AlignState.ALIGNED
}

// 拍照
private fun capturePhoto(
    imageCapture: ImageCapture,
    context: android.content.Context,
    executor: ExecutorService,
    onSaved: (String) -> Unit
) {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val fileName = "FaceAlign_$timeStamp.jpg"
    val photoFile = File(context.filesDir, fileName)
    
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    
    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d(TAG, "Photo saved: ${photoFile.absolutePath}")
                onSaved(photoFile.absolutePath)
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed", exc)
            }
        }
    )
}

@Composable
fun FaceGuideOverlay(
    alignState: AlignState,
    faceRect: Rect?
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        
        // 目标框：屏幕中央，宽高比为 3:4
        val boxWidth = canvasWidth * 0.6f
        val boxHeight = boxWidth * 1.33f
        val left = (canvasWidth - boxWidth) / 2
        val top = (canvasHeight - boxHeight) / 2
        
        // 目标框颜色根据状态变化
        val boxColor = when (alignState) {
            AlignState.ALIGNED -> Color(0xFF4CAF50)
            else -> Color.Yellow
        }
        
        // 绘制目标框
        drawRect(
            color = boxColor,
            topLeft = Offset(left, top),
            size = ComposeSize(boxWidth, boxHeight),
            style = Stroke(width = 4f)
        )
        
        // 绘制四个角标记
        val cornerLength = 40f
        val strokeWidth = 4f
        val cornerColor = Color.White
        
        // 左上角
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth)
        drawLine(cornerColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth)
        
        // 右上角
        drawLine(cornerColor, Offset(left + boxWidth, top), Offset(left + boxWidth - cornerLength, top), strokeWidth)
        drawLine(cornerColor, Offset(left + boxWidth, top), Offset(left + boxWidth, top + cornerLength), strokeWidth)
        
        // 左下角
        drawLine(cornerColor, Offset(left, top + boxHeight), Offset(left + cornerLength, top + boxHeight), strokeWidth)
        drawLine(cornerColor, Offset(left, top + boxHeight), Offset(left, top + boxHeight - cornerLength), strokeWidth)
        
        // 右下角
        drawLine(cornerColor, Offset(left + boxWidth, top + boxHeight), Offset(left + boxWidth - cornerLength, top + boxHeight), strokeWidth)
        drawLine(cornerColor, Offset(left + boxWidth, top + boxHeight), Offset(left + boxWidth, top + boxHeight - cornerLength), strokeWidth)
        
        // 绘制人脸检测框（如果有）
        if (faceRect != null && alignState != AlignState.NO_FACE) {
            // 270° 旋转 + 前置摄像头镜像的正确转换
            // 参考 w3tutorials.net 公式：(x,y) → (imageHeight-y-height, x)
            // 前置镜像：mirrorX = canvasWidth - X
            
            // faceRect 是原始 boundingBox，来自 640x480 横向图像
            // rect.left/right = 原始 X 坐标 (0-640)
            // rect.top/bottom = 原始 Y 坐标 (0-480)
            
            // 270° 旋转后：原始 X → 显示 Y，原始 Y → 显示 X
            // 前置摄像头镜像：显示 X 需要翻转
            
            val scaleX = canvasWidth / 480f   // 旋转后宽度来自原始高度
            val scaleY = canvasHeight / 640f  // 旋转后高度来自原始宽度
            
            // 270° + 镜像：displayX = 480 - imageY, displayY = imageX
            val displayLeft = (480 - faceRect!!.bottom) * scaleX   // 原始 Y 最大 → 显示 X 最小（镜像后）
            val displayRight = (480 - faceRect!!.top) * scaleX     // 原始 Y 最小 → 显示 X 最大（镜像后）
            val displayTop = faceRect!!.left * scaleY              // 原始 X 最小 → 显示 Y 最小
            val displayBottom = faceRect!!.right * scaleY          // 原始 X 最大 → 显示 Y 最大
            
            val faceWidth = displayRight - displayLeft
            val faceHeight = displayBottom - displayTop
            
            drawRect(
                color = Color.Cyan,
                topLeft = Offset(displayLeft, displayTop),
                size = ComposeSize(faceWidth, faceHeight),
                style = Stroke(width = 2f)
            )
        }
    }
}
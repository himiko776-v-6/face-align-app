package com.facealign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 简单的人脸检测器
 * 使用 Qwen 视觉模型进行人脸位置判断
 */
class FaceDetector(private val context: Context, private val qwenService: QwenService) {
    
    // 目标框的位置（相对于预览画面）
    var targetRect = Rect(100, 200, 400, 500)  // 左上右下
    
    // 检测结果
    var lastResult: QwenService.FacePosition? = null
        private set
    
    // 帧计数器（避免每帧都调用 API）
    private var frameCount = 0
    private val analyzeInterval = 15  // 每 15 帧分析一次
    
    /**
     * 处理摄像头帧
     */
    suspend fun processFrame(imageProxy: ImageProxy): QwenService.FacePosition? {
        frameCount++
        
        // 只在指定间隔分析
        if (frameCount % analyzeInterval != 0) {
            return lastResult
        }
        
        val result = qwenService.analyzeFacePosition(imageProxy)
        
        result.onSuccess { position ->
            lastResult = position
        }.onFailure { error ->
            // 检测失败，保持上次结果
            android.util.Log.e("FaceDetector", "Detection failed", error)
        }
        
        return lastResult
    }
    
    /**
     * 在 Bitmap 上绘制目标框和检测状态
     */
    fun drawOverlay(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        // 绘制目标框
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        
        // 根据检测结果选择颜色
        paint.color = when {
            lastResult == null -> Color.YELLOW
            lastResult?.inFrame == true -> Color.GREEN
            else -> Color.RED
        }
        
        canvas.drawRect(
            targetRect.left.toFloat(),
            targetRect.top.toFloat(),
            targetRect.right.toFloat(),
            targetRect.bottom.toFloat(),
            paint
        )
        
        return result
    }
    
    /**
     * 根据检测结果生成引导提示
     */
    fun getGuidanceText(): String {
        val result = lastResult ?: return "正在初始化..."
        
        if (result.inFrame) {
            return "✓ 位置合适，请保持"
        }
        
        return result.guidance.ifEmpty { 
            when {
                result.horizontalPosition == "left" -> "请向右移动"
                result.horizontalPosition == "right" -> "请向左移动"
                result.verticalPosition == "top" -> "请向下移动"
                result.verticalPosition == "bottom" -> "请向上移动"
                else -> "请调整位置"
            }
        }
    }
}
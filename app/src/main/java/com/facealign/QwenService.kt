package com.facealign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Qwen3.5 视觉模型服务
 * 用于检测人脸位置并给出引导建议
 */
class QwenService(private val apiKey: String) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // 通义千问 API 端点
    private val apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    
    data class FacePosition(
        val inFrame: Boolean,
        val horizontalPosition: String,  // "center", "left", "right"
        val verticalPosition: String,    // "center", "top", "bottom"
        val distance: String,            // "good", "too_close", "too_far"
        val guidance: String,            // 给用户的引导语
        val confidence: Float
    )
    
    /**
     * 分析图片中的人脸位置
     */
    suspend fun analyzeFacePosition(imageProxy: ImageProxy): Result<FacePosition> = withContext(Dispatchers.IO) {
        try {
            // 转换为 Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)
            val base64Image = bitmapToBase64(bitmap)
            
            // 构建 prompt
            val prompt = """
分析这张图片中的人脸位置。

图片中央有一个绿色矩形框（目标区域）。请判断：

1. 人脸是否在绿色框内？
2. 如果不在，人脸在哪个方向？（左/右/上/下）
3. 人脸距离是否合适？（太近/太远/合适）

请以 JSON 格式回复，不要其他内容：
{
  "in_frame": true/false,
  "horizontal": "center" | "left" | "right",
  "vertical": "center" | "top" | "bottom", 
  "distance": "good" | "too_close" | "too_far",
  "guidance": "给用户的中文引导语，简短清晰",
  "confidence": 0.0-1.0
}

如果没有人脸，返回：
{
  "in_frame": false,
  "horizontal": "center",
  "vertical": "center",
  "distance": "good",
  "guidance": "未检测到人脸，请面对摄像头",
  "confidence": 0.0
}
""".trimIndent()

            // 调用 API
            val response = callQwenAPI(base64Image, prompt)
            
            // 解析响应
            parseFacePosition(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 缩小图片以减少传输量
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 480, 640, true)
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun callQwenAPI(base64Image: String, prompt: String): String {
        val requestBody = """
        {
            "model": "qwen-vl-plus",
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": "$prompt"
                        },
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": "data:image/jpeg;base64,$base64Image"
                            }
                        }
                    ]
                }
            ],
            "max_tokens": 500
        }
        """.trimIndent()
        
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("API call failed: ${response.code} ${response.message}")
        }
        
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        
        return json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
    }
    
    private fun parseFacePosition(response: String): Result<FacePosition> {
        return try {
            // 尝试提取 JSON
            val jsonStr = response.substringAfter("{").substringBeforeLast("}") + "}"
            val json = gson.fromJson("{${jsonStr}", JsonObject::class.java)
            
            Result.success(FacePosition(
                inFrame = json.get("in_frame")?.asBoolean ?: false,
                horizontalPosition = json.get("horizontal")?.asString ?: "center",
                verticalPosition = json.get("vertical")?.asString ?: "center",
                distance = json.get("distance")?.asString ?: "good",
                guidance = json.get("guidance")?.asString ?: "",
                confidence = json.get("confidence")?.asFloat ?: 0.0f
            ))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse response: $response"))
        }
    }
}
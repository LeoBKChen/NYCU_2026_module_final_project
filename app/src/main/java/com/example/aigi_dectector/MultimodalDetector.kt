package com.example.aigi_dectector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.MessageCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 多模態 AI 偵測器。
 * 使用 LiteRT-LM 引擎，支援 .litertlm 格式模型。
 */
class MultimodalDetector(private val context: Context) {

    private var engine: Engine? = null
    private var activeConversation: com.google.ai.edge.litertlm.Conversation? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    // 🌟 1. 初始化 CLIP 階段偵測器
    private val clipRunner = ClipLiteRtRunner(context)
    
    var initializationError by mutableStateOf<String?>(null)
        private set
    var isInitializing by mutableStateOf(false)
        private set
    var modelPath by mutableStateOf<String?>(null)
        private set
    var availableModels by mutableStateOf<List<File>>(emptyList())
        private set

    init {
        refreshAvailableModels()
        // 預設載入第一個找到的模型
        availableModels.firstOrNull()?.let {
            initInferenceAsync(it.absolutePath)
        }
    }

    /**
     * 掃描模型目錄並更新可用模型列表
     */
    fun refreshAvailableModels() {
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        availableModels = modelsDir.listFiles { _, name -> name.endsWith(".litertlm") }?.toList() ?: emptyList()
    }

    /**
     * 切換模型
     */
    fun switchModel(newModelPath: String) {
        if (this.modelPath == newModelPath && engine != null) return
        
        scope.launch {
            close() // 先關閉舊引擎釋放記憶體
            initInferenceAsync(newModelPath)
        }
    }

    private fun initInferenceAsync(targetPath: String? = null) {
        isInitializing = true
        initializationError = null
        
        scope.launch {
            try {
                // 🌟 1. 物理防禦：強制清空舊模型的 XNNPACK 暫存檔，防止新舊模型結構衝突引發 OOM
                val litertCacheDir = File(context.cacheDir, "litert_cache")
                if (litertCacheDir.exists()) {
                    Log.d("MultimodalDetector", "🧹 正在清除舊的暫存殘渣以釋放 RAM 空間...")
                    litertCacheDir.deleteRecursively()
                }
                litertCacheDir.mkdirs()

                // 如果沒傳入路徑，則從可用列表中取第一個
                val modelFile = targetPath?.let { File(it) } ?: availableModels.firstOrNull()

                this@MultimodalDetector.modelPath = modelFile?.absolutePath

                if (modelFile == null || !modelFile.exists()) {
                    val error = "模型檔案不存在。請確保模型已放入私有目錄：\n" +
                            File(context.getExternalFilesDir(null), "models").absolutePath
                    Log.e("MultimodalDetector", error)
                    initializationError = error
                    isInitializing = false
                    return@launch
                }

                val modelPath = modelFile.absolutePath
                Log.d("MultimodalDetector", "🚀 正在載入模型: ${modelFile.name} (LiteRT-LM)...")

                // 🌟 3. 硬核生存配置：精準配算 CPU 記憶體上限與 Token 預算
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
//                    maxNumTokens = 1024,
//                    maxNumImages = 1,
                    cacheDir = litertCacheDir.absolutePath
                )

                // 建立並初始化引擎
                engine = Engine(config)
                engine?.initialize()
                Log.d("MultimodalDetector", "🎉 LiteRT-LM Engine 初始化成功：${modelFile.name}")
            } catch (e: Exception) {
                val errorMsg = "初始化失敗: ${e.message}\n(提示: 請確保模型為 .litertlm 格式)"
                Log.e("MultimodalDetector", errorMsg, e)
                initializationError = errorMsg
            } finally {
                isInitializing = false
            }
        }
    }

    /**
     * 驗證圖片真偽
     */
    fun verifyImageAuthenticity(inputBitmap: Bitmap, onTokenReceived: (String, Boolean) -> Unit) {
        if (isInitializing) {
            onTokenReceived("模型初始化中，請稍候...", true)
            return
        }

        val currentEngine = engine ?: run {
            val error = initializationError ?: "引擎未初始化"
            onTokenReceived(error, true)
            return
        }

        try {
            // ==========================================
            // 🌟 [第一階段] 執行 CLIP-LoRA 快速二分類偵測
            // ==========================================
            val clipInputSize = 384
            val clipBitmap = resizeBitmapToSquare(inputBitmap, width = clipInputSize)
            val clipInputData = bitmapToInt8ByteArray(clipBitmap, clipInputSize)

            Log.d("CLIP_DEBUG", "🚀 啟動 CLIP 偵測...")
            val clipOutput: Boolean = clipRunner.isFake(clipInputData) // 拿回 true/false
            Log.d("CLIP_DEBUG", "📊 CLIP 偵測結果 (isFake): $clipOutput")

            // ==========================================
            // 🌟 [第二階段] 動態生成 Prompt 並交由 Gemma 4 生成詳細描述
            // ==========================================

            // 1. 清除先前的對話 Session（必須在協程內安全操作）
            activeConversation?.close()
            activeConversation = null

            // 2. 影像預處理：等比例縮放（建議 448，有效降低 S23 GPU 記憶體與時間維度壓力）
            val resizedBitmap = resizeBitmap(inputBitmap, maxSide = 448)
            val imageBytes = bitmapToByteArray(resizedBitmap)

            // 3. 核心動態 Prompt 設計：將 CLIP 結果轉為文字提示注入
            val clipHint = if (clipOutput) {
                "【前級專家模型提示】：經第一階段 CLIP 輕量特徵網絡快速掃描，該圖片已被高度懷疑為『AI 生成偽造影像 (Fake)』。"
            } else {
                "【前級專家模型提示】：經第一階段 CLIP 輕量特徵網絡快速掃描，該圖片初步判定傾向為『相機實地拍攝之真實照片 (Real)』。"
            }

            val prompt = """
                $clipHint
                
                請以此提示作為參考基底，對傳入的圖片進行分析此圖片是否由 AI 生成 (Deepfake/AIGC)。
                請針對以下八個面向進行評估、尋找是否有對應的瑕疵證據並提供解釋：
                1. Lighting & Shadows Consistency
                2. Edges & Boundaries
                3. Texture & Resolution
                4. Perspective & Spatial Relationships
                5. Physical & Common Sense Logic
                6. Text & Symbols
                7. Human & Biological Structure Integrity
                8. Material & Object Details
    
                最終必須以 JSON 格式輸出結果：
                {
                  "is_ai_generated": true/false,
                  "confidence": 0.0-1.0,
                  "reason": "結合第一階段提示與八大面向的總結描述",
                  "detailed_analysis": {
                    "lighting": "解釋",
                    "edges": "解釋",
                    "texture": "解釋",
                    "perspective": "解釋",
                    "logic": "解釋",
                    "text": "解釋",
                    "biological": "解釋",
                    "details": "解釋"
                  }
                }
            """.trimIndent()

            Log.d("GEMMA_DEBUG", "📝 動態 Prompt 注入成功，正在發送給 Gemma 4...")

            val conversation = currentEngine.createConversation()
            activeConversation = conversation

            val contents = Contents.of(
                Content.Text(prompt),
                Content.ImageBytes(imageBytes)
            )
            val message = Message.user(contents)

            conversation.sendMessageAsync(message, object : MessageCallback {
                private var lastFullText = ""

                override fun onMessage(message: Message) {
                    val fullText = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    lastFullText = fullText
                    onTokenReceived(fullText, false)
                }

                override fun onDone() {
                    onTokenReceived(lastFullText, true)
                    // 注意：不在此立刻 close，留給下一次呼叫前清除，或由外部控制
                }

                override fun onError(throwable: Throwable) {
                    Log.e("MultimodalDetector", "推論錯誤", throwable)
                    onTokenReceived("Error: ${throwable.message}", true)
                }
            })

        } catch (e: Exception) {
            Log.e("MultimodalDetector", "推論執行失敗", e)
            onTokenReceived("Error: ${e.message}", true)
        }
    }

    /**
     * 輸入影片並進行 AI 真偽辨識
     */
    fun verifyVideoAuthenticity(videoUri: android.net.Uri, frameCount: Int = 5, onTokenReceived: (String, Boolean) -> Unit) {
        if (isInitializing) {
            onTokenReceived("模型初始化中，請稍候...", true)
            return
        }

        val currentEngine = engine ?: run {
            val error = initializationError ?: "引擎未初始化"
            onTokenReceived(error, true)
            return
        }

        try {
            // 1. 清除先前的對話 Session
            activeConversation?.close()
            activeConversation = null

            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLong() ?: 0L

            val frames = mutableListOf<Bitmap>()
            val interval = if (duration > 0) duration * 1000 / (frameCount + 1) else 1000000L

            for (i in 1..frameCount) {
                val timeUs = i * interval
                retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                    frames.add(resizeBitmap(it, maxSide = 448))
                }
            }
            retriever.release()

            if (frames.isEmpty()) {
                onTokenReceived("無法從影片擷取幀", true)
                return
            }

            val prompt = """
                分析此影片序列是否由 AI 生成 (Deepfake/AIGC)。
                請針對以下八個面向進行評估並提供中文解釋：
                1. Lighting & Shadows Consistency 
                2. Edges & Boundaries 
                3. Texture & Resolution 
                4. Perspective & Spatial Relationships
                5. Physical & Common Sense Logic
                6. Text & Symbols
                7. Human & Biological Structure Integrity 
                8. Material & Object Details
                
                特別注意影格間的一致性與動作流暢度。
                最終必須以 JSON 格式輸出結果：
                { 
                  "is_ai_generated": true/false, 
                  "confidence": 0.0-1.0, 
                  "reason": "總結描述",
                  "detailed_analysis": {
                    "lighting": "解釋",
                    "edges": "解釋",
                    "texture": "解釋",
                    "perspective": "解釋",
                    "logic": "解釋",
                    "text": "解釋",
                    "biological": "解釋",
                    "details": "解釋"
                  }
                }
            """.trimIndent()

            val conversation = currentEngine.createConversation()
            activeConversation = conversation

            val contentList = mutableListOf<Content>()
            contentList.add(Content.Text(prompt))
            for (frame in frames) {
                contentList.add(Content.ImageBytes(bitmapToByteArray(frame)))
            }

            val message = Message.user(Contents.of(contentList))

            conversation.sendMessageAsync(message, object : MessageCallback {
                private var lastFullText = ""
                private var tokenCount = 0 // 🌟 1. 加入 Token 實體計數器

                override fun onMessage(message: Message) {
                    tokenCount++
                    val fullText = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }

                    lastFullText = fullText

                    // 🌟 2. 用最高級別日誌狂噴目前輸出的字元，並印出當前 Token 數量
                    Log.wtf("AIGI_DEBUG", "第 $tokenCount 個 Token 產出! 內容片段 -> [${fullText.takeLast(30)}]")

                    // 🌟 3. 核心物理防禦：如果模型暴走，字數吐超過 100 個字還不停，直接在前端拋出異常「自我了斷」
                    // 因為我們只需要一個簡短的 JSON，絕對不可能超過 100 個 Token
                    if (tokenCount > 100) {
                        Log.e("AIGI_DEBUG", "🚨 偵測到模型陷入自迴歸無限死循環！執行前端物理阻斷！")
                        throw RuntimeException("Force stop infinite loops to save RAM")
                    }

                    onTokenReceived(fullText, false)
                }

                override fun onDone() {
                    Log.d("AIGI_DEBUG", "🎉 模型健康解碼完成，總共輸出 $tokenCount 個 Token")
                    onTokenReceived(lastFullText, true)
                }

                override fun onError(throwable: Throwable) {
                    Log.e("AIGI_DEBUG", "💥 引擎解碼崩潰: ${throwable.message}", throwable)
                    onTokenReceived("Error: ${throwable.message}", true)
                }
            })
        } catch (e: Exception) {
            Log.e("MultimodalDetector", "影片處理失敗", e)
            onTokenReceived("Error: ${e.message}", true)
        }
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    /**
     * 將 Bitmap 轉換為 CLIP 模型需要的 INT8 ByteArray
     */
    private fun bitmapToInt8ByteArray(bitmap: Bitmap, size: Int): ByteArray {
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, 0, 0, size, size)
        
        val byteArray = ByteArray(size * size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            // 簡單處理：將 0-255 對映到 -128 到 127
            // 注意：實際量化公式可能為 (pixel / 255.0 - mean) / std / scale + zero_point
            byteArray[i * 3] = ((p shr 16 and 0xFF) - 128).toByte()
            byteArray[i * 3 + 1] = ((p shr 8 and 0xFF) - 128).toByte()
            byteArray[i * 3 + 2] = ((p and 0xFF) - 128).toByte()
        }
        return byteArray
    }

    /**
     * 將 Bitmap 等比例縮放，使長邊不超過指定像素
     */
    private fun resizeBitmap(source: Bitmap, maxSide: Int = 448): Bitmap {
        val width = source.width
        val height = source.height

        if (width <= maxSide && height <= maxSide) return source

        val ratio = if (width > height) {
            maxSide.toFloat() / width
        } else {
            maxSide.toFloat() / height
        }

        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    private fun resizeBitmapToSquare(source: Bitmap, width: Int = 448): Bitmap {

        return Bitmap.createScaledBitmap(source, width, width, true)
    }

    fun close() {
        activeConversation?.close()
        activeConversation = null
        engine?.close()
        engine = null
        clipRunner.close()
    }
}

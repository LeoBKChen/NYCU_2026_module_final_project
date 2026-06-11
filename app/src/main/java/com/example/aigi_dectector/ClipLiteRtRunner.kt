package com.example.aigi_dectector

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import kotlin.math.exp

class ClipLiteRtRunner(context: Context) {

    private var model: CompiledModel? = null
    private val modelPath = File(context.getExternalFilesDir(null), "models/first_stage/clip_lora_int8_094_099.tflite")

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            if (!modelPath.exists()) {
                Log.e("CLIP_DEBUG", "❌ CLIP 模型不存在: ${modelPath.absolutePath}")
                return
            }

            Log.d("CLIP_DEBUG", "📂 正在從外部路徑載入 CLIP: ${modelPath.absolutePath}")
            model = CompiledModel.create(
                modelPath.absolutePath,
                CompiledModel.Options(Accelerator.CPU) // 在 S23 實機上測試成功後，可改用 Accelerator.GPU
            )
            Log.d("CLIP_DEBUG", "✅ CLIP 模型初始化成功")
        } catch (e: Exception) {
            Log.e("CLIP_DEBUG", "💥 CLIP 初始化崩潰: ${e.message}", e)
        }
    }

    /**
     * 執行二分類 AIGI 偵測推論
     * @param inputData 已經過量化預處理的 ByteArray (224x224x3)
     * @return true 代表是 AI 偽造圖片 (Fake)，false 代表是真實相機照片 (Real)
     */
    fun isFake(inputData: ByteArray): Boolean {
        val currentModel = model ?: run {
            Log.e("CLIP_DEBUG", "推論失敗：模型尚未成功載入")
            return false
        }

        return try {
            val inputBuffers = currentModel.createInputBuffers()
            val outputBuffers = currentModel.createOutputBuffers()

            // 1. 寫入影像 INT8 資料
            inputBuffers[0].writeInt8(inputData)

            // 2. 執行硬體推論
            currentModel.run(inputBuffers, outputBuffers)

            // 3. 讀取結果 (Log 已驗證模型輸出為單一 Float)
            val result: FloatArray = outputBuffers[0].readFloat()

            if (result.isEmpty()) {
                Log.e("CLIP_DEBUG", "推論失敗：輸出 Buffer 為空")
                return false
            }

            // 4. 提取原始的 Raw Logit
            val rawLogit = result[0]

            // 5. 將 Raw Logit 通過 Sigmoid 轉換為 0.0 ~ 1.0 的機率值
            val fakeProbability = sigmoid(rawLogit)

            // 6. 依據 0.5 閾值進行分類 (Class 0: Real, Class 1: Fake)
            val isFakeResult = fakeProbability >= 0.5f

            // 在背景列印詳細數據，方便 Debug 觀看
            Log.d("CLIP_DEBUG", "🎉 推論完成 -> Logit: ${String.format("%.4f", rawLogit)}, Fake機率: ${String.format("%.4f", fakeProbability * 100)}%, 判定isFake: $isFakeResult")

            // 7. 直接回傳布林值
            isFakeResult

        } catch (e: Exception) {
            Log.e("CLIP_DEBUG", "推論執行異常: ${e.message}", e)
            false // 發生異常時預設回傳 false 安全牌
        }
    }

    /**
     * Sigmoid 激活函數：將 (-inf, +inf) 的 Logit 對應到 (0.0, 1.0)
     */
    private fun sigmoid(logit: Float): Float {
        return 1.0f / (1.0f + exp(-logit))
    }

    fun close() {
        model?.close()
        model = null
    }
}
package com.example.aigi_dectector

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aigi_dectector.ui.theme.Aigi_dectectorTheme
import com.google.gson.Gson
import java.io.File
import java.util.Locale

// 定義資料結構以解析模型輸出
data class DetectionResult(
    val is_ai_generated: Boolean = false,
    val confidence: Double = 0.0,
    val reason: String = "",
    val detailed_analysis: Map<String, String> = emptyMap()
)

class MainActivity : ComponentActivity() {
    private lateinit var detector: MultimodalDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detector = MultimodalDetector(this)
        enableEdgeToEdge()
        setContent {
            Aigi_dectectorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DetectorScreen(
                        detector = detector,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.close()
    }
}

@Composable
fun DetectorScreen(detector: MultimodalDetector, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }

    // UI 提示與錯誤處理文字（不再顯示原始 JSON）
    var uiStatusText by remember { mutableStateOf("") }
    var parsedResult by remember { mutableStateOf<DetectionResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            videoUri = null
            parsedResult = null
            uiStatusText = ""
            bitmap = try {
                if (Build.VERSION.SDK_INT < 28) {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                } else {
                    val source = ImageDecoder.createSource(context.contentResolver, it)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                }
            } catch (e: Exception) { null }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            bitmap = null
            parsedResult = null
            uiStatusText = ""
            videoUri = it
        }
    }

    // 解析 JSON 的輔助函式
    fun parseResult(rawText: String): Boolean {
        val logTag = "AIGI_Detector_Debug"
        Log.d(logTag, "=================== [開始解析模型輸出] ===================")
        Log.d(logTag, "1. 原始模型回傳文字 (Raw Text):\n$rawText")
        Log.d(logTag, "--------------------------------------------------------")

        return try {
            // 【優化】直接使用嚴格的括號定位法，無視所有外部的 Markdown 標籤或雜質字串（如 "json"）
            val start = rawText.indexOf("{")
            val end = rawText.lastIndexOf("}")

            if (start == -1 || end == -1 || end <= start) {
                throw IllegalArgumentException("找不到合法的 JSON 物件邊界（缺少 { 或 }）")
            }

            // 僅截取中間純粹的 JSON 物件部分
            val finalJson = rawText.substring(start, end + 1).trim()

            Log.d(logTag, "2. 清洗過後的 JSON 字串 (Processed JSON):\n$finalJson")
            Log.d(logTag, "--------------------------------------------------------")

            // 執行 Gson 反序列化
            parsedResult = Gson().fromJson(finalJson, DetectionResult::class.java)

            Log.d(logTag, "✅ JSON 解析成功！已成功轉換為 DetectionResult 物件。")
            Log.d(logTag, "========================================================")
            true
        } catch (e: Exception) {
            Log.e(logTag, "❌ JSON 解析失敗 (Gson Parsing Exception)！", e)
            Log.e(logTag, "錯誤原因提示: ${e.message}")
            Log.e(logTag, "========================================================")
            false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "AI 偽造偵測系統", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // --- 模型選取區塊 ---
        ModelSelectionSection(detector)

        // 內容預覽卡片
        Card(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (videoUri != null) {
                    Text("🎥 影片已選取：\n${videoUri!!.lastPathSegment}", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                } else {
                    Text("請選擇要分析的圖片或影片", color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { imageLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("選擇圖片") }
            Button(onClick = { videoLauncher.launch("video/*") }, modifier = Modifier.weight(1f)) { Text("選擇影片") }
        }

        // 偵測按鈕
        Button(
            onClick = {
                isAnalyzing = true
                parsedResult = null
                uiStatusText = "啟動核心推論引擎..."

                var accumulatedText = ""

                if (bitmap != null) {
                    detector.verifyImageAuthenticity(bitmap!!) { responseSoFar, done ->
                        if (done) {
                            isAnalyzing = false
                            if (accumulatedText.startsWith("Error:") || accumulatedText.isEmpty()) {
                                uiStatusText = if (accumulatedText.isEmpty()) "偵測失敗：模型未回傳資料" else accumulatedText
                            } else {
                                val success = parseResult(accumulatedText)
                                if (!success) uiStatusText = "解析報告失敗，可能模型輸出格式不正確。"
                            }
                        } else {
                            if (responseSoFar.length >= accumulatedText.length) {
                                accumulatedText = responseSoFar
                            } else {
                                accumulatedText += responseSoFar
                            }
                            uiStatusText = "深度特徵掃描中..."
                        }
                    }
                } else if (videoUri != null) {
                    detector.verifyVideoAuthenticity(videoUri!!) { responseSoFar, done ->
                        if (done) {
                            isAnalyzing = false
                            if (accumulatedText.startsWith("Error:") || accumulatedText.isEmpty()) {
                                uiStatusText = if (accumulatedText.isEmpty()) "偵測失敗：模型未回傳資料" else accumulatedText
                            } else {
                                val success = parseResult(accumulatedText)
                                if (!success) uiStatusText = "解析報告失敗，可能模型輸出格式不正確。"
                            }
                        } else {
                            if (responseSoFar.length >= accumulatedText.length) {
                                accumulatedText = responseSoFar
                            } else {
                                accumulatedText += responseSoFar
                            }
                            uiStatusText = "多影格時序特徵分析中..."
                        }
                    }
                }
            },
            enabled = (bitmap != null || videoUri != null) && !isAnalyzing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isAnalyzing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("深度分析中...")
            } else {
                Text("開始深度分析")
            }
        }

        // 結果與狀態顯示區域
        if (isAnalyzing) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Text(text = uiStatusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        } else if (parsedResult != null) {
            ResultCard(parsedResult!!) { showDetails = true }
        } else if (uiStatusText.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = uiStatusText,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // ─── 完整保留並移至最底部的模型狀態與路徑檢查區塊 ───
        val actualModelPath = detector.modelPath
        val initError = detector.initializationError
        val isInit = detector.initializationError == null && !detector.isInitializing && detector.modelPath != null

        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
            Text(
                text = when {
                    detector.isInitializing -> "⏳ 模型初始化中..."
                    initError != null -> "❌ 初始化失敗"
                    isInit -> "✅ 模型已就緒"
                    detector.modelPath == null -> "⚠️ 尚未載入任何模型"
                    else -> "❓ 未知狀態"
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    detector.isInitializing -> MaterialTheme.colorScheme.secondary
                    initError != null -> MaterialTheme.colorScheme.error
                    isInit -> MaterialTheme.colorScheme.primary
                    detector.modelPath == null -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
            )

            if (initError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = initError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "路徑: ${actualModelPath ?: "未找到模型"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }

    // 詳情對話框
    if (showDetails && parsedResult != null) {
        DetailDialog(parsedResult!!) { showDetails = false }
    }
}

@Composable
fun ModelSelectionSection(detector: MultimodalDetector) {
    var expanded by remember { mutableStateOf(false) }
    val currentModelPath = detector.modelPath
    val availableModels = detector.availableModels

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = "目前模型：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = currentModelPath?.let { File(it).name } ?: "未選取",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { detector.refreshAvailableModels() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新清單")
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !detector.isInitializing
                ) {
                    Text(if (detector.isInitializing) "更換模型中..." else "切換模型")
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    if (availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("目錄下無模型檔案") },
                            onClick = { expanded = false },
                            enabled = false
                        )
                    } else {
                        availableModels.forEach { file ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(file.name, fontWeight = if (detector.modelPath == file.absolutePath) FontWeight.Bold else FontWeight.Normal)
                                        Text(
                                            "${String.format(Locale.getDefault(), "%.2f", file.length() / 1024.0 / 1024.0 / 1024.0)} GB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                },
                                onClick = {
                                    detector.switchModel(file.absolutePath)
                                    expanded = false
                                },
                                leadingIcon = {
                                    RadioButton(
                                        selected = detector.modelPath == file.absolutePath,
                                        onClick = null // 由 MenuItem 處理
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResultCard(result: DetectionResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.is_ai_generated) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (result.is_ai_generated) "判定結果：AI 生成內容 ❌" else "判定結果：真實拍攝內容  ",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (result.is_ai_generated) Color(0xFFD32F2F) else Color(0xFF388E3C),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "信心度：${(result.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray
                )
                Text(
                    text = "點擊區塊查看生成原因與深度分析報告",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(Icons.Default.Info, contentDescription = "詳情", tint = if (result.is_ai_generated) Color(0xFFD32F2F) else Color(0xFF388E3C))
        }
    }
}

@Composable
fun DetailDialog(result: DetectionResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🛡️ 深度分析報告", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = "核心結論：", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                Text(text = result.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))

                if (result.detailed_analysis.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(text = "多維度特徵量化評估：", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))

                    result.detailed_analysis.forEach { (key, value) ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "• ${key.replaceFirstChar { it.uppercase() }}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(text = value, fontSize = 13.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("確認並關閉", fontWeight = FontWeight.Bold)
            }
        }
    )
}
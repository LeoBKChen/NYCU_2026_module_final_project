# Multimodal AI Detector 實作指南與筆記 (2024/05 更新)

## 1. 核心技術痛點與解決方案

### A. 模型檔案與格式確認
*   **檔案來源**：Hugging Face 下載的 `gemma-4-E4B-it.litertlm`。
*   **重要提示**：`LlmInference` 核心庫 (`com.google.mediapipe:tasks-genai`) 預期輸入的是一個 **MediaPipe GenAI .task bundle** (Flatbuffer 格式)。如果你下載的是原始的 `.bin` 或 `.litertlm` 權重檔案且初始化失敗，可能需要先透過 [MediaPipe GenAI Converter](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android#convert-model) 進行轉換。
*   **常見錯誤**：若出現 `The model is not a valid Flatbuffer buffer`，代表檔案格式不相容。

### 方案 B (推薦)：使用 LiteRT-LM SDK (相容 .litertlm)
如果您使用的是最新的 `gemma-4-E4B-it.litertlm` 格式，專案已切換至 LiteRT-LM SDK：
*   **依賴項**：`com.google.ai.edge.litertlm:litertlm-android:0.12.0`
*   **優點**：原生支援 `.litertlm` 二進位結構，效能更佳且不會有 Flatbuffer 格式錯誤。
*   **模型路徑**：程式碼會自動尋找 `models/gemma-4-E4B-it.litertlm` 或 `models/gemma4.bin`。

### 部署步驟更新：
1.  **建立目錄**：`adb shell mkdir -p /sdcard/Android/data/com.example.aigi_dectector/files/models/`
2.  **推送模型**：`adb push gemma-4-E4B-it.litertlm /sdcard/Android/data/com.example.aigi_dectector/files/models/gemma-4-E4B-it.litertlm`
3.  **確認大小**：`adb shell ls -lh /sdcard/Android/data/com.example.aigi_dectector/files/models/` (應約為 3.6GB)

### C. SDK 版本與多模態 API (0.10.35)
*   **架構變動**：優先嘗試使用 `Session` 模式處理多模態輸入。
*   **多影格支援**：已實作影片自動抽幀 (5 frames) 並透過 `addImage` 加入 Session 進行分析。
*   **效能優化**：所有輸入的圖片與影片影格在進入推論前，都會自動等比例縮放至長邊為 768 像素，以顯著降低 Token 數量並加速推論速度。

---

## 2. 測試介面說明 (MainActivity.kt)
目前的 UI 支援：
1. **圖片偵測**：選取圖片後直接進行分析。
2. **影片偵測**：選取影片後，系統會自動從影片中擷取 5 個關鍵影格送入模型進行時序分析。
3. **模型狀態檢查**：UI 下方會顯示模型檔案是否正確放置於指定路徑，並提供完整的絕對路徑參考。

---

## 3. 核心程式碼結構
* **MultimodalDetector.kt**: 封裝了 MediaPipe `LlmInference`。
    * 提供 `verifyImageAuthenticity` 與 `verifyVideoAuthenticity`。
    * 使用 `MediaMetadataRetriever` 處理影片影格擷取。
* **MainActivity.kt**: 提供 Compose 介面，處理內容選取、分析狀態管理與結果顯示。

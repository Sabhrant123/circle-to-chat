package com.example.geminichat.llm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.StringBuilder
import java.util.Locale
import kotlin.math.max

/**
 * Handles LLM state, model downloading, initialization, and inference.
 */
class LlmViewModel(
    private val appContext: Context,
    initialModelPath: String,
    private var hfToken: String
) : ViewModel() {

    private val STATS = listOf(
        Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
        Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
        Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
        Stat(id = "latency", label = "Latency", unit = "sec"),
    )

    private val defaultAccelerator = "CPU"
    private val whitespaceRegex = Regex("\\s+")

    var latestBenchmarkResult by mutableStateOf<ChatMessageBenchmarkLlmResult?>(null)
        private set

    // --- Public State for Composable Functions (used by MainActivity) ---
    var inProgress by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false) // Model is being loaded/initialized
        private set
    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var downloadComplete by mutableStateOf(false)
        private set
    var needsInitialization by mutableStateOf(false)
        private set
    var isDownloadPaused by mutableStateOf(false)
        private set

    var response by mutableStateOf<String?>(null) // Final LLM description
        private set
    var error by mutableStateOf<String?>(null) // Error message
        private set
    var currentModelPath by mutableStateOf(initialModelPath) // Path to the currently used model
    var isModelReady by mutableStateOf(false) // Whether the model is fully initialized and ready
        private set
    var selectedAccelerator by mutableStateOf(defaultAccelerator)
        private set

    // --- Internal Properties ---
    private val downloadBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ModelDownloadService.ACTION_PROGRESS -> {
                    val progress = intent.getFloatExtra(ModelDownloadService.EXTRA_PROGRESS, 0f)
                    downloadProgress = progress.coerceIn(0f, 1f)
                    isDownloading = true
                    isDownloadPaused = false
                    downloadComplete = false
                    needsInitialization = false
                    error = null
                }

                ModelDownloadService.ACTION_COMPLETE -> {
                    val path = intent.getStringExtra(ModelDownloadService.EXTRA_FILEPATH)
                    if (!path.isNullOrBlank()) {
                        isDownloading = false
                        isDownloadPaused = false
                        error = null
                        completeDownload(File(path))
                    }
                }

                ModelDownloadService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(ModelDownloadService.EXTRA_ERROR_MESSAGE)
                    if (!msg.isNullOrBlank()) {
                        error = msg
                    }
                    isDownloading = false
                    downloadComplete = false
                    needsInitialization = false
                    when {
                        msg?.contains("cancel", ignoreCase = true) == true -> {
                            isDownloadPaused = false
                            downloadProgress = 0f
                            downloadComplete = false
                            needsInitialization = false
                            isModelReady = false
                            lastDownloadUrl = null
                            lastFileName = null
                            lastToken = null
                        }
                        msg?.contains("pause", ignoreCase = true) == true -> {
                            isDownloadPaused = true
                        }
                        else -> {
                            // Default to paused state so the user can resume from the Settings screen.
                            isDownloadPaused = true
                        }
                    }
                }
            }
        }
    }
    private var downloadReceiverRegistered = false
    private var lastDownloadUrl: String? = null
    private var lastFileName: String? = null
    private var lastToken: String? = null
    private var llmInstance: LlmModelInstance? = null // The actual LLM engine instance
    private var acceleratorReinitPending = false

    init {
        val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        selectedAccelerator = prefs.getString("selected_accelerator", defaultAccelerator) ?: defaultAccelerator

        val initialFile = File(currentModelPath)
        if (isValidModelFile(initialFile)) {
            downloadComplete = true
            currentModelPath = initialFile.absolutePath
            initialize(currentModelPath)
        } else {
            currentModelPath = ""
        }

        val savedToken = prefs.getString("huggingface_token", null)
        if (!savedToken.isNullOrBlank()) {
            hfToken = savedToken
        }

        registerDownloadReceiver()
    }

    override fun onCleared() {
        if (downloadReceiverRegistered) {
            try {
                appContext.unregisterReceiver(downloadBroadcastReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver already unregistered; ignore.
            }
            downloadReceiverRegistered = false
        }
        LlmModelHelper.cleanUp(llmInstance)
        llmInstance = null
        super.onCleared()
    }

    /**
     * Initializes the LLM engine using the specified model path.
     */
    fun initialize(modelPath: String) {
        if (preparing) return
        preparing = true
        error = null
        currentModelPath = modelPath
        needsInitialization = false

        val acceleratorChoice = selectedAccelerator

        // Launch initialization on a background thread
        viewModelScope.launch(Dispatchers.Default) {
            val result = LlmModelHelper.initialize(
                context = appContext,
                modelPath = modelPath,
                enableVision = true,
                accelerator = acceleratorChoice,
            )
            result.onSuccess { inst ->
                llmInstance = inst
                withContext(Dispatchers.Main) {
                    error = null
                    isModelReady = true
                    downloadComplete = true
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Model initialization failed."
                    isModelReady = false
                    needsInitialization = true
                }
            }
            withContext(Dispatchers.Main) {
                preparing = false
                finalizeAcceleratorReinitIfNeeded()
            }
        }
    }

    /**
     * Delete the current model file
     */
    fun deleteModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(currentModelPath)
                if (file.exists()) {
                    // Clean up the current instance first
                    LlmModelHelper.cleanUp(llmInstance)
                    llmInstance = null

                    // Then delete the file
                    val deleted = file.delete()
                    withContext(Dispatchers.Main) {
                        if (deleted) {
                            currentModelPath = ""
                            downloadComplete = false
                            needsInitialization = false
                            isModelReady = false
                            downloadProgress = 0f
                            error = null
                            lastDownloadUrl = null
                            lastFileName = null
                        } else {
                            error = "Failed to delete model file"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = "Error deleting model: ${e.message}"
                }
            }
        }
    }

    /**
     * Clears LLM response and error states.
     */
    fun clearState() {
        response = null
        error = null
    }

    /**
     * Updates the in-memory Hugging Face token for the current session.
     */
    fun updateToken(token: String) {
        hfToken = token.trim()
        appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("huggingface_token", hfToken)
            .apply()
    }

    fun updateAccelerator(accelerator: String) {
        val normalized = accelerator.uppercase(Locale.US)
        val storedValue = if (normalized == "GPU") "GPU" else defaultAccelerator
        if (storedValue == selectedAccelerator) return
        selectedAccelerator = storedValue
        appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_accelerator", storedValue)
            .apply()

        requestAcceleratorReinitialization()
    }

    private fun requestAcceleratorReinitialization() {
        if (currentModelPath.isBlank()) return
        if (preparing) {
            acceleratorReinitPending = true
            return
        }
        if (inProgress) {
            acceleratorReinitPending = true
            needsInitialization = true
            return
        }
        reinitializeLlmForAcceleratorChange()
    }

    private fun reinitializeLlmForAcceleratorChange() {
        acceleratorReinitPending = false
        needsInitialization = false
        isModelReady = false

        val instance = llmInstance
        llmInstance = null

        viewModelScope.launch(Dispatchers.Default) {
            instance?.let { LlmModelHelper.cleanUp(it) }
            withContext(Dispatchers.Main) {
                if (currentModelPath.isNotBlank()) {
                    initialize(currentModelPath)
                }
            }
        }
    }

    private fun finalizeAcceleratorReinitIfNeeded() {
        if (acceleratorReinitPending && !preparing && !inProgress) {
            reinitializeLlmForAcceleratorChange()
        }
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ModelDownloadService.ACTION_PROGRESS)
            addAction(ModelDownloadService.ACTION_COMPLETE)
            addAction(ModelDownloadService.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(
            appContext,
            downloadBroadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        downloadReceiverRegistered = true
    }

    /**
     * Attempts to stop the current LLM inference process immediately.
     * This is the core function for the 'Stop' button feature.
     */
    fun cancelInference() {
        llmInstance?.let { inst ->
            Log.d("LlmViewModel", "Stopping response for current model instance…")
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    inst.session.cancelGenerateResponseAsync()
                    Log.d("LlmViewModel", "Cancelled generation gracefully.")
                } catch (e: Exception) {
                    Log.w(
                        "LlmViewModel",
                        "Graceful cancel failed, resetting session as fallback",
                        e
                    )
                    try {
                        LlmModelHelper.resetSession(inst, enableVision = true)
                    } catch (_: Exception) {
                        // Ignore fallback errors, state update handles any lingering issues
                    }
                }

                withContext(Dispatchers.Main) {
                    error = "Response generation stopped by user."
                    inProgress = false
                    finalizeAcceleratorReinitIfNeeded()
                }
            }
        } ?: run {
            error = "No active model session to cancel."
            inProgress = false
            finalizeAcceleratorReinitIfNeeded()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun downloadModel(
        downloadUrl: String,
        fileName: String,
        token: String,
        onComplete: ((String) -> Unit)? = null
    ) {
        if (isDownloading) return

        val sharedPrefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val trimmedToken = token.trim()
        if (trimmedToken.isNotBlank()) {
            hfToken = trimmedToken
            sharedPrefs.edit().putString("huggingface_token", hfToken).apply()
        } else if (hfToken.isBlank()) {
            val savedToken = sharedPrefs.getString("huggingface_token", "") ?: ""
            if (savedToken.isNotBlank()) {
                hfToken = savedToken
            }
        }

        if (hfToken.isBlank()) {
            error = "Please enter a valid Hugging Face token to start the download."
            return
        }

        lastDownloadUrl = downloadUrl
        lastFileName = fileName
        lastToken = hfToken
        isDownloading = true
        isDownloadPaused = false
        downloadProgress = 0f
        downloadComplete = false
        needsInitialization = false
        isModelReady = false
        error = null

        sendDownloadServiceCommand(
            action = ModelDownloadService.ACTION_START,
            requireForeground = true
        ) {
            putExtra(ModelDownloadService.EXTRA_URL, downloadUrl)
            putExtra(ModelDownloadService.EXTRA_FILENAME, fileName)
            putExtra(ModelDownloadService.EXTRA_TOKEN, hfToken)
        }

        // onComplete callback is invoked indirectly once the service broadcasts completion.
    }

    fun cancelDownload() {
        if (!isDownloading && !isDownloadPaused) return

        sendDownloadServiceCommand(
            action = ModelDownloadService.ACTION_CANCEL,
            requireForeground = false
        )

        downloadProgress = 0f
        downloadComplete = false
        needsInitialization = false
        isDownloading = false
        isModelReady = false
        error = "Download cancelled."
        isDownloadPaused = false
        lastDownloadUrl = null
        lastFileName = null
        lastToken = null
    }

    fun pauseDownload() {
        if (!isDownloading || isDownloadPaused) return
        isDownloadPaused = true
        isDownloading = false
        error = "Download paused."
        sendDownloadServiceCommand(
            action = ModelDownloadService.ACTION_PAUSE,
            requireForeground = false
        )
    }

    fun resumeDownload() {
        if (!isDownloadPaused) return
        val url = lastDownloadUrl ?: return
        val fileName = lastFileName ?: return
        val token = lastToken ?: hfToken
        isDownloadPaused = false
        isDownloading = true
        error = null
        sendDownloadServiceCommand(
            action = ModelDownloadService.ACTION_RESUME,
            requireForeground = true
        ) {
            putExtra(ModelDownloadService.EXTRA_URL, url)
            putExtra(ModelDownloadService.EXTRA_FILENAME, fileName)
            putExtra(ModelDownloadService.EXTRA_TOKEN, token)
        }
    }

    private fun completeDownload(file: File) {
        downloadComplete = true
        needsInitialization = true
        currentModelPath = file.absolutePath
        downloadProgress = 1f
        error = null
        isDownloadPaused = false
        lastDownloadUrl = null
        lastFileName = null
        lastToken = null
        Log.d("LlmViewModel", "Download complete: ${file.absolutePath}")

        initializeModel()
    }

    fun initializeModel() {
        if (preparing) return
        if (currentModelPath.isBlank()) {
            error = "Model path missing for initialization."
            return
        }
        needsInitialization = false
        initialize(currentModelPath)
    }

    private fun sendDownloadServiceCommand(
        action: String,
        requireForeground: Boolean,
        extrasConfigurer: Intent.() -> Unit = {}
    ) {
        val intent = Intent(appContext, ModelDownloadService::class.java).apply {
            this.action = action
            extrasConfigurer()
        }
        if (requireForeground) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun isValidModelFile(file: File?): Boolean {
        if (file == null) return false
        return file.exists() && file.length() >= MIN_MODEL_FILE_BYTES
    }

    /**
     * Runs the cropped bitmap through the multimodal LLM to generate a description.
     */
    fun describeImage(bitmap: Bitmap, userPrompt: String?, onResponseComplete: ((String) -> Unit)? = null) {
        val inst = llmInstance
        if (inst == null) {
            error = "AI Model is not ready. Please wait for initialization or download."
            Log.e("LlmViewModel", "describeImage: llmInstance is null.")
            return
        }

        val acceleratorUsed = selectedAccelerator

        // Use user-provided prompt if available, else fallback to default
        val prompt = if (!userPrompt.isNullOrBlank()) {
            userPrompt.trim()
        } else {
            "Describe the image."
        }

        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                inProgress = true
                response = null
                error = null
                Log.d(
                    "LlmViewModel",
                    "describeImage: Starting image description with prompt: $prompt"
                )
            }
            try {
                LlmModelHelper.resetSession(inst, enableVision = true)
                val imgs = listOf(bitmap)

                val prefillTokens = max(estimateTokenCount(prompt), 1)
                var firstRun = true
                var timeToFirstToken = 0f
                var firstTokenTs = 0L
                var decodeTokens = 0
                var prefillSpeed = 0f
                var decodeSpeed = 0f
                val start = System.currentTimeMillis()
                val modelName = File(currentModelPath).name.ifBlank { currentModelPath.ifBlank { "unknown" } }

                latestBenchmarkResult = null

                // Run inference and stream the result back to the UI
                LlmModelHelper.runInference(
                    instance = inst,
                    input = prompt,
                    images = imgs,
                    resultListener = { partial, done ->
                        val curTs = System.currentTimeMillis()
                        var benchmarkResult: ChatMessageBenchmarkLlmResult? = null

                        if (partial.isNotEmpty()) {
                            if (firstRun) {
                                firstTokenTs = curTs
                                timeToFirstToken = (firstTokenTs - start).coerceAtLeast(0L).toFloat() / 1000f
                                if (timeToFirstToken > 0f) {
                                    prefillSpeed = prefillTokens.toFloat() / timeToFirstToken
                                    if (prefillSpeed.isNaN() || prefillSpeed.isInfinite()) {
                                        prefillSpeed = 0f
                                    }
                                }
                                firstRun = false
                            } else {
                                decodeTokens += max(estimateTokenCount(partial), 1)
                            }
                        }

                        if (done) {
                            val decodeDurationSec = if (firstTokenTs == 0L) 0f else (curTs - firstTokenTs).toFloat() / 1000f
                            decodeSpeed = if (decodeDurationSec > 0f) decodeTokens / decodeDurationSec else 0f
                            if (decodeSpeed.isNaN() || decodeSpeed.isInfinite()) {
                                decodeSpeed = 0f
                            }
                            val latencySeconds = (curTs - start).toFloat() / 1000f
                            benchmarkResult = ChatMessageBenchmarkLlmResult(
                                orderedStats = STATS,
                                statValues = mutableMapOf(
                                    "prefill_speed" to prefillSpeed,
                                    "decode_speed" to decodeSpeed,
                                    "time_to_first_token" to timeToFirstToken,
                                    "latency" to latencySeconds,
                                ),
                                running = false,
                                latencyMs = -1f,
                                accelerator = acceleratorUsed,
                            )
                        }

                        viewModelScope.launch(Dispatchers.Main) {
                            response = (response ?: "") + partial // Append the streamed response
                            if (done) {
                                benchmarkResult?.let {
                                    updateLastTextMessageLlmBenchmarkResult(
                                        model = modelName,
                                        llmBenchmarkResult = it,
                                        accelerator = acceleratorUsed,
                                    )
                                }
                                inProgress = false
                                // Call the callback with the final response
                                response?.let { onResponseComplete?.invoke(it) }
                                Log.d(
                                    "LlmViewModel",
                                    "describeImage: completed. Response: ${'$'}{response}"
                                )

                                finalizeAcceleratorReinitIfNeeded()
                            }
                        }
                    },
                    cleanUpListener = {
                        // Cleanup logic is handled by the 'done' check in resultListener
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Inference error"
                    inProgress = false
                    Log.e("LlmViewModel", "describeImage: Inference error: ${'$'}{e.message}")
                    finalizeAcceleratorReinitIfNeeded()
                }
            }
        }
    }

    /**
     * Runs text inference for chat responses.
     */
    fun respondToText(input: String, conversationHistory: List<String> = emptyList(), onResponseComplete: ((String) -> Unit)? = null) {
        val inst = llmInstance
        if (inst == null) {
            error = "AI Model is not ready. Please wait for initialization or download."
            Log.e("LlmViewModel", "respondToText: llmInstance is null.")
            return
        }

        val acceleratorUsed = selectedAccelerator

        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                inProgress = true
                response = null
                error = null
                Log.d("LlmViewModel", "respondToText: Starting text response. Input: $input")
            }
            try {
                LlmModelHelper.resetSession(inst, enableVision = false)

                // Build context from conversation history
                val contextBuilder = StringBuilder()
                if (conversationHistory.isNotEmpty()) {
                    contextBuilder.append("Previous conversation:\n")
                    conversationHistory.forEach { message ->
                        contextBuilder.append("$message\n")
                    }
                    contextBuilder.append("\nNow respond to: ")
                }

                // Add current input
                val contextualInput = if (conversationHistory.isNotEmpty()) {
                    "${contextBuilder}$input"
                } else {
                    input
                }

                val prefillTokens = max(estimateTokenCount(contextualInput), 1)
                var firstRun = true
                var timeToFirstToken = 0f
                var firstTokenTs = 0L
                var decodeTokens = 0
                var prefillSpeed = 0f
                var decodeSpeed = 0f
                val start = System.currentTimeMillis()
                val modelName = File(currentModelPath).name.ifBlank { currentModelPath.ifBlank { "unknown" } }

                latestBenchmarkResult = null

                // Run inference and stream the result back to the UI
                LlmModelHelper.runInference(
                    instance = inst,
                    input = contextualInput,
                    images = emptyList(),
                    resultListener = { partial, done ->
                        val curTs = System.currentTimeMillis()
                        var benchmarkResult: ChatMessageBenchmarkLlmResult? = null

                        if (partial.isNotEmpty()) {
                            if (firstRun) {
                                firstTokenTs = curTs
                                timeToFirstToken = (firstTokenTs - start).coerceAtLeast(0L).toFloat() / 1000f
                                if (timeToFirstToken > 0f) {
                                    prefillSpeed = prefillTokens.toFloat() / timeToFirstToken
                                    if (prefillSpeed.isNaN() || prefillSpeed.isInfinite()) {
                                        prefillSpeed = 0f
                                    }
                                }
                                firstRun = false
                            } else {
                                decodeTokens += max(estimateTokenCount(partial), 1)
                            }
                        }

                        if (done) {
                            val decodeDurationSec = if (firstTokenTs == 0L) 0f else (curTs - firstTokenTs).toFloat() / 1000f
                            decodeSpeed = if (decodeDurationSec > 0f) decodeTokens / decodeDurationSec else 0f
                            if (decodeSpeed.isNaN() || decodeSpeed.isInfinite()) {
                                decodeSpeed = 0f
                            }
                            val latencySeconds = (curTs - start).toFloat() / 1000f
                            benchmarkResult = ChatMessageBenchmarkLlmResult(
                                orderedStats = STATS,
                                statValues = mutableMapOf(
                                    "prefill_speed" to prefillSpeed,
                                    "decode_speed" to decodeSpeed,
                                    "time_to_first_token" to timeToFirstToken,
                                    "latency" to latencySeconds,
                                ),
                                running = false,
                                latencyMs = -1f,
                                accelerator = acceleratorUsed,
                            )
                        }

                        viewModelScope.launch(Dispatchers.Main) {
                            response = (response ?: "") + partial // Append the streamed response
                            if (done) {
                                benchmarkResult?.let {
                                    updateLastTextMessageLlmBenchmarkResult(
                                        model = modelName,
                                        llmBenchmarkResult = it,
                                        accelerator = acceleratorUsed,
                                    )
                                }
                                inProgress = false
                                // Call the callback with the final response
                                response?.let { onResponseComplete?.invoke(it) }
                                Log.d(
                                    "LlmViewModel",
                                    "respondToText: Text response completed. Response: ${'$'}{response}"
                                )

                                finalizeAcceleratorReinitIfNeeded()
                            }
                        }
                    },
                    cleanUpListener = {
                        // Cleanup logic is handled by the 'done' check in resultListener
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Inference error"
                    inProgress = false
                    Log.e("LlmViewModel", "respondToText: Inference error: ${'$'}{e.message}")
                    finalizeAcceleratorReinitIfNeeded()
                }
            }
        }
    }

    fun consumeLatestBenchmarkResult(): ChatMessageBenchmarkLlmResult? {
        val result = latestBenchmarkResult
        latestBenchmarkResult = null
        return result
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateLastTextMessageLlmBenchmarkResult(
        model: String,
        llmBenchmarkResult: ChatMessageBenchmarkLlmResult,
        accelerator: String?,
    ) {
        latestBenchmarkResult = llmBenchmarkResult
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(whitespaceRegex).count { it.isNotBlank() }
    }

    /**
     * Resets the response/error states. Called when moving from Result back to Chat screen,
     * or after a message has been added to history (for cleanup).
     */
    fun clearResponse() {
        response = null
        // Note: We don't clear 'error' here because the ChatScreen needs it immediately
        // after cancellation to insert the "stopped by user" message into the chat history.
        inProgress = false
    }


    companion object {
        const val MIN_MODEL_FILE_BYTES = 1_000_000_000L

        fun provideFactory(appContext: Context, modelPath: String, token: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    // Passed from MainActivity:
                    return LlmViewModel(appContext.applicationContext, modelPath, token) as T
                }
            }
    }
}

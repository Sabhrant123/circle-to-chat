package com.example.geminichat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.ImageDecoder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.consumeAllChanges

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color as ComposeColor
import com.example.geminichat.ui.theme.MiraEdgeTheme
import com.example.geminichat.llm.ChatMessageBenchmarkLlmResult
import com.example.geminichat.llm.LlmViewModel

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import java.io.File
import java.io.FileOutputStream
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.circletochat.ai.model.recommendation.ModelRecommendationEngine
import kotlinx.coroutines.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke


data class Message(
    val text: String,
    val isBot: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val usedForContext: Boolean = false,
    val benchmarkStats: ChatMessageBenchmarkLlmResult? = null,
)

private val WelcomeMessage = """
Welcome to MiraEdge! I'm your on-device assistant ready to help.

My capabilities include:
- Generating different kinds of creative text formats: I can write stories, poems, code, scripts, musical pieces, emails, letters, and more.
- Answering your questions in an informative way: I'll try my best to provide comprehensive and helpful answers, drawing on a vast amount of knowledge.
- Following your instructions and completing your requests thoughtfully.
- Adapting to different writing styles.

Modes available:
- Chat
- Circle-to-Describe
- Summarize
- 100% on-device
""".trimIndent()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun createImageFile(context: Context): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val storageDir = context.cacheDir
    return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
}

private fun Bitmap.downscaleIfTooLarge(maxDimension: Int = 1280): Bitmap {
    val maxSide = max(width, height)
    if (maxSide <= maxDimension) return this

    val scale = maxDimension.toFloat() / maxSide.toFloat()
    val newWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val newHeight = (height * scale).roundToInt().coerceAtLeast(1)

    return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // get an Android Context for file lookups inside composable
            val appContext = LocalContext.current.applicationContext

            // App-level states remembered inside composition (correct usage)
            var isDarkTheme by remember { mutableStateOf(false) }
            var currentScreen by remember { mutableStateOf("chat") }
            var attachedImage by remember { mutableStateOf<Bitmap?>(null) }
            var imageToCrop by remember { mutableStateOf<Bitmap?>(null) }


            // Determine the initial model path. Check for downloaded file first, then fallback to legacy ADB path.
            val initialAppFilePath = remember {
                File(appContext.filesDir, "llm/gemma-3n-E2B-it-int4.task").absolutePath
            }
            val legacyModelPath = "/data/local/tmp/llm/gemma-3n-E2B-it-int4.task"
            val initialModelPath = remember {
                if (File(initialAppFilePath).exists()) initialAppFilePath else legacyModelPath
            }

            // LLM ViewModel
            val llmVm: LlmViewModel = viewModel(
                factory = LlmViewModel.provideFactory(
                    appContext = appContext,
                    modelPath = initialModelPath,
                    token = BuildConfig.HUGGING_FACE_TOKEN
                )
            )

            MiraEdgeTheme(darkTheme = isDarkTheme) {
                val snackbarHostState = remember { SnackbarHostState() }
                
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        when (currentScreen) {
                            "chat" -> ChatScreen(
                                onSettingsClick = { currentScreen = "settings" },
                                onImageSelected = { bitmap ->
                                    imageToCrop = bitmap
                                    currentScreen = "crop"
                                },
                                attachedImage = attachedImage,
                                onRemoveImage = { attachedImage = null },
                                llmVm = llmVm
                            )
                            "settings" -> SettingsScreen(
                                isDarkTheme = isDarkTheme,
                                onThemeToggle = { isDarkTheme = it },
                                onBackClick = { currentScreen = "chat" },
                                llmVm = llmVm // Pass llmVm here
                            )
                            "crop" -> imageToCrop?.let { bitmap ->
                                ImageCropScreen(
                                    imageBitmap = bitmap,
                                    onCropComplete = { croppedBitmap ->
                                        currentScreen = "chat"
                                        imageToCrop = null
                                        attachedImage = croppedBitmap
                                    },
                                    onCancel = {
                                        currentScreen = "chat"
                                        imageToCrop = null
                                    }
                                )
                            }
                        }
                    }
                    
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onSettingsClick: () -> Unit,
    onImageSelected: (Bitmap) -> Unit,
    attachedImage: Bitmap?,
    onRemoveImage: () -> Unit,
    llmVm: LlmViewModel
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // Load saved messages from SharedPreferences
    val messages = remember {
        val savedMessages = sharedPrefs.getString("chat_messages", null)
        if (savedMessages != null) {
            try {
                val type = object : TypeToken<List<Message>>() {}.type
                val messageList: List<Message> = Gson().fromJson(savedMessages, type)
                mutableStateListOf(*messageList.toTypedArray())
            } catch (e: Exception) {
                mutableStateListOf(
                    Message(WelcomeMessage, true)
                )
            }
        } else {
            mutableStateListOf(
                Message(WelcomeMessage, true)
            )
        }
    }

    // Define the local clear function
    val clearChatHistory: () -> Unit = {
        // 1. Clear the mutable list
        messages.clear()
        // 2. Add the initial welcome message
        messages.add(Message(WelcomeMessage, true))
        // 3. Clear SharedPreferences
        sharedPrefs.edit().remove("chat_messages").apply()
        // 4. Clear attached image and input text
        onRemoveImage()
        text = ""
        // 5. Cancel any ongoing LLM inference and clear error state
        llmVm.cancelInference()
        llmVm.clearState()
    }


    // Save messages when they change
    LaunchedEffect(messages.size) {
        val messagesJson = Gson().toJson(messages.toList())
        sharedPrefs.edit().putString("chat_messages", messagesJson).apply()
    }

    var showImagePreview by remember { mutableStateOf(false) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { uri ->
                try {
                    val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                            .copy(Bitmap.Config.ARGB_8888, true)
                    }

                    val bitmap = rawBitmap.downscaleIfTooLarge(maxDimension = 1280)
                    onImageSelected(bitmap)

                    if (rawBitmap !== bitmap) {
                        rawBitmap.recycle()
                    }
                } catch (e: Exception) {
                    // TODO: surface decoding failure to the user
                } finally {
                    cameraImageUri = null
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val imageFile = createImageFile(context)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        } else {
            showPermissionDeniedDialog = true
        }
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Use ContentResolver to get Bitmap
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        .copy(Bitmap.Config.ARGB_8888, true)
                }

                val bitmap = rawBitmap.downscaleIfTooLarge(maxDimension = 1280)
                onImageSelected(bitmap)

                if (rawBitmap !== bitmap) {
                    rawBitmap.recycle()
                }
            } catch (e: Exception) {
                // Log or handle error as needed
            }
        }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(12.dp)) {
        // Top bar with settings, CLEAR BUTTON, and model status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            var acceleratorMenuExpanded by remember { mutableStateOf(false) }
            // Model status (Left side)
            val colorScheme = MaterialTheme.colorScheme
            val expectedPath = File(context.filesDir, "llm/gemma-3n-E2B-it-int4.task")
            fun File.isUsableModel() = exists() && length() >= 1_000_000_000L
            val modelFileExists = expectedPath.isUsableModel() || File(llmVm.currentModelPath).isUsableModel()
            val modelStatus = when {
                llmVm.isDownloading -> "Downloading model..."
                llmVm.preparing -> "Initializing model..."
                llmVm.isModelReady -> "Model ready"
                llmVm.downloadComplete || modelFileExists -> "Model downloaded, initializing..."
                else -> "Model not found"
            }
            val statusColor = when {
                llmVm.isModelReady -> colorScheme.primary
                llmVm.isDownloading || llmVm.preparing || llmVm.downloadComplete || modelFileExists -> colorScheme.secondary
                else -> colorScheme.error
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                    OutlinedButton(
                        onClick = { acceleratorMenuExpanded = true },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = llmVm.selectedAccelerator,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null
                        )
                    }

                    DropdownMenu(
                        expanded = acceleratorMenuExpanded,
                        onDismissRequest = { acceleratorMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("CPU") },
                            onClick = {
                                llmVm.updateAccelerator("CPU")
                                acceleratorMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("GPU") },
                            onClick = {
                                llmVm.updateAccelerator("GPU")
                                acceleratorMenuExpanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = modelStatus,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor
                )
            }

            // Right side: Group Clear and Settings buttons
            Row(verticalAlignment = Alignment.CenterVertically) {

                // Clear Chat Button
                IconButton(
                    onClick = clearChatHistory,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Clear Chat History",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // Existing: Settings Button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Chat area with auto-scrolling
        val listState = rememberLazyListState()
        val isUserAtBottom by remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                val total = listState.layoutInfo.totalItemsCount - 1
                lastVisible == total
            }
        }

        // Combine persisted messages with the current streaming response (if any)
        val streamingResponse = llmVm.response
        val allChatItems = buildList<Any> {
            addAll(messages)
            when {
                !streamingResponse.isNullOrBlank() -> add(
                    Message(
                        text = streamingResponse,
                        isBot = true,
                        usedForContext = true
                    )
                )
                llmVm.inProgress -> add("loading_indicator") // Fallback spinner until first tokens arrive
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            items(allChatItems) { item ->
                when (item) {
                    is Message -> {
                        ChatBubble(message = item, context = context)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    is String -> if (item == "loading_indicator") {
                        // Show loading indicator
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(
                                    topEnd = 18.dp,
                                    topStart = 6.dp,
                                    bottomEnd = 18.dp,
                                    bottomStart = 18.dp
                                ),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp,
                                modifier = Modifier.widthIn(max = 260.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Assistant",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "Thinking", style = MaterialTheme.typography.bodyLarge)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // Auto-scroll to bottom when new items are added
        LaunchedEffect(allChatItems.size) {
            if (isUserAtBottom && allChatItems.isNotEmpty()) {
                listState.animateScrollToItem(index = allChatItems.size - 1)
            }
        }

        // Input bar
        Surface(
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(4.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Attached image preview
                attachedImage?.let { bitmap ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showImagePreview = true },
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Image attached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = onRemoveImage,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove image",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(fontSize = 16.sp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                "Type a message",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        innerTextField()
                    }

                    // --- IMAGE AND CAMERA BUTTONS ---
                    // These buttons should be disabled when inference is in progress.
                    IconButton(
                        onClick = {
                            when {
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED -> {
                                    val imageFile = createImageFile(context)
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        imageFile
                                    )
                                    cameraImageUri = uri
                                    cameraLauncher.launch(uri)
                                }

                                activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                                    activity,
                                    Manifest.permission.CAMERA
                                ) -> {
                                    showPermissionRationaleDialog = true
                                }

                                else -> {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        },
                        enabled = !llmVm.inProgress,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = "Camera")
                    }

                    IconButton(
                        onClick = {
                            galleryLauncher.launch("image/*")
                        },
                        enabled = !llmVm.inProgress,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Icon(imageVector = Icons.Filled.Image, contentDescription = "Gallery")
                    }

                    // --- SEND / STOP BUTTON ---
                    IconButton(
                        onClick = {
                            val isSending = llmVm.inProgress

                            if (isSending) {
                                // ACTION: Stop the current process
                                llmVm.cancelInference()
                            } else {
                                // ACTION: Send a new message
                                if (text.isNotBlank() || attachedImage != null) {
                                    // Save image to internal storage if attached
                                    var imageUri: String? = null
                                    if (attachedImage != null) {
                                        val fileName = "img_${System.currentTimeMillis()}.jpg"
                                        val file = File(context.filesDir, fileName)
                                        try {
                                            FileOutputStream(file).use { outputStream ->
                                                attachedImage.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                                            }
                                            imageUri = Uri.fromFile(file).toString()
                                        } catch (e: Exception) {
                                            // Handle error
                                        }
                                    }

                                    // Clear previous response/error state before starting new operation
                                    llmVm.clearState()

                                    // Add user message with image URI if available
                                    messages.add(Message(text.trim(), false, imageUri = imageUri))

                                    // Build conversation history from previous messages
                                    val conversationHistory = messages
                                        .filter { !it.usedForContext }
                                        .take(6) // Limit context to last 6 messages
                                        .map { if (it.isBot) "Assistant: ${it.text}" else "User: ${it.text}" }

                                    // Mark messages as used for context
                                    messages.forEach { message ->
                                        if (!message.usedForContext) {
                                            val index = messages.indexOf(message)
                                            if (index >= 0) {
                                                messages[index] = message.copy(usedForContext = true)
                                            }
                                        }
                                    }

                                    // Define the response handler (used for both text and image)
                                    val handleResponse: (String) -> Unit = { response ->
                                        // Use the LLM's error state if the response is empty (e.g., due to cancellation)
                                        val finalResponse = if (response.isNotEmpty()) {
                                            response
                                        } else {
                                            llmVm.error ?: "Sorry, I couldn't generate a response."
                                        }

                                        messages.add(Message(finalResponse, true))

                                        llmVm.consumeLatestBenchmarkResult()?.let { stats ->
                                            val lastIndex = messages.lastIndex
                                            if (lastIndex >= 0 && messages[lastIndex].isBot) {
                                                messages[lastIndex] =
                                                    messages[lastIndex].copy(benchmarkStats = stats)
                                            }
                                        }

                                        // **IMPORTANT**: Clear the error state *after* adding it to the message list
                                        llmVm.clearState()
                                    }

                                    // Use LLM for response with conversation history
                                    if (attachedImage != null) {
                                        llmVm.describeImage(attachedImage, text.trim(), handleResponse)
                                    } else {
                                        llmVm.respondToText(text.trim(), conversationHistory, handleResponse)
                                    }

                                    text = ""
                                    onRemoveImage()
                                }
                            }
                        },
                        // Button is always enabled to allow the Stop action
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        // SWITCH ICON: Show Stop if in progress, otherwise show Send
                        if (llmVm.inProgress) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Stop Generation",
                                tint = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message"
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            title = { Text("Camera permission needed", fontWeight = FontWeight.Bold) },
            text = {
                Text("MiraEdge needs camera access to capture photos. Please grant the permission to continue.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationaleDialog = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationaleDialog = false }) {
                    Text("Not now")
                }
            }
        )
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Enable camera permission", fontWeight = FontWeight.Bold) },
            text = {
                Text("Camera access is turned off. Open system settings and enable the camera permission for MiraEdge to use the camera feature.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDeniedDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Image preview dialog
    if (showImagePreview && attachedImage != null) {
        AlertDialog(
            onDismissRequest = { showImagePreview = false },
            title = { Text("Image Preview", fontWeight = FontWeight.Bold) },
            text = {
                Image(
                    bitmap = attachedImage.asImageBitmap(),
                    contentDescription = "Attached image preview",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            },
            confirmButton = {
                Button(onClick = { showImagePreview = false }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onThemeToggle: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    llmVm: LlmViewModel
) {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* No-op: download continues regardless of permission state. */ }
    var huggingFaceToken by rememberSaveable {
        mutableStateOf(
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("huggingface_token", "")
                ?: ""
        )
    }
    var showTokenSavedMessage by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var recommendedModel by remember { mutableStateOf("") }
    var recommendationExplanation by remember { mutableStateOf("") }
    
    // Load model recommendation on screen init
    LaunchedEffect(Unit) {
        val (model, explanation) = ModelRecommendationEngine.recommendBestModelWithDetails(context)
        recommendedModel = model
        recommendationExplanation = explanation
    }

    // Use the actual path where the model is expected to be downloaded
    val modelFilePath = remember { File(context.filesDir, "llm/gemma-3n-E2B-it-int4.task").absolutePath }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar with back button
        TopAppBar(
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )

        // Settings content
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Model management section promoted to the top
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Model Management",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val isDownloading = llmVm.isDownloading
                    val isPaused = llmVm.isDownloadPaused
                    val downloadProgress = llmVm.downloadProgress
                    val downloadComplete = llmVm.downloadComplete
                    val needsInitialization = llmVm.needsInitialization
                    val preparingModel = llmVm.preparing
                    val modelReady = llmVm.isModelReady
                    val viewModelError = llmVm.error
                    val minModelBytes = 1_000_000_000L
                    val modelFileExists = run {
                        fun valid(file: File) = file.exists() && file.length() >= minModelBytes
                        valid(File(modelFilePath)) || valid(File(llmVm.currentModelPath))
                    }

                    when {
                        isDownloading -> {
                            Text(
                                "Downloading model...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = downloadProgress.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            val pct = (downloadProgress * 100).toInt().coerceIn(0, 100)
                            Text("Progress: ${pct}%", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { llmVm.pauseDownload() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Pause Download", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { llmVm.cancelDownload() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Cancel Download", fontWeight = FontWeight.Bold)
                            }
                        }
                        isPaused -> {
                            Text(
                                "Download paused.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = downloadProgress.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                            val pct = (downloadProgress * 100).toInt().coerceIn(0, 100)
                            Text("Progress: ${pct}%", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { llmVm.resumeDownload() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Resume Download", fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { llmVm.cancelDownload() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Cancel Download", fontWeight = FontWeight.Bold)
                            }
                        }
                        !modelFileExists -> {
                            Text(
                                "Model not found. Download the required model (~3.2 GB) to enable LLM responses.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasPermission) {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        }
                                    }

                                    val downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task"
                                    val fileName = "gemma-3n-E2B-it-int4.task"
                                    llmVm.downloadModel(downloadUrl, fileName, huggingFaceToken)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = huggingFaceToken.isNotBlank()
                            ) {
                                Text("Download Model (~3.2 GB)", fontWeight = FontWeight.Bold)
                            }
                            if (huggingFaceToken.isBlank()) {
                                Text(
                                    "Please enter a Hugging Face token to download the model",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        else -> {
                            Text(
                                "Model file detected. You can start chatting immediately.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            when {
                                preparingModel -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Initializing model...",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                needsInitialization -> {
                                    Text(
                                        "Model downloaded. Initialize to start using it.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { llmVm.initializeModel() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Initialize Model", fontWeight = FontWeight.Bold)
                                    }
                                }
                                modelReady -> {
                                    Text(
                                        "Model is ready to use.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                downloadComplete -> {
                                    Text(
                                        "Finalizing model setup...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Delete Downloaded Model", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (!viewModelError.isNullOrBlank() && !isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            viewModelError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hugging Face Token Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Hugging Face Token",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter your Hugging Face token to download models",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = huggingFaceToken,
                        onValueChange = { huggingFaceToken = it },
                        label = { Text("Hugging Face Token", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            llmVm.updateToken(huggingFaceToken)
                            showTokenSavedMessage = true
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save Token", fontWeight = FontWeight.Bold)
                    }

                    if (showTokenSavedMessage) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Token saved successfully!",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Hide the message after 3 seconds
                        LaunchedEffect(showTokenSavedMessage) {
                            delay(3000)
                            showTokenSavedMessage = false
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Theme toggle section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Dark Theme",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = onThemeToggle
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Model Recommendation Section - Permanently Displayed
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Recommended Model",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (recommendedModel.isNotBlank()) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = recommendedModel,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "This model is best for your mobile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (recommendationExplanation.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = recommendationExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Display both models for reference
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ElevatedCard(
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    width = if (recommendedModel == "gemma-E2b") 2.dp else 1.dp,
                                    color = if (recommendedModel == "gemma-E2b") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (recommendedModel == "gemma-E2b") MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "gemma-E2b",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "RAM: 3GB • Storage: 1GB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (recommendedModel == "gemma-E2b") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "✓ Selected",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        ElevatedCard(
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    width = if (recommendedModel == "gemma-E4b") 2.dp else 1.dp,
                                    color = if (recommendedModel == "gemma-E4b") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (recommendedModel == "gemma-E4b") MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "gemma-E4b",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "RAM: 6GB • Storage: 2GB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (recommendedModel == "gemma-E4b") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "✓ Selected",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = {
                    Text(
                        "Delete downloaded model?",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "This will permanently remove the downloaded model file from your device. " +
                            "You can download it again later, but it will take time and data. " +
                            "Are you sure you want to delete it?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            llmVm.deleteModel()
                        }
                    ) {
                        Text(
                            "Delete",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun CircleOverlay(
    boxSize: IntSize,
    imageBounds: Rect,
    center: Offset,
    radius: Float,
    onChange: (Offset, Float) -> Unit
) {
    val density = LocalDensity.current
    val strokePx = 3f
    val handleRadiusPx = with(density) { 20f }
    val handleGrabRadiusPx = with(density) { 36f }
    val minRadiusPx = with(density) { 24f }

    var currentCenter by remember { mutableStateOf(center) }
    var currentRadius by remember { mutableStateOf(radius) }

    val latestCenter by rememberUpdatedState(center)
    val latestRadius by rememberUpdatedState(radius)
    LaunchedEffect(latestCenter) { currentCenter = latestCenter }
    LaunchedEffect(latestRadius) { currentRadius = latestRadius }

    var isResizing by remember { mutableStateOf(false) }
    var isMoving by remember { mutableStateOf(false) }

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .pointerInput(boxSize, imageBounds) {
                detectDragGestures(
                    onDragStart = { down ->
                        val handleCenter = Offset(currentCenter.x + currentRadius, currentCenter.y)
                        val distToHandle = distance(down, handleCenter)

                        if (distToHandle <= handleGrabRadiusPx) {
                            isResizing = true
                            isMoving = false
                            return@detectDragGestures
                        }

                        if (distance(down, currentCenter) <= currentRadius) {
                            isResizing = false
                            isMoving = true
                        } else {
                            isResizing = false
                            isMoving = false
                        }
                    },
                    onDrag = { change, drag ->
                        change.consumeAllChanges()
                        if (boxSize == IntSize.Zero) return@detectDragGestures

                        if (isResizing) {
                            val newR = distance(currentCenter, change.position)
                            val clampedR = clampRadiusToRect(newR, currentCenter, imageBounds, minRadiusPx)
                            currentRadius = clampedR
                            onChange(currentCenter, currentRadius)
                        } else if (isMoving) {
                            val newCenter = Offset(currentCenter.x + drag.x, currentCenter.y + drag.y)
                            val clampedC = clampCenterToRect(newCenter, currentRadius, imageBounds)
                            currentCenter = clampedC
                            onChange(currentCenter, currentRadius)
                        }
                    },
                    onDragEnd = {
                        isResizing = false
                        isMoving = false
                    },
                    onDragCancel = {
                        isResizing = false
                        isMoving = false
                    }
                )
            }
            .fillMaxSize()
    ) {
        drawRect(color = ComposeColor.Black.copy(alpha = 0.10f))
        drawRect(color = ComposeColor.Black.copy(alpha = 0.25f), topLeft = imageBounds.topLeft, size = imageBounds.size)

        drawCircle(
            color = ComposeColor.Cyan.copy(alpha = 0.15f),
            radius = currentRadius,
            center = currentCenter
        )
        drawCircle(
            color = ComposeColor.Cyan,
            radius = currentRadius,
            center = currentCenter,
            style = Stroke(width = strokePx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
        )
        drawCircle(
            color = ComposeColor.Cyan,
            radius = handleRadiusPx,
            center = Offset(currentCenter.x + currentRadius, currentCenter.y)
        )
    }
}

private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)

private fun clampCenterToRect(center: Offset, r: Float, rect: Rect): Offset {
    val minX = rect.left + r
    val maxX = rect.right - r
    val minY = rect.top + r
    val maxY = rect.bottom - r
    val cx = center.x.coerceIn(minX, maxX)
    val cy = center.y.coerceIn(minY, maxY)
    return Offset(cx, cy)
}

private fun clampRadiusToRect(r: Float, center: Offset, rect: Rect, minR: Float): Float {
    val maxR = min(
        min(center.x - rect.left, rect.right - center.x),
        min(center.y - rect.top, rect.bottom - center.y)
    )
    return r.coerceIn(minR, maxR)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScreen(
    imageBitmap: Bitmap,
    onCropComplete: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var imageBounds by remember { mutableStateOf(Rect.Zero) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var radius by remember { mutableStateOf(120f) }

    val canCrop = imageBounds.width > 0f &&
            imageBounds.height > 0f &&
            radius >= 8f &&
            center.x - radius >= imageBounds.left &&
            center.x + radius <= imageBounds.right &&
            center.y - radius >= imageBounds.top &&
            center.y + radius <= imageBounds.bottom

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Circle selector", fontWeight = FontWeight.Bold) }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { sz ->
                    boxSize = sz
                    imageBounds = computeFitBounds(sz, imageBitmap.width, imageBitmap.height)
                    if (center == Offset.Zero) {
                        center = imageBounds.center
                        radius = min(imageBounds.width, imageBounds.height) * 0.25f
                    }
                }
        ) {
            Image(
                bitmap = imageBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            CircleOverlay(
                boxSize = boxSize,
                imageBounds = imageBounds,
                center = center,
                radius = radius,
                onChange = { c, r -> center = c; radius = r }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Button(
                onClick = { onCropComplete(imageBitmap) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    "Use Full Image",
                    maxLines = 1
                )
            }

            Button(
                onClick = {
                    val out = cropCircleFromBitmap(
                        source = imageBitmap,
                        imageBoundsInView = imageBounds,
                        circleCenterInView = center,
                        circleRadiusInView = radius
                    )
                    onCropComplete(out)
                },
                enabled = canCrop,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    if (canCrop) "Crop Selected Area" else "Adjust Circle",
                    maxLines = 1
                )
            }
        }
    }
}

fun computeFitBounds(viewSize: IntSize, imgW: Int, imgH: Int): Rect {
    val viewW = viewSize.width.toFloat()
    val viewH = viewSize.height.toFloat()
    val imgAspect = imgW.toFloat() / imgH.toFloat()
    val viewAspect = viewW / viewH

    val fittedW: Float
    val fittedH: Float
    if (imgAspect > viewAspect) {
        fittedW = viewW
        fittedH = viewW / imgAspect
    } else {
        fittedH = viewH
        fittedW = viewH * imgAspect
    }

    val left = (viewW - fittedW) / 2f
    val top = (viewH - fittedH) / 2f
    return Rect(left, top, left + fittedW, top + fittedH)
}

/**
 * Create a circular crop of the source bitmap given a circle specified in *view* coordinates.
 *
 * - imageBoundsInView: rectangle where the image is drawn in the view (same coordinate space as circleCenterInView)
 * - circleCenterInView / circleRadiusInView: circle in view coordinates
 *
 * Returns a square ARGB_8888 bitmap of size (radius*2).
 */
fun cropCircleFromBitmap(
    source: Bitmap,
    imageBoundsInView: Rect,
    circleCenterInView: Offset,
    circleRadiusInView: Float
): Bitmap {
    val softwareBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    // Output size in pixels (based on view radius scaled to source using same scale used below)
    val scaleX = softwareBitmap.width.toFloat() / imageBoundsInView.width
    val scaleY = softwareBitmap.height.toFloat() / imageBoundsInView.height
    val scale = min(scaleX, scaleY)

    val bitmapRadius = circleRadiusInView * scale
    val outputSize = (bitmapRadius * 2f).toInt().coerceAtLeast(1)

    // Circle center in bitmap coordinates
    val bitmapCenterX = (circleCenterInView.x - imageBoundsInView.left) * scale
    val bitmapCenterY = (circleCenterInView.y - imageBoundsInView.top) * scale

    // Source rect area to sample (rounded and clamped)
    val left = (bitmapCenterX - bitmapRadius).toInt().coerceAtLeast(0)
    val top = (bitmapCenterY - bitmapRadius).toInt().coerceAtLeast(0)
    val right = (bitmapCenterX + bitmapRadius).toInt().coerceAtMost(softwareBitmap.width)
    val bottom = (bitmapCenterY + bitmapRadius).toInt().coerceAtMost(softwareBitmap.height)

    val srcRect = AndroidRect(left, top, right, bottom)
    val destRect = AndroidRect(0, 0, outputSize, outputSize)

    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawARGB(0, 0, 0, 0)

    // Paint for drawing opaque circle mask
    val maskPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
    }
    // Draw circle (dest) as mask (opaque)
    canvas.drawCircle(outputSize / 2f, outputSize / 2f, outputSize / 2f, maskPaint)

    // Now draw bitmap with SRC_IN so only the circle area remains
    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    canvas.drawBitmap(softwareBitmap, srcRect, destRect, paint)

    // Clear xfermode (best practice)
    paint.xfermode = null

    return output
}

@Composable
fun ChatBubble(message: Message, context: Context) {
    val bubbleColor = if (message.isBot) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer
    val textColor = if (message.isBot) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer
    val shape = if (message.isBot)
        RoundedCornerShape(topEnd = 18.dp, topStart = 6.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    else
        RoundedCornerShape(topEnd = 6.dp, topStart = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isBot) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        if (message.isBot) {
            val logoPainter = rememberAsyncImagePainter(
                ImageRequest.Builder(context)
                    .data("file:///android_asset/mirafra_logo.svg")
                    .decoderFactory(SvgDecoder.Factory())
                    .build()
            )

            Image(
                painter = logoPainter,
                contentDescription = "Trademark Logo",
                modifier = Modifier
                    .size(34.dp)
                    .padding(top = 1.dp, end = 10.dp)
            )
        }

        Column(horizontalAlignment = if (message.isBot) Alignment.Start else Alignment.End) {
            Surface(
                shape = shape,
                color = bubbleColor,
                tonalElevation = if (message.isBot) 2.dp else 4.dp,
                modifier = Modifier
                    .widthIn(max = 260.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (message.isBot) {
                        Text(
                            text = "Assistant",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Display image if available
                    message.imageUri?.let { uriString ->
                        val bitmap = remember(uriString) {
                            try {
                                val uri = Uri.parse(uriString)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                                    ImageDecoder.decodeBitmap(source)
                                } else {
                                    @Suppress("DEPRECATION")
                                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }

                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            Text(
                                text = "[Image could not be loaded]",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // Display message text
                    if (message.text.isNotBlank()) {
                        val trimmedText = remember(message.text) { message.text.trimEnd() }
                        if (trimmedText.isNotBlank()) {
                            val styledText = remember(trimmedText) { parseSimpleMarkdown(trimmedText) }
                            Text(
                                text = styledText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor
                            )
                        }
                    }
                }
            }

            if (message.isBot) {
                message.benchmarkStats?.let { stats ->
                    var showStats by remember(message.timestamp, stats) { mutableStateOf(false) }

                    Spacer(modifier = Modifier.height(4.dp))

                    val latency = String.format(Locale.US, "%.2f", stats.latency)
                    val firstToken = String.format(Locale.US, "%.2f", stats.timeToFirstToken)
                    val prefill = String.format(Locale.US, "%.1f", stats.prefillSpeed)
                    val decode = String.format(Locale.US, "%.1f", stats.decodeSpeed)

                    Row(
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$latency s",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        TextButton(
                            onClick = { showStats = !showStats },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (showStats) "Hide stats" else "Show stats",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showStats,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .padding(top = 8.dp)
                    ) {
                        Surface(
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val acceleratorLabel = (stats.accelerator?.ifBlank { null }
                                    ?: "CPU").uppercase(Locale.US)
                                Text(
                                    text = "Stats on $acceleratorLabel",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )

                                val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                val valueStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val statsItems = listOf(
                                    "1st token" to "$firstToken s",
                                    "Prefill speed" to "$prefill tok/s",
                                    "Decode speed" to "$decode tok/s",
                                    "Latency" to "$latency s"
                                )

                                statsItems.forEach { (label, value) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = labelStyle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                        Text(
                                            text = value,
                                            style = valueStyle,
                                            maxLines = 1,
                                            textAlign = TextAlign.End
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val BoldMarkdownRegex = Regex("""\*\*(.+?)\*\*""", setOf(RegexOption.DOT_MATCHES_ALL))

private fun parseSimpleMarkdown(text: String): AnnotatedString {
    if (!text.contains("**")) return AnnotatedString(text)

    return buildAnnotatedString {
        var currentIndex = 0
        for (match in BoldMarkdownRegex.findAll(text)) {
            val matchStart = match.range.first
            val matchEnd = match.range.last + 1

            if (matchStart > currentIndex) {
                append(text.substring(currentIndex, matchStart))
            }

            val boldText = match.groupValues.getOrNull(1) ?: ""
            val spanStart = length
            append(boldText)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), spanStart, length)

            currentIndex = matchEnd
        }

        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

package com.andrip.browser.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.andrip.browser.R
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import com.andrip.browser.detect.MediaKind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.andrip.browser.browser.BrowserViewModel
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.log.AppLog
import com.andrip.browser.download.Notifications
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.andrip.browser.util.UrlInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Screen { BROWSER, DOWNLOADS, LOGS }

@Composable
fun AndRipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        dark -> darkColorScheme(primary = Color(0xFF8DD9CC), secondary = Color(0xFFB6CCC6), background = Color(0xFF101816), surface = Color(0xFF101816))
        else -> lightColorScheme(primary = Color(0xFF006B5E), secondary = Color(0xFF4C635D), background = Color(0xFFF5FAF6), surface = Color(0xFFF5FAF6))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndRipScreen(vm: BrowserViewModel, webView: WebView) {
    var screen by rememberSaveable { mutableStateOf(Screen.BROWSER) }
    var showVideos by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var preview by remember { mutableStateOf<DetectedMedia?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val downloads by vm.downloads.collectAsState()
    LaunchedEffect(vm.openDownloads) {
        if (vm.openDownloads) {
            screen = Screen.DOWNLOADS
            vm.openDownloads = false
        }
    }
    LaunchedEffect(vm.queueMessage) {
        vm.queueMessage?.let { snackbar.showSnackbar(it); vm.queueMessage = null }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.queueMessage = "Progress notifications are off. Enable them from Downloads."
    }

    BackHandler(enabled = screen != Screen.BROWSER || vm.canGoBack) {
        if (screen != Screen.BROWSER) screen = Screen.BROWSER else webView.goBack()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showVideos = true }, modifier = Modifier.weight(1f)) {
                            Text(if (vm.detected.isEmpty()) "Find media" else "Media · ${vm.detected.size}")
                        }
                        TextButton(onClick = { screen = Screen.DOWNLOADS }, modifier = Modifier.weight(1f)) {
                            val active = downloads.count { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }
                            Text(if (active > 0) "Downloads · $active" else "Downloads")
                        }
                    }
                }
            },
            topBar = {
                BrowserBar(
                    vm = vm,
                    webView = webView,
                    onShowVideos = { showVideos = true },
                    onOpen = { screen = it },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(vm.pageError != null) {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Connection needs attention", style = MaterialTheme.typography.titleMedium)
                            Text(vm.pageError.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { webView.reload() }) { Text("Try again") }
                                TextButton(onClick = {
                                    try { com.andrip.browser.browser.ExternalBrowser.open(context, vm.url) }
                                    catch (_: ActivityNotFoundException) { Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show() }
                                }) { Text("Open browser") }
                            }
                        }
                    }
                }
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }

        when (screen) {
            Screen.DOWNLOADS -> DownloadsScreen(vm, onBack = { screen = Screen.BROWSER }, onSource = { url -> webView.loadUrl(url); screen = Screen.BROWSER })
            Screen.LOGS -> LogsScreen(onBack = { screen = Screen.BROWSER })
            Screen.BROWSER -> Unit
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }) {
            VideoList(
                videos = vm.detected,
                onPreview = {
                    webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
                    preview = it
                },
                onDownload = { media ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.download(media)
                    showVideos = false
                },
            )
        }
    }
    preview?.let { MediaPreview(it, onDismiss = { preview = null }) }
}

@Composable
private fun BrowserBar(
    vm: BrowserViewModel,
    webView: WebView,
    onShowVideos: () -> Unit,
    onOpen: (Screen) -> Unit,
) {
    var text by remember { mutableStateOf(vm.url) }
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val pageUrl = vm.url
    val videoCount = vm.detected.size

    // Follow the page's address unless the user is in the middle of typing one.
    LaunchedEffect(pageUrl, focused) { if (!focused) text = pageUrl }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandMark()
                Text("AndRip", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { webView.goBack() }, enabled = vm.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("Search or type a URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            webView.loadUrl(UrlInput.toUrl(text))
                            focusManager.clearFocus()
                        },
                    ),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Reload") },
                            onClick = { menuOpen = false; if (webView.url == null) webView.loadUrl(vm.url) else webView.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text("Forward") },
                            enabled = vm.canGoForward,
                            onClick = { menuOpen = false; webView.goForward() },
                        )
                        DropdownMenuItem(
                            text = { Text("Home") },
                            onClick = { menuOpen = false; webView.loadUrl(UrlInput.HOME) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Downloads") },
                            onClick = { menuOpen = false; onOpen(Screen.DOWNLOADS) },
                        )
                        DropdownMenuItem(
                            text = { Text("Logs") },
                            onClick = { menuOpen = false; onOpen(Screen.LOGS) },
                        )
                    }
                }
            }
            if (vm.loading) {
                LinearProgressIndicator(
                    progress = { vm.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VideoList(videos: List<DetectedMedia>, onPreview: (DetectedMedia) -> Unit, onDownload: (DetectedMedia) -> Unit) {
    var maxHeight by rememberSaveable { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ready when you are", style = MaterialTheme.typography.headlineSmall)
            Text("Preview a source, then save media you have permission to download.", style = MaterialTheme.typography.bodyMedium)
        }
        if (videos.any { it.kind != MediaKind.FILE }) item {
            Text("Stream quality", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, 1080, 720, 480).forEach { height ->
                    FilterChip(selected = maxHeight == height, onClick = { maxHeight = height }, label = { Text(height?.let { "${it}p" } ?: "Best") })
                }
            }
            Text("Prefers this quality or lower; uses the lowest if unavailable. Audio is chosen automatically.", style = MaterialTheme.typography.bodySmall)
        }
        if (videos.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start playing on the page", style = MaterialTheme.typography.titleMedium)
                    Text("Detected audio, video and streams appear here. Some sites keep their sources private.")
                }
            }
        }
        items(videos, key = { it.url }) { media ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(media.pageTitle ?: "Media source", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val host = Uri.parse(media.url).host.orEmpty()
                    Text("${media.kind.label} · $host", style = MaterialTheme.typography.labelMedium)
                    val file = Uri.parse(media.url).lastPathSegment.orEmpty()
                    Text(file, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    if (media.protected) {
                        Text("DRM protected · Watch on the source website", color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onPreview(media) }, modifier = Modifier.weight(1f)) { Text("Preview") }
                            Button(onClick = { onDownload(media.copy(maxHeight = if (media.kind == MediaKind.FILE) null else maxHeight)) }, modifier = Modifier.weight(1f)) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(vm: BrowserViewModel, onBack: () -> Unit, onSource: (String) -> Unit) {
    val downloads by vm.downloads.collectAsState()
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf("All") }
    var notificationsEnabled by remember { mutableStateOf(Notifications.enabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = Notifications.enabled(context)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrandMark()
                        Text("Downloads")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text("Your media, at your pace", style = MaterialTheme.typography.headlineSmall)
                Text("Downloads continue when you leave AndRip.", style = MaterialTheme.typography.bodyMedium)
                var unmetered by remember { mutableStateOf(com.andrip.browser.download.DownloadPreferences.unmeteredOnly(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = unmetered, onCheckedChange = {
                        unmetered = it
                        com.andrip.browser.download.DownloadPreferences.setUnmeteredOnly(context, it)
                    })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Unmetered network only")
                        Text("Applies to new and resumed downloads", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!notificationsEnabled) {
                    Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("See your download progress", style = MaterialTheme.typography.titleMedium)
                            Text("Enable notifications for the progress bar, segment count and completion message.")
                            TextButton(onClick = {
                                try { context.startActivity(Notifications.settingsIntent(context)) }
                                catch (_: ActivityNotFoundException) { vm.queueMessage = "Open Android Settings > Apps > AndRip > Notifications." }
                            }) { Text("Enable notifications") }
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Active", "Saved", "Attention").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                    }
                }
            }
            val visible = downloads.filter {
                when (filter) {
                    "Active" -> it.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.PAUSED)
                    "Saved" -> it.status == DownloadStatus.DONE
                    "Attention" -> it.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)
                    else -> true
                }
            }
            if (visible.isEmpty()) {
                item { Text("No downloads here yet. Browse a page and tap Find media to get started.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(visible, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    onCancel = { vm.cancel(item.id) },
                    onPause = { vm.pause(item.id) },
                    onSource = { item.pageUrl?.let(onSource) },
                    onRetry = { vm.retry(item.id) },
                    onRemove = { vm.remove(item.id) },
                    onOpen = { vm.openSaved(item) { openVideo(context, item) } },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadEntity,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onSource: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val active = item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.QUEUED
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            item.fileName ?: item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when (item.status) {
            DownloadStatus.QUEUED -> item.error ?: "Waiting for network, storage or a download slot…"
            DownloadStatus.RUNNING -> item.detail.ifEmpty { "Starting…" }
            DownloadStatus.DONE -> "Saved on this device"
            DownloadStatus.PAUSED -> item.error ?: "Paused · progress kept"
            DownloadStatus.CANCELED -> "Canceled · Remove history to free partial storage"
            else -> item.error ?: "Download could not finish"
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (item.status == DownloadStatus.RUNNING) {
            Spacer(Modifier.height(6.dp))
            if (item.progress >= 0) {
                LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                active -> {
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                item.status == DownloadStatus.PAUSED -> TextButton(onClick = onRetry) { Text("Resume") }
                item.status == DownloadStatus.DONE -> TextButton(onClick = onOpen) { Text("Open") }
                else -> TextButton(onClick = onRetry) { Text(if (item.error?.startsWith("Retry saving:") == true) "Retry saving" else "Retry") }
            }
            if (item.status == DownloadStatus.FAILED && item.pageUrl != null) TextButton(onClick = onSource) { Text("Source page") }
            if (!active) TextButton(onClick = onRemove) { Text("Remove history") }
        }
    }
}

@Composable
private fun BrandMark() {
    Surface(shape = CircleShape, color = Color(0xFFF5FAF6)) {
        Image(painterResource(R.drawable.andrip_logo), contentDescription = "AndRip logo", modifier = Modifier.size(40.dp))
    }
}

private fun openVideo(context: Context, item: DownloadEntity) {
    val uri = item.outputUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), com.andrip.browser.util.FileNames.mimeFor(item.fileName?.substringAfterLast('.').orEmpty()))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        AppLog.w("UI", "No app could open $uri", e)
        Toast.makeText(context, "No video player found", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        text = withContext(Dispatchers.IO) { AppLog.tail() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { reload++ }) { Text("Refresh") }
                    TextButton(onClick = { AppLog.clear(); reload++ }) { Text("Clear") }
                    TextButton(
                        onClick = {
                            try {
                                AppLog.shareIntent(context)?.let { context.startActivity(it) }
                            } catch (e: Exception) {
                                AppLog.e("UI", "Could not share the log", e)
                                Toast.makeText(context, "Could not share the log", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text("Share") }
                },
            )
        },
    ) { padding ->
        Text(
            text = text.ifEmpty { "Log is empty." },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            softWrap = false,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}

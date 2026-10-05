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
import com.andrip.browser.util.UrlInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Screen { BROWSER, DOWNLOADS, LOGS }

@Composable
fun AndRipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndRipScreen(vm: BrowserViewModel, webView: WebView) {
    var screen by rememberSaveable { mutableStateOf(Screen.BROWSER) }
    var showVideos by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    BackHandler(enabled = screen != Screen.BROWSER || vm.canGoBack) {
        if (screen != Screen.BROWSER) screen = Screen.BROWSER else webView.goBack()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                BrowserBar(
                    vm = vm,
                    webView = webView,
                    onShowVideos = { showVideos = true },
                    onOpen = { screen = it },
                )
            },
        ) { padding ->
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }

        when (screen) {
            Screen.DOWNLOADS -> DownloadsScreen(vm, onBack = { screen = Screen.BROWSER })
            Screen.LOGS -> LogsScreen(onBack = { screen = Screen.BROWSER })
            Screen.BROWSER -> Unit
        }
    }

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }) {
            VideoList(
                videos = vm.detected,
                onDownload = { media ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.download(media)
                    Toast.makeText(context, "Added to downloads", Toast.LENGTH_SHORT).show()
                    showVideos = false
                },
            )
        }
    }
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
                TextButton(onClick = onShowVideos) {
                    Text(if (videoCount > 0) "Videos ($videoCount)" else "Videos")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Reload") },
                            onClick = { menuOpen = false; webView.reload() },
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
private fun VideoList(videos: List<DetectedMedia>, onDownload: (DetectedMedia) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item {
            Text("Videos on this page", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }
        if (videos.isEmpty()) {
            item {
                Text(
                    "Nothing found yet. Start playing the video, then open this list again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(videos, key = { it.url }) { media ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(media.kind.label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        media.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDownload(media) }) { Text("Download") }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(vm: BrowserViewModel, onBack: () -> Unit) {
    val downloads by vm.downloads.collectAsState()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
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
            if (downloads.isEmpty()) {
                item { Text("No downloads yet.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(downloads, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    onCancel = { vm.cancel(item.id) },
                    onRetry = { vm.retry(item.id) },
                    onRemove = { vm.remove(item.id) },
                    onOpen = { openVideo(context, item) },
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
            DownloadStatus.QUEUED -> "Waiting…"
            DownloadStatus.RUNNING -> item.detail.ifEmpty { "Starting…" }
            DownloadStatus.DONE -> "Saved to Movies/AndRip"
            DownloadStatus.CANCELED -> "Canceled"
            else -> "Failed: ${item.error ?: "unknown error"} (details in Logs)"
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
                active -> TextButton(onClick = onCancel) { Text("Cancel") }
                item.status == DownloadStatus.DONE -> TextButton(onClick = onOpen) { Text("Open") }
                else -> TextButton(onClick = onRetry) { Text("Retry") }
            }
            if (!active) TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

private fun openVideo(context: Context, item: DownloadEntity) {
    val uri = item.outputUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), "video/*")
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

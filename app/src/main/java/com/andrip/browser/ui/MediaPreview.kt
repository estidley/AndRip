package com.andrip.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.download.Http
import com.andrip.browser.download.HttpPolicy
import com.andrip.browser.download.RequestHeaders

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MediaPreview(media: DetectedMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(media.url) { mutableStateOf<String?>(null) }
    var buffering by remember(media.url) { mutableStateOf(true) }
    val player = remember(media) {
        val headers = RequestHeaders(media.referer, media.userAgent, media.url, media.headers)
        val client = Http.client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().tag(RequestHeaders::class.java, headers).build())
        }.build()
        val factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
                override fun onPlayerError(e: PlaybackException) {
                    buffering = false
                    error = if (e.errorCode in 6000..6999) "This media is DRM-protected. Open the source website to watch it."
                    else "Preview could not play this source. Its link may have expired, access may be restricted, or the device may not support its codec."
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if (tracks.groups.any { group -> (0 until group.length).any { group.getTrackFormat(it).drmInitData != null } }) {
                        stop()
                        error = "This media is DRM-protected. Open the source website to watch it."
                    }
                }
            })
            if (!media.protected) {
                val mime = when (media.kind) {
                    MediaKind.HLS -> MimeTypes.APPLICATION_M3U8
                    MediaKind.DASH -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
                setMediaItem(MediaItem.Builder().setUri(media.url).setMimeType(mime).build())
                prepare()
                playWhenReady = true
            } else { buffering = false; error = "This media is DRM-protected and cannot be previewed here." }
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(media.pageTitle ?: "Preview", style = MaterialTheme.typography.titleLarge, maxLines = 2)
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 0 } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(error ?: "Drag the timeline to seek. Live sources can seek only within the available window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Back to media") }
            }
        }
    }
}

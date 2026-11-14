package com.infinitum.aether.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.net.Uri
import com.infinitum.aether.data.MediaFile
import com.infinitum.aether.data.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val FILES_AUTHORITY = "com.infinitum.aether.files"
private const val MAX_VOICE_MS = 5 * 60 * 1000
private const val MIN_VOICE_MS = 700L

// ---- Attach menu and voice recording ------------------------------------------------

/**
 * "Attach" opens Photo (system photo picker) or File (document picker); "Hold to talk" records a
 * voice message while pressed (AAC mono 32 kbps .m4a, at most 5 minutes). Slide left to cancel.
 */
@Composable
internal fun AttachButton(onPicked: (Uri) -> Unit, onVoice: (java.io.File, Long) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val picked by rememberUpdatedState(onPicked)
    val voice by rememberUpdatedState(onVoice)
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(picked) }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(picked) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Toast.makeText(context, "Microphone allowed. Hold to record.", Toast.LENGTH_SHORT).show()
    }
    val rec = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val cancelPx = with(LocalDensity.current) { 96.dp.toPx() }

    DisposableEffect(Unit) { onDispose { rec.cancel() } }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = rec.elapsed()
            if (!rec.active) recording = false
            delay(200)
        }
    }
    rec.onMaxReached = { file, ms -> recording = false; voice(file, ms) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (recording) {
            Text(
                (if (cancelling) "Release to cancel" else "Recording ${clock(elapsed)}  ·  slide left to cancel"),
                style = MaterialTheme.typography.labelLarge,
                color = Alert,
                modifier = Modifier.testTag("voice-status"),
            )
        } else {
            Box {
                BarAction("Attach", "attach") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Paper)) {
                    DropdownMenuItem(
                        text = { Text("Photo", style = MaterialTheme.typography.labelLarge) },
                        onClick = {
                            menu = false
                            photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.testTag("attach-photo"),
                    )
                    DropdownMenuItem(
                        text = { Text("File", style = MaterialTheme.typography.labelLarge) },
                        onClick = { menu = false; doc.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("attach-file"),
                    )
                }
            }
        }
        Text(
            if (recording) "● REC" else "Hold to talk",
            style = MaterialTheme.typography.labelLarge,
            color = if (recording) Alert else Ink,
            modifier = Modifier
                .testTag("voice-record")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                            return@awaitEachGesture
                        }
                        if (!rec.start()) {
                            Toast.makeText(context, "Could not start recording", Toast.LENGTH_SHORT).show()
                            return@awaitEachGesture
                        }
                        recording = true
                        cancelling = false
                        var cancel = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            cancel = down.position.x - c.position.x > cancelPx
                            cancelling = cancel
                            if (!c.pressed) break
                            c.consume()
                        }
                        recording = false
                        cancelling = false
                        if (cancel) rec.cancel() else rec.stop()?.let { (f, ms) -> voice(f, ms) }
                    }
                }
                .padding(vertical = 8.dp),
        )
    }
}

/** One MediaRecorder session writing to the app-private voice cache. */
private class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    var onMaxReached: (File, Long) -> Unit = { _, _ -> }
    val active get() = recorder != null

    fun elapsed() = if (recorder == null) 0L else System.currentTimeMillis() - startedAt

    fun start(): Boolean {
        cancel()
        val dir = File(context.cacheDir, MediaStore.RECORD_DIR).apply { mkdirs() }
        val f = File(dir, "voice-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioSamplingRate(22_050)
            r.setMaxDuration(MAX_VOICE_MS)
            r.setOutputFile(f.path)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop()?.let { (ff, ms) -> onMaxReached(ff, ms) }
            }
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            r.release()
            f.delete()
            false
        }
    }

    /** Stops and returns the recording and its length, or null if it was too short or failed. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file ?: return null
        val ms = System.currentTimeMillis() - startedAt
        recorder = null
        file = null
        val ok = try { r.stop(); true } catch (e: RuntimeException) { false } finally { r.release() }
        if (!ok || ms < MIN_VOICE_MS || f.length() == 0L) { f.delete(); return null }
        return f to ms.coerceAtMost(MAX_VOICE_MS.toLong())
    }

    fun cancel() {
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        file?.delete()
        file = null
    }
}

// ---- Bubbles ------------------------------------------------------------------------

/** A photo, voice message or file inside a chat bubble, with transfer progress. */
@Composable
internal fun MediaBubble(file: MediaFile, media: MediaStore) {
    Column(Modifier.widthIn(max = 300.dp).testTag("media-${file.id}")) {
        when {
            file.state == MediaFile.State.READY && file.isImage -> ImagePreview(file, media)
            file.state == MediaFile.State.READY && file.isAudio -> VoicePlayer(file, media)
            file.isAudio && file.state == MediaFile.State.FAILED && file.received == 0L -> FileRow(file, media, openable = false)
            else -> FileRow(file, media, openable = file.state != MediaFile.State.RECEIVING && !(file.state == MediaFile.State.FAILED && file.received == 0L))
        }
        TransferStatus(file)
    }
}

@Composable
private fun TransferStatus(file: MediaFile) {
    when (file.state) {
        MediaFile.State.SENDING, MediaFile.State.RECEIVING -> {
            val p = if (file.size > 0) (file.received.toFloat() / file.size).coerceIn(0f, 1f) else 0f
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { p },
                color = Accent,
                trackColor = Wash,
                modifier = Modifier.fillMaxWidth().height(3.dp).testTag("media-progress"),
            )
            Kicker((if (file.state == MediaFile.State.SENDING) "Sending" else "Receiving") + " ${(p * 100).toInt()}%", Modifier.padding(top = 4.dp))
        }
        MediaFile.State.FAILED -> Kicker("Transfer failed", Modifier.padding(top = 4.dp).testTag("media-failed"), color = Alert)
        MediaFile.State.READY -> {}
    }
}

@Composable
private fun ImagePreview(file: MediaFile, media: MediaStore) {
    val thumb by produceState<ImageBitmap?>(null, file.id) { value = withContext(Dispatchers.IO) { media.thumbnail(file.id) } }
    var full by remember { mutableStateOf(false) }
    val img = thumb
    if (img == null) {
        Box(Modifier.size(200.dp, 140.dp).background(Wash).border(1.dp, Rule), contentAlignment = Alignment.Center) {
            Kicker("Photo")
        }
    } else {
        Image(
            img,
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 300.dp).heightIn(max = 320.dp).border(1.dp, Rule)
                .clickable { full = true }.testTag("media-image"),
        )
    }
    if (full) ImageViewer(file, media) { full = false }
}

@Composable
private fun ImageViewer(file: MediaFile, media: MediaStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val big by produceState<ImageBitmap?>(null, file.id) { value = withContext(Dispatchers.IO) { media.image(file.id, 2048) } }
    val save = rememberSaveLauncher(file, media)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink).testTag("media-viewer")) {
            Row(
                Modifier.fillMaxWidth().background(Paper).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction("Close", "viewer-close", onClose)
                Spacer(Modifier.weight(1f))
                BarAction("Save", "viewer-save") { save.launch(file.name) }
                BarAction("Share", "viewer-share") { scope.launch { share(context, media, file) } }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                big?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    ?: Kicker("Opening", color = Paper)
            }
        }
    }
}

@Composable
private fun VoicePlayer(file: MediaFile, media: MediaStore) {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(file.durationMs?.toInt() ?: 0) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(file.id) { onDispose { player?.release(); player = null } }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { pos = runCatching { it.currentPosition }.getOrDefault(pos) }
            delay(100)
        }
    }
    val frac = if (length > 0) (pos.toFloat() / length).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.border(1.dp, Rule).padding(horizontal = 12.dp, vertical = 10.dp).widthIn(min = 220.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (playing) "Pause" else "Play",
            style = MaterialTheme.typography.labelLarge,
            color = Accent,
            modifier = Modifier.testTag("voice-play").clickable {
                val p = player
                when {
                    playing && p != null -> { p.pause(); playing = false }
                    p != null -> { p.start(); playing = true }
                    else -> scope.launch {
                        val f = media.open(file.id) ?: run { failed = true; return@launch }
                        val mp = MediaPlayer()
                        try {
                            withContext(Dispatchers.IO) { mp.setDataSource(f.path); mp.prepare() }
                            mp.setOnCompletionListener { playing = false; pos = 0; it.seekTo(0) }
                            length = mp.duration.takeIf { it > 0 } ?: length
                            mp.start()
                            player = mp
                            playing = true
                        } catch (e: Exception) {
                            mp.release()
                            failed = true
                        }
                    }
                }
            }.padding(vertical = 4.dp),
        )
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(progress = { frac }, color = Ink, trackColor = Wash, modifier = Modifier.fillMaxWidth().height(3.dp))
            Spacer(Modifier.height(6.dp))
            Kicker(if (failed) "Cannot play" else "Voice  ${clock(pos.toLong())} / ${clock(length.toLong())}", color = if (failed) Alert else Body)
        }
    }
}

@Composable
private fun FileRow(file: MediaFile, media: MediaStore, openable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberSaveLauncher(file, media)
    Column(Modifier.border(1.dp, Rule).padding(horizontal = 12.dp, vertical = 10.dp).widthIn(min = 220.dp)) {
        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Kicker("${file.mime.substringAfter('/').take(12)}  ·  ${sizeLabel(file.size)}", Modifier.padding(top = 2.dp))
        if (openable) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BarAction("Open", "file-open") { scope.launch { view(context, media, file) } }
                BarAction("Save", "file-save") { save.launch(file.name) }
                BarAction("Share", "file-share") { scope.launch { share(context, media, file) } }
            }
        }
    }
}

@Composable
private fun rememberSaveLauncher(file: MediaFile, media: MediaStore) = run {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        if (uri != null) scope.launch {
            val ok = media.saveTo(file.id, uri)
            Toast.makeText(context, if (ok) "Saved" else "Could not save", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun shareUri(context: Context, media: MediaStore, file: MediaFile): Uri? {
    val f = media.open(file.id) ?: return null
    return runCatching { FileProvider.getUriForFile(context, FILES_AUTHORITY, f) }.getOrNull()
}

private suspend fun view(context: Context, media: MediaStore, file: MediaFile) {
    val uri = shareUri(context, media, file) ?: return toast(context, "Cannot open this file")
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(i) } catch (e: ActivityNotFoundException) { toast(context, "No app can open this file") }
}

private suspend fun share(context: Context, media: MediaStore, file: MediaFile) {
    val uri = shareUri(context, media, file) ?: return toast(context, "Cannot share this file")
    val i = Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        toast(context, "No app can share this file")
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun sizeLabel(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / (1024.0 * 1024))
}

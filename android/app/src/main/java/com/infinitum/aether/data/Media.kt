package com.infinitum.aether.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.aether_core.SocialEvent
import uniffi.aether_core.SocialKind
import uniffi.aether_core.Vault
import uniffi.aether_core.socialChunkCount
import uniffi.aether_core.socialEvent
import uniffi.aether_core.socialValidate
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One photo, file or voice message in ÆTHER Talk™, sent or received. */
data class MediaFile(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    /** Bytes received (incoming) or handed to the mesh (outgoing) so far. */
    val received: Long,
    val state: State,
    val durationMs: Long?,
) {
    enum class State { SENDING, RECEIVING, READY, FAILED }

    val isImage get() = mime.startsWith("image/")
    val isAudio get() = mime.startsWith("audio/")
}

/**
 * ÆTHER Talk™ attachments. Metadata lives in an encrypted ÆTHER Vault™ (append-only JSON
 * events); file bytes live in `noBackupFilesDir/aether/media`, sealed with AES-256-GCM per
 * 32 KiB segment under a random media key that is itself stored only inside the vault.
 * One segment = one wire chunk, so sending re-reads a segment per [SocialKind.FILE_CHUNK].
 * Nothing here touches the network or logs names or contents.
 */
class MediaStore(context: Context, private val vault: Vault) {
    private val app = context.applicationContext
    private val dir = File(app.noBackupFilesDir, "aether/media").apply { mkdirs() }
    private val openDir = File(app.cacheDir, OPEN_DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val rng = SecureRandom()
    private val key: SecretKeySpec

    private class Meta(var file: MediaFile, val outgoing: Boolean, val convo: String, val sha256: String, var complete: Boolean)
    private class Partial(val convo: String, val total: Int, var next: Int, val digest: MessageDigest, val out: SegmentWriter, var last: Long)

    private val metas = HashMap<String, Meta>()
    private val partials = HashMap<String, Partial>()
    private val thumbs = LruCache<String, ImageBitmap>(32)

    private val _files = MutableStateFlow<Map<String, MediaFile>>(emptyMap())
    val files: StateFlow<Map<String, MediaFile>> = _files.asStateFlow()

    init {
        // Decrypted copies opened for viewing or sharing never outlive one app run.
        openDir.deleteRecursively()
        File(app.cacheDir, RECORD_DIR).deleteRecursively()
        File(app.cacheDir, TMP_DIR).deleteRecursively()
        var k: ByteArray? = null
        for (raw in vault.readAll()) {
            val o = runCatching { JSONObject(raw.decodeToString()) }.getOrNull() ?: continue
            when (o.optString("t")) {
                "key" -> if (k == null) k = Base64.getDecoder().decode(o.getString("k"))
                "file" -> {
                    val id = o.getString("id")
                    val st = runCatching { MediaFile.State.valueOf(o.getString("st")) }.getOrDefault(MediaFile.State.FAILED)
                    val f = MediaFile(
                        id, o.getString("n"), o.getString("m"), o.getLong("s"),
                        if (st == MediaFile.State.READY) o.getLong("s") else 0L, st,
                        if (o.has("d")) o.getLong("d") else null,
                    )
                    metas[id] = Meta(f, o.optBoolean("o"), o.optString("c"), o.optString("h"), o.optBoolean("x"))
                }
                "del" -> metas.remove(o.getString("id"))
            }
        }
        if (k == null) {
            k = ByteArray(32).also { rng.nextBytes(it) }
            vault.append(JSONObject().put("t", "key").put("k", Base64.getEncoder().encodeToString(k)).toString().toByteArray())
        }
        key = SecretKeySpec(k, "AES")
        k.fill(0)
        // Transfers cut short by a restart cannot resume.
        for (m in metas.values) when (m.file.state) {
            MediaFile.State.RECEIVING -> { m.file = m.file.copy(state = MediaFile.State.FAILED, received = 0); blob(m.file.id).delete() }
            MediaFile.State.SENDING -> m.file = m.file.copy(state = MediaFile.State.FAILED)
            else -> {}
        }
        dir.listFiles()?.forEach { if (it.name.endsWith(".part") || it.name.removeSuffix(".bin") !in metas) it.delete() }
        publish()
        scope.launch { while (true) { delay(SWEEP_MS); lock.withLock { sweep() } } }
    }

    data class Outgoing(
        val fileId: String,
        val offer: SocialEvent,
        val chunks: Sequence<SocialEvent>,
        val mime: String,
        val name: String,
        val size: Long,
    )

    // ---- Sending --------------------------------------------------------------------

    /** Reads a picked photo or document. Photos are downscaled and re-encoded without EXIF/GPS. */
    suspend fun prepareOutgoing(uri: Uri): Result<Outgoing> = withContext(Dispatchers.IO) {
        runCatching {
            val cr = app.contentResolver
            var name = "file"
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
            val mime = cleanMime(cr.getType(uri))
            if (isPhoto(mime)) {
                val orient = cr.openInputStream(uri)?.use { orientation(ExifInterface(it)) } ?: 0
                val jpeg = photo({ cr.openInputStream(uri) ?: error("unreadable") }, orient)
                store(jpegName(name), "image/jpeg", jpeg.inputStream(), null)
            } else {
                val dur = if (mime.startsWith("audio/") || mime.startsWith("video/")) duration { it.setDataSource(app, uri) } else null
                (cr.openInputStream(uri) ?: error("unreadable")).use { store(name, mime, it, dur) }
            }
        }
    }

    /** Reads a local file (a voice recording, say). Recordings in the app's voice cache are deleted once sealed. */
    suspend fun prepareOutgoing(file: java.io.File, name: String, mime: String): Result<Outgoing> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val m = cleanMime(mime)
                if (isPhoto(m)) {
                    val orient = orientation(ExifInterface(file.path))
                    store(jpegName(name), "image/jpeg", photo({ file.inputStream() }, orient).inputStream(), null)
                } else {
                    val dur = if (m.startsWith("audio/") || m.startsWith("video/")) duration { it.setDataSource(file.path) } else null
                    file.inputStream().use { store(name, m, it, dur) }
                }
            } finally {
                if (file.parentFile?.canonicalPath == File(app.cacheDir, RECORD_DIR).canonicalPath) file.delete()
            }
        }
    }

    /** Bytes of [id] handed to the mesh so far (the chunk sequence also reports this itself). */
    fun markProgress(id: String, bytesSent: Long) = change(id) {
        if (it.state == MediaFile.State.SENDING) it.copy(received = bytesSent.coerceIn(0, it.size)) else it
    }

    /** All chunks of [id] went out. */
    fun markSent(id: String) {
        change(id) { it.copy(state = MediaFile.State.READY, received = it.size) }
        persist(id)
    }

    /** Sending [id] failed; the local copy stays viewable. */
    fun markFailed(id: String) {
        change(id) { if (it.state == MediaFile.State.SENDING) it.copy(state = MediaFile.State.FAILED) else it }
        persist(id)
    }

    private fun store(rawName: String, mime: String, input: InputStream, durationMs: Long?): Outgoing {
        val id = "f-" + ByteArray(12).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val name = cleanName(rawName)
        val tmp = File(dir, "$id.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            SegmentWriter(tmp, id).use { w ->
                val buf = ByteArray(SEG)
                while (true) {
                    var n = 0
                    while (n < SEG) { val r = input.read(buf, n, SEG - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    if (w.bytes + n > MAX_BYTES) error("over 25 MB")
                    digest.update(buf, 0, n)
                    w.write(buf, n)
                    if (n < SEG) break
                }
                size = w.bytes
            }
            require(size > 0) { "empty file" }
            check(tmp.renameTo(blob(id))) { "could not store" }
        } catch (e: Throwable) {
            tmp.delete(); throw e
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val total = socialChunkCount(size.toULong())
        val offer = socialEvent(SocialKind.FILE_OFFER).copy(id = id, text = name, mime = mime, size = size.toULong(), sha256 = sha, total = total)
        socialValidate(offer)
        val f = MediaFile(id, name, mime, size, 0, MediaFile.State.SENDING, durationMs)
        synchronized(metas) { metas[id] = Meta(f, outgoing = true, convo = "", sha256 = sha, complete = true) }
        persist(id)
        publish()
        val n = total.toInt()
        val chunks = sequence {
            for (i in 0 until n) {
                val plain = readSegment(id, i) ?: error("local copy is damaged")
                yield(
                    socialEvent(SocialKind.FILE_CHUNK).copy(
                        target = id, index = i.toUInt(), total = total, text = Base64.getEncoder().encodeToString(plain),
                    ),
                )
                markProgress(id, minOf(size, (i + 1L) * SEG))
            }
        }
        return Outgoing(id, offer, chunks, mime, name, size)
    }

    // ---- Receiving ------------------------------------------------------------------

    /** Handles a peer's [SocialKind.FILE_OFFER] or [SocialKind.FILE_CHUNK]; anything else is ignored. */
    suspend fun onIncoming(convo: String, event: SocialEvent) = withContext(Dispatchers.IO) {
        lock.withLock {
            sweep()
            when (event.kind) {
                SocialKind.FILE_OFFER -> offer(convo, event)
                SocialKind.FILE_CHUNK -> chunk(convo, event)
                else -> {}
            }
        }
    }

    private fun offer(convo: String, e: SocialEvent) {
        if (runCatching { socialValidate(e) }.isFailure) return
        if (e.size == 0UL || e.size > MAX_BYTES.toULong() || e.total != socialChunkCount(e.size)) return
        if (synchronized(metas) { e.id in metas }) return // duplicate, or an id we already hold
        if (partials.size >= MAX_PARTIALS || partials.values.count { it.convo == convo } >= MAX_PARTIALS_PER_CONVO) return
        val size = e.size.toLong()
        if (dir.usableSpace < size * 2 + RESERVE_BYTES) return
        val f = MediaFile(e.id, cleanName(e.text), cleanMime(e.mime), size, 0, MediaFile.State.RECEIVING, null)
        synchronized(metas) { metas[e.id] = Meta(f, outgoing = false, convo = convo, sha256 = e.sha256.lowercase(), complete = false) }
        partials[e.id] = Partial(convo, e.total.toInt(), 0, MessageDigest.getInstance("SHA-256"), SegmentWriter(File(dir, "${e.id}.part"), e.id), now())
        persist(e.id)
        publish()
    }

    private fun chunk(convo: String, e: SocialEvent) {
        val p = partials[e.target] ?: return
        if (p.convo != convo) return // chunks only count from the peer that offered the file
        if (runCatching { socialValidate(e) }.isFailure || e.total.toInt() != p.total) return fail(e.target)
        val idx = e.index.toInt()
        if (idx < p.next) return // retransmitted chunk
        if (idx > p.next) return fail(e.target)
        val data = runCatching { Base64.getDecoder().decode(e.text) }.getOrNull() ?: return fail(e.target)
        val meta = synchronized(metas) { metas[e.target] } ?: return
        val size = meta.file.size
        val want = if (idx == p.total - 1) (size - SEG.toLong() * (p.total - 1)).toInt() else SEG
        if (data.size != want) return fail(e.target)
        p.digest.update(data)
        p.out.write(data, data.size)
        p.next++
        p.last = now()
        change(e.target) { it.copy(received = p.out.bytes) }
        if (p.next < p.total) return
        partials.remove(e.target)
        p.out.close()
        val sha = p.digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != meta.sha256 || p.out.bytes != size || !File(dir, "${e.target}.part").renameTo(blob(e.target))) return fail(e.target)
        meta.complete = true
        val dur = if (meta.file.isAudio) decrypt(e.target)?.let { bytes -> duration { it.setDataSource(BytesSource(bytes)) } } else null
        change(e.target) { it.copy(state = MediaFile.State.READY, received = size, durationMs = dur) }
        persist(e.target)
    }

    private fun fail(id: String) {
        partials.remove(id)?.out?.let { runCatching { it.close() } }
        File(dir, "$id.part").delete()
        change(id) { it.copy(state = MediaFile.State.FAILED, received = 0) }
        persist(id)
    }

    /** Drops partial transfers that have gone quiet. Caller holds [lock]. */
    private fun sweep() {
        val cut = now() - STALE_MS
        partials.filterValues { it.last < cut }.keys.toList().forEach(::fail)
    }

    // ---- Viewing --------------------------------------------------------------------

    /** Decrypts [id] to a private cache file for viewing or sharing (cleared on next start). */
    suspend fun open(id: String): java.io.File? = withContext(Dispatchers.IO) {
        val m = synchronized(metas) { metas[id] } ?: return@withContext null
        if (!m.complete) return@withContext null
        val out = File(File(openDir, id), safeFileName(m.file.name))
        if (out.exists() && out.length() == m.file.size) return@withContext out
        val bytes = decrypt(id) ?: return@withContext null
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        bytes.fill(0)
        out
    }

    /** Copies [id] to a user-chosen document ([uri] from the system "save" picker). */
    suspend fun saveTo(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bytes = decrypt(id) ?: return@withContext false
        try {
            app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        } catch (e: Exception) {
            false
        } finally {
            bytes.fill(0)
        }
    }

    /** A small preview of image [id] (blocking; call off the main thread), or null. */
    fun thumbnail(id: String): ImageBitmap? = image(id, THUMB_PX)?.also { thumbs.put(id, it) }

    /** Image [id] decoded to at most [maxPx] on its long side. Blocking. */
    fun image(id: String, maxPx: Int): ImageBitmap? {
        if (maxPx == THUMB_PX) thumbs.get(id)?.let { return it }
        val m = synchronized(metas) { metas[id] } ?: return null
        if (!m.complete || !m.file.isImage) return null
        val bytes = decrypt(id) ?: return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(maxOf(o.outWidth, o.outHeight), maxPx) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    /** Forgets [id] and deletes its sealed bytes (disappearing messages, delete for everyone). */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            partials.remove(id)?.out?.let { runCatching { it.close() } }
            File(dir, "$id.part").delete()
            blob(id).delete()
            File(openDir, id).deleteRecursively()
            thumbs.remove(id)
            if (synchronized(metas) { metas.remove(id) } != null) {
                runCatching { vault.append(JSONObject().put("t", "del").put("id", id).toString().toByteArray()) }
                publish()
            }
        }
    }

    // ---- Internals ------------------------------------------------------------------

    private fun blob(id: String) = File(dir, "$id.bin")

    private fun now() = System.currentTimeMillis()

    private fun publish() {
        _files.value = synchronized(metas) { metas.mapValues { it.value.file } }
    }

    private fun change(id: String, f: (MediaFile) -> MediaFile) {
        synchronized(metas) { metas[id]?.let { it.file = f(it.file) } ?: return }
        _files.update { cur -> synchronized(metas) { metas[id]?.file }?.let { cur + (id to it) } ?: cur }
    }

    private fun persist(id: String) {
        val m = synchronized(metas) { metas[id] } ?: return
        val f = m.file
        val o = JSONObject().put("t", "file").put("id", f.id).put("n", f.name).put("m", f.mime).put("s", f.size)
            .put("st", f.state.name).put("o", m.outgoing).put("c", m.convo).put("h", m.sha256).put("x", m.complete)
        f.durationMs?.let { o.put("d", it) }
        runCatching { vault.append(o.toString().toByteArray()) }
    }

    private fun aad(id: String, idx: Int) = "AETHER-MEDIA-v1:$id:$idx".toByteArray()

    /** Writes sealed segments: [len:int][nonce:12][ciphertext+tag], one per [SEG] bytes of plaintext. */
    private inner class SegmentWriter(file: File, private val id: String) : AutoCloseable {
        private val out = DataOutputStream(FileOutputStream(file).buffered())
        private var idx = 0
        var bytes = 0L
            private set

        fun write(plain: ByteArray, len: Int) {
            val nonce = ByteArray(NONCE).also { rng.nextBytes(it) }
            val c = Cipher.getInstance(AES)
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            c.updateAAD(aad(id, idx))
            val ct = c.doFinal(plain, 0, len)
            out.writeInt(NONCE + ct.size)
            out.write(nonce)
            out.write(ct)
            idx++
            bytes += len
        }

        override fun close() = out.close()
    }

    /** Segment [i] of complete file [id]; every segment but the last holds exactly [SEG] bytes. */
    private fun readSegment(id: String, i: Int): ByteArray? = runCatching {
        RandomAccessFile(blob(id), "r").use { f ->
            f.seek(i.toLong() * (4 + NONCE + SEG + TAG))
            val len = f.readInt()
            require(len in (NONCE + TAG + 1)..(NONCE + SEG + TAG))
            val rec = ByteArray(len).also { f.readFully(it) }
            val c = Cipher.getInstance(AES)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, rec, 0, NONCE))
            c.updateAAD(aad(id, i))
            c.doFinal(rec, NONCE, len - NONCE)
        }
    }.getOrNull()

    /** Whole plaintext of [id], checked against its size and SHA-256, or null. */
    private fun decrypt(id: String): ByteArray? {
        val m = synchronized(metas) { metas[id] } ?: return null
        val size = m.file.size
        if (size <= 0 || size > MAX_BYTES) return null
        val total = socialChunkCount(size.toULong()).toInt()
        val out = ByteArray(size.toInt())
        var pos = 0
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until total) {
            val seg = readSegment(id, i) ?: return null
            if (pos + seg.size > out.size) return null
            seg.copyInto(out, pos)
            digest.update(seg)
            pos += seg.size
        }
        if (pos != out.size) return null
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return if (sha == m.sha256) out else null.also { out.fill(0) }
    }

    private fun isPhoto(mime: String) = mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml"

    private fun orientation(e: ExifInterface): Int = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    /** Decodes, downscales to ≤ [PHOTO_PX], rotates upright and re-encodes as JPEG q85 (no metadata survives). */
    private fun photo(open: () -> InputStream, rotate: Int): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, o) }
        require(o.outWidth > 0 && o.outHeight > 0) { "not a readable image" }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(maxOf(o.outWidth, o.outHeight), PHOTO_PX) }
        val src = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: error("not a readable image")
        val scale = minOf(1f, PHOTO_PX.toFloat() / maxOf(src.width, src.height))
        val mx = Matrix().apply { postScale(scale, scale); if (rotate != 0) postRotate(rotate.toFloat()) }
        var bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mx, true)
        if (bmp !== src) src.recycle()
        if (bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            bmp = flat
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun sample(longSide: Int, target: Int): Int {
        var s = 1
        while (longSide / (s * 2) >= target) s *= 2
        return s
    }

    private fun duration(set: (MediaMetadataRetriever) -> Unit): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            set(r)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    }.getOrNull()

    private class BytesSource(private val b: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= b.size) return -1
            val n = minOf(size.toLong(), b.size - position).toInt()
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize() = b.size.toLong()
        override fun close() {}
    }

    companion object {
        const val SEG = 32 * 1024
        const val MAX_BYTES = 25L * 1024 * 1024
        const val PHOTO_PX = 1600
        const val THUMB_PX = 480
        /** App-private cache dir for voice recordings in progress (see AttachButton). */
        const val RECORD_DIR = "voice-rec"
        private const val OPEN_DIR = "media-open"
        private const val TMP_DIR = "media-tmp"
        private const val AES = "AES/GCM/NoPadding"
        private const val NONCE = 12
        private const val TAG = 16
        private const val STALE_MS = 120_000L
        private const val SWEEP_MS = 30_000L
        private const val MAX_PARTIALS = 8
        private const val MAX_PARTIALS_PER_CONVO = 3
        private const val RESERVE_BYTES = 64L * 1024 * 1024

        fun cleanName(raw: String): String {
            val s = raw.filter { !it.isISOControl() && it != '/' && it != '\\' }.trim().take(200)
            return s.ifEmpty { "file" }
        }

        fun cleanMime(raw: String?): String {
            val m = raw?.trim()?.lowercase().orEmpty()
            return if (m.length in 3..100 && m.count { it == '/' } == 1 && m.none { it == '|' || it.isWhitespace() || it.isISOControl() }) m
            else "application/octet-stream"
        }

        private fun jpegName(name: String) = cleanName(name).substringBeforeLast('.').ifEmpty { "photo" } + ".jpg"

        private fun safeFileName(name: String): String {
            val s = name.map { if (it.isLetterOrDigit() || it in " .-_()") it else '_' }.joinToString("").trimStart('.').trim()
            return s.ifEmpty { "file" }.take(120)
        }
    }
}

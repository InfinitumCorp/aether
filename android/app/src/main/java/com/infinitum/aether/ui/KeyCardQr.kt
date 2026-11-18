package com.infinitum.aether.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.infinitum.aether.net.AetherNode
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Renders [text] as a black-on-white QR code, [px] square. */
internal fun qrBitmap(text: String, px: Int = 720): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Opens the ZXing camera screen for one QR code; [onScan] gets its text, or nothing on cancel. */
@Composable
internal fun rememberQrScanner(onScan: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(onScan) }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan an ÆTHER key card")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
}

/**
 * Face-to-face contact exchange: this operator's key card as a QR code, and a scanner for the
 * other side's. Nothing goes to a server and no phone number or address book is involved; the
 * scanned key is pinned, so the session is accepted only from the device holding that key.
 */
@Composable
internal fun KeyCardDialog(node: AetherNode, onDismiss: () -> Unit, onPinned: (String) -> Unit) {
    val card = remember { runCatching { node.myKeyCard() }.getOrNull() }
    val qr = remember(card) { card?.let { runCatching { qrBitmap(it) }.getOrNull() } }
    val scan = rememberQrScanner { text -> node.pinScanned(text).onSuccess(onPinned) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        shape = MaterialTheme.shapes.small,
        title = { Text("Key card", style = MaterialTheme.typography.titleLarge, color = Ink) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr != null) {
                    Image(qr, contentDescription = "Your key card QR code", modifier = Modifier.size(240.dp).testTag("keycard_qr"))
                } else {
                    Text("Could not make your key card.", color = Body)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Let the other operator scan this, then scan theirs. Only your public key, name and local addresses are in it.",
                    style = MaterialTheme.typography.bodySmall, color = Body,
                )
                Spacer(Modifier.height(8.dp))
                Text(node.profile.identity.fingerprint(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Ink)
            }
        },
        confirmButton = {
            TextButton(onClick = scan, modifier = Modifier.testTag("keycard_scan")) { Text("Scan theirs ↗", color = Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Body) } },
    )
}

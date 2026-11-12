package com.infinitum.aether

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.infinitum.aether.cid.CidAccount
import com.infinitum.aether.net.AetherNode
import com.infinitum.aether.ui.AetherRoot
import com.infinitum.aether.ui.AetherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardened: no screenshots, screen recording or recents thumbnails of message content.
        if (!BuildConfigFlags.allowScreenCapture(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // White UI: dark status/navigation bar icons on a white page.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        // The "reachable" notification of the background node needs this on Android 13+.
        // If refused, the node still runs; only the notification is hidden.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val app = application as AetherApp
        // Cumulus ID: open the stored sign-in, pick a reachable host (Russia mirror when needed),
        // and take a redirect that launched this activity. Not again after a configuration change.
        CidAccount.start(app)
        if (savedInstanceState == null) handleCidReturn(intent)
        setContent {
            AetherTheme {
                var loading by remember { mutableStateOf(true) }
                var node by remember { mutableStateOf<AetherNode?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    try {
                        node = withContext(Dispatchers.IO) { app.ensureNode() }
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    }
                    loading = false
                }
                AetherRoot(
                    loading = loading,
                    node = node,
                    fatalError = error,
                    keyBacking = { app.store.keyBacking },
                    onCreateProfile = { name -> withContext(Dispatchers.IO) { app.createProfile(name) }.also { node = it } },
                    scratchDir = cacheDir,
                    features = { app.features },
                )
            }
        }
    }

    // launchMode="singleTask": the Cumulus ID redirect comes back to this instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCidReturn(intent)
    }

    private fun handleCidReturn(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && CidAccount.handleReturn(data.toString())) {
            // Do not process the same code twice.
            setIntent(Intent(this, MainActivity::class.java))
        }
    }
}

/** Screen capture is allowed only in debuggable builds, so automated UI tests can take screenshots. */
object BuildConfigFlags {
    fun allowScreenCapture(activity: ComponentActivity): Boolean =
        (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

package com.infinitum.aether.cid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.infinitum.aether.AetherApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The operator's Cumulus ID sign-in, for the whole process. MainActivity starts it and forwards
 * redirect intents; Onboarding reads its state; Settings (Diagnostics) can show [signedInLine].
 *
 * Observable state is Compose state and is written on the main thread only. Store access and the
 * token exchange run on one serial IO lane, so a return can never race the store opening or the
 * pending values being written. Nothing here logs, and no token is ever stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object CidAccount {
    private val cid = CumulusId(CidConfig())
    private val lane = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var app: AetherApp? = null
    private var store: CidStore? = null // touched on [lane] only
    private var started = false

    /** The Cumulus ID host sign-in will use (main or the Russia mirror), shown under Advanced. */
    var issuer by mutableStateOf(cid.activeIssuer)
        private set

    /** The verified sign-in (claims only), or null. */
    var session by mutableStateOf<CidSession?>(null)
        private set

    /** True once the stored sign-in has been read. */
    var loaded by mutableStateOf(false)
        private set

    /** True while a returned code is being exchanged and verified. */
    var busy by mutableStateOf(false)
        private set

    /** A short message for the operator, never containing a token or code. */
    var error by mutableStateOf<String?>(null)
        private set

    /** "Signed in with Cumulus ID as …" for Settings / Diagnostics, or null when not signed in. */
    val signedInLine: String?
        get() = session?.let { "Signed in with Cumulus ID as ${it.profile.display}" }

    /** Opens the stored sign-in and picks a reachable Cumulus ID host. Idempotent; call from onCreate. */
    fun start(app: AetherApp) {
        if (started) return
        started = true
        this.app = app
        val throttled = throttledCountry(app)
        scope.launch {
            val saved = withContext(lane) { storeOnLane().loadSession() }
            session = saved
            loaded = true
        }
        scope.launch {
            issuer = withContext(Dispatchers.IO) {
                runCatching { cid.pickReachableIssuer(throttledNetwork = throttled) }.getOrDefault(cid.activeIssuer)
            }
        }
    }

    /**
     * "Sign in with Cumulus ID" ([create] false) or "Create a Cumulus ID" ([create] true): saves the
     * PKCE, state and nonce, then opens the IdP in the browser. Either way the person comes back
     * through the redirect signed in.
     */
    fun begin(context: Context, create: Boolean) {
        if (busy) return
        error = null
        val pending = cid.newPending()
        val url = cid.authorizeUrl(pending, create)
        scope.launch {
            withContext(lane) { storeOnLane().savePending(pending) }
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is android.app.Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(view)
            } catch (_: ActivityNotFoundException) {
                error = "No browser is installed to open Cumulus ID."
            }
        }
    }

    /** Handles a redirect. Returns false when [uri] is not a Cumulus ID return. */
    fun handleReturn(uri: String): Boolean {
        val back = CumulusId.parseReturn(uri, cid.config) ?: return false
        busy = true
        error = null
        scope.launch {
            try {
                val verified = withContext(lane) {
                    val store = storeOnLane()
                    val pending = store.loadPending()
                    store.savePending(null)
                    when {
                        back.error.isNotEmpty() -> throw CidFlowException("Cumulus ID sign-in was not completed.")
                        pending == null || back.code.isEmpty() || !sameState(back.state, pending.state) ->
                            throw CidFlowException("That sign-in didn't match. Please try again.")
                        else -> cid.redeem(pending, back.code).also(store::saveSession)
                    }
                }
                session = verified
            } catch (e: CidFlowException) {
                error = e.message
            } catch (e: IdTokenException) {
                error = e.message
            } catch (e: CidHttpException) {
                error = "Cumulus ID refused that sign-in (${e.reason}). Please try again."
            } catch (_: Exception) {
                error = "Cumulus ID could not be reached. Check the connection and try again."
            } finally {
                busy = false
            }
        }
        return true
    }

    /** Forgets the Cumulus ID sign-in on this device. The ÆTHER identity key is not touched. */
    fun signOut() {
        session = null
        error = null
        scope.launch { withContext(lane) { storeOnLane().saveSession(null) } }
    }

    private fun storeOnLane(): CidStore = store ?: openStore().also { store = it }

    private fun openStore(): CidStore {
        val a = app ?: return MemoryCidStore()
        return try {
            val vault = a.store.openVault("cid")
            LogCidStore(append = { vault.append(it) }, readAll = { vault.readAll() })
        } catch (_: Exception) {
            // The sign-in still works for this run; it is just not remembered.
            MemoryCidStore()
        }
    }

    private fun sameState(a: String, b: String) =
        a.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** True when the mobile network or the SIM is Russian or Belarusian (Cloudflare is throttled there). */
    private fun throttledCountry(context: Context): Boolean = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso).any { it.equals("ru", true) || it.equals("by", true) }
    } catch (_: Exception) {
        false
    }

    private class CidFlowException(message: String) : Exception(message)
}

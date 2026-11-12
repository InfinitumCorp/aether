package com.infinitum.aether.cid

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "Sign in with Cumulus://ID" for ÆTHER, ported from FabriC++ (data/CumulusId.kt): OpenID Connect
 * authorization code + PKCE S256 + state + nonce against https://id.cumuluscloud.app/realms/cumulus
 * (public client [CidConfig.DEFAULT_CLIENT], no secret).
 *
 *   1. The browser opens {host}/realms/cumulus/protocol/openid-connect/auth. People without a Cumulus
 *      ID create one there (prompt=create opens that page first), then the IdP continues.
 *   2. The IdP redirects to com.infinitum.aether:/cid/return?code&state (RFC 8252 private-use scheme,
 *      so no web page or App Link domain is needed, and the return works the same from the mirror).
 *   3. The app posts the code and verifier (form) to …/token on the host that served the sign-in,
 *      then verifies the ID token on the device ([IdTokenVerifier]: ES256 against …/certs, iss, aud,
 *      exp, nonce). ÆTHER only needs the verified identity, so the access and refresh tokens are
 *      revoked right away and never stored.
 *
 * The host falls back to the Russia mirror when the main host does not deliver the sign-in page.
 * The mirror serves the same realm: endpoints and keys come from whichever host answered, but the
 * ID token's iss is always [CidConfig.OIDC_ISSUER].
 *
 * Plain java.net + org.json (no OkHttp), so it adds no dependency and runs in JVM unit tests.
 * Nothing here logs; error messages are short reasons that never contain a token or code.
 */
data class CidConfig(
    val issuer: String = DEFAULT_ISSUER,
    val clientId: String = DEFAULT_CLIENT,
    val redirectUri: String = DEFAULT_REDIRECT,
    /** Tried in order when [issuer] does not answer (e.g. Cloudflare stalls on some Russian networks). */
    val fallbackIssuers: List<String> = listOf(RU_ISSUER),
) {
    init {
        require(issuer.startsWith("https://")) { "Cumulus ID issuer must be HTTPS" }
        require(fallbackIssuers.all { it.startsWith("https://") }) { "Cumulus ID fallback issuers must be HTTPS" }
        require(redirectUri.startsWith("$APP_SCHEME:/") || redirectUri.startsWith("https://")) {
            "Cumulus ID redirect must be the app scheme or HTTPS"
        }
    }

    companion object {
        const val DEFAULT_ISSUER = "https://id.cumuluscloud.app"
        /** The `iss` of every Cumulus://ID token, whichever host served it. */
        const val OIDC_ISSUER = "https://id.cumuluscloud.app/realms/cumulus"
        const val REALM_PATH = "/realms/cumulus"
        const val SCOPE = "openid profile email"
        /** Same Cumulus ID, served from a host that is reachable in Russia without a VPN. */
        const val RU_ISSUER = "https://ru.id.cumuluscloud.app"
        /** Public OIDC client registered in the cumulus realm for ÆTHER. */
        const val DEFAULT_CLIENT = "aether"
        /** RFC 8252 private-use scheme redirect: the reverse-DNS package name, one slash. */
        const val APP_SCHEME = "com.infinitum.aether"
        const val RETURN_PATH = "/cid/return"
        const val DEFAULT_REDIRECT = "$APP_SCHEME:$RETURN_PATH"
        /** The Cumulus ID sign-in page's main script (about 75 KB), used to test a host end to end. */
        const val SIGN_IN_SCRIPT = "/app.js"
        const val MIN_SCRIPT_BYTES = 32 * 1024
        const val USER_AGENT = "AETHER-Android"

        /** The host part of an issuer setting (a full realm URL is accepted too). */
        fun hostOf(issuer: String): String = issuer.trimEnd('/').removeSuffix(REALM_PATH)

        /** The realm's OpenID Connect endpoints on [issuer]'s host (main or mirror). */
        fun oidc(issuer: String): String = hostOf(issuer) + REALM_PATH + "/protocol/openid-connect"
    }
}

/** What the app keeps between the browser opening and the return. */
data class CidPending(val verifier: String, val state: String, val issuer: String, val nonce: String)

data class CidProfile(
    /** The verified ID token's `sub`: the stable account id. */
    val cid: String,
    val username: String,
    val email: String,
    val handle: String,
    /** The token's `name` claim (may be blank). */
    val name: String = "",
) {
    /** How "Signed in with Cumulus ID as …" names the person. */
    val display: String get() = email.ifBlank { username.ifBlank { handle.ifBlank { "Cumulus ID" } } }

    /** A starting call sign for onboarding; the operator edits it before confirming. */
    val suggestedCallSign: String
        get() = name.ifBlank { username.ifBlank { handle.removePrefix("@").ifBlank { email.substringBefore('@') } } }
            .trim().take(40)
}

/**
 * A verified Cumulus ID sign-in. No token is kept: [verifiedAtMillis] is when the ID token passed
 * [IdTokenVerifier], [issuer] the host that served it.
 */
data class CidSession(val profile: CidProfile, val verifiedAtMillis: Long, val issuer: String) {
    /** Onboarding accepts a sign-in this recent, so a restart mid-onboarding does not force a new one. */
    fun freshForOnboarding(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - verifiedAtMillis in 0..ONBOARDING_WINDOW_MILLIS

    companion object {
        const val ONBOARDING_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }
}

data class CidReturn(val code: String, val state: String, val error: String)

/** The token endpoint refused. [reason] is a short OAuth error code, never a token. */
class CidHttpException(val status: Int, val reason: String) : IOException("Cumulus ID answered $status ($reason)")

/** A minimal blocking HTTP client so tests can see requests and the app needs no HTTP library. */
fun interface CidHttp {
    /** [body] null means GET (or HEAD when [head]); otherwise a form POST. */
    fun call(url: String, body: Map<String, String>?, head: Boolean, timeoutMillis: Int): CidHttpResponse
}

class CidHttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
    val ok: Boolean get() = status in 200..299
}

/** java.net client: no redirects (keys and tokens come only from the host that answered), no cookies. */
object UrlConnectionHttp : CidHttp {
    private const val MAX_BODY = 1 shl 20

    override fun call(url: String, body: Map<String, String>?, head: Boolean, timeoutMillis: Int): CidHttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = minOf(timeoutMillis, 4000)
            conn.readTimeout = minOf(timeoutMillis, 8000)
            conn.setRequestProperty("User-Agent", CidConfig.USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            when {
                body != null -> {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val form = body.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()
                    conn.setFixedLengthStreamingMode(form.size)
                    conn.outputStream.use { it.write(form) }
                }
                head -> conn.requestMethod = "HEAD"
                else -> conn.requestMethod = "GET"
            }
            val status = conn.responseCode
            if (head) return CidHttpResponse(status, ByteArray(0))
            val deadline = System.currentTimeMillis() + timeoutMillis
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readCapped(it, deadline) } ?: ByteArray(0)
            return CidHttpResponse(status, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream, deadline: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            if (System.currentTimeMillis() > deadline) throw IOException("timed out")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY) throw IOException("response too large")
        }
        return out.toByteArray()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}

class CumulusId(
    val config: CidConfig = CidConfig(),
    private val http: CidHttp = UrlConnectionHttp,
    private val random: SecureRandom = SecureRandom(),
    /** Checks ID tokens; keeps the JWKS between sign-ins. Tests inject one with a fixed clock. */
    private val verifier: IdTokenVerifier = IdTokenVerifier(config.clientId, { url -> fetchJwks(http, url) }),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** The issuer sign-in uses: [CidConfig.issuer], or the first fallback that answered when it did not. */
    @Volatile
    var activeIssuer: String = config.issuer
        private set

    /**
     * Picks an issuer that can actually serve the sign-in page on this network (blocking; call off the
     * main thread). Some Russian networks let Cloudflare answer small requests but stall every transfer
     * after the first few kilobytes, so a host only counts when it delivers the full sign-in script.
     * When no host passes, any host that answers at all is used, then [CidConfig.issuer].
     */
    fun pickReachableIssuer(
        probe: (String) -> Boolean = ::servesSignIn,
        answersAtAll: (String) -> Boolean = ::answers,
        throttledNetwork: Boolean = false,
    ): String {
        // Russian and Belarusian networks throttle Cloudflare on and off: there the mirrors go first.
        val all = if (throttledNetwork) config.fallbackIssuers + config.issuer else listOf(config.issuer) + config.fallbackIssuers
        val chosen = all.firstOrNull(probe) ?: all.firstOrNull(answersAtAll) ?: config.issuer
        activeIssuer = chosen
        return chosen
    }

    private fun servesSignIn(issuer: String): Boolean = try {
        val r = http.call(CidConfig.hostOf(issuer) + CidConfig.SIGN_IN_SCRIPT, null, head = false, timeoutMillis = 8000)
        r.ok && r.body.size >= CidConfig.MIN_SCRIPT_BYTES
    } catch (_: Exception) {
        false
    }

    private fun answers(issuer: String): Boolean = try {
        http.call(CidConfig.hostOf(issuer) + "/", null, head = true, timeoutMillis = 6000).status in 200..499
    } catch (_: Exception) {
        false
    }

    fun newPending(): CidPending =
        CidPending(verifier = randomToken(48), state = randomToken(16), issuer = activeIssuer, nonce = randomToken(16))

    /** The authorize URL to open in the browser. [create] asks the IdP to show "Create a Cumulus ID" first. */
    fun authorizeUrl(pending: CidPending, create: Boolean = false): String {
        val params = buildList {
            add("client_id" to config.clientId)
            add("redirect_uri" to config.redirectUri)
            add("response_type" to "code")
            add("scope" to CidConfig.SCOPE)
            add("code_challenge" to challenge(pending.verifier))
            add("code_challenge_method" to "S256")
            add("state" to pending.state)
            add("nonce" to pending.nonce)
            if (create) add("prompt" to "create")
        }
        return CidConfig.oidc(pending.issuer) + "/auth?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /**
     * Trades a returned code for tokens on the host that served the sign-in and verifies the ID token.
     * Then revokes the access and refresh tokens (best effort): ÆTHER keeps only the verified identity.
     * Throws [CidHttpException] (token endpoint) or [IdTokenException] (verification) with a short
     * reason that never contains a token.
     */
    fun redeem(pending: CidPending, code: String): CidSession {
        val response = http.call(
            CidConfig.oidc(pending.issuer) + "/token",
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to pending.verifier,
                "client_id" to config.clientId,
                "redirect_uri" to config.redirectUri,
            ),
            head = false,
            timeoutMillis = 15000,
        )
        val json = try {
            if (response.text.isBlank()) JSONObject() else JSONObject(response.text)
        } catch (_: Exception) {
            JSONObject()
        }
        if (!response.ok) throw CidHttpException(response.status, safeReason(json.optString("error"), response.status))
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        return try {
            if (access.isBlank()) throw CidHttpException(response.status, "no_token")
            val verified = try {
                verifier.verify(json.optString("id_token").ifBlank { null }, pending.nonce, CidConfig.oidc(pending.issuer) + "/certs")
            } catch (e: IdTokenException) {
                throw IdTokenException("That sign-in couldn't be verified (${e.message}). Please try again.")
            }
            CidSession(profileOf(verified), nowMillis(), pending.issuer)
        } finally {
            if (refresh.isNotBlank()) revokeQuietly(refresh, "refresh_token", pending.issuer)
            if (access.isNotBlank()) revokeQuietly(access, "access_token", pending.issuer)
        }
    }

    /** Revokes a token at Cumulus://ID (RFC 7009). Best effort; failures are ignored. */
    fun revokeQuietly(token: String, hint: String, issuer: String = activeIssuer) {
        try {
            http.call(
                CidConfig.oidc(issuer) + "/revoke",
                mapOf("token" to token, "token_type_hint" to hint, "client_id" to config.clientId),
                head = false,
                timeoutMillis = 6000,
            )
        } catch (_: Exception) {
            // Best effort: the token is short-lived and was never stored.
        }
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return b64url(buf)
    }

    companion object {
        fun challenge(verifier: String): String =
            b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** The person from a verified ID token, keyed by its `sub`. */
        fun profileOf(token: VerifiedIdToken) = CidProfile(
            cid = token.sub,
            username = token.claims.optString("preferred_username").ifBlank { token.claims.optString("username") },
            email = token.claims.optString("email"),
            handle = token.claims.optString("handle"),
            name = token.claims.optString("name"),
        )

        /** GETs a JWKS document (no redirects: keys come only from the host that answered). */
        fun fetchJwks(http: CidHttp, url: String): String {
            val r = http.call(url, null, head = false, timeoutMillis = 10000)
            if (!r.ok) throw IOException("JWKS answered ${r.status}")
            return r.text
        }

        /** An OAuth error code, or http_<status>; only [A-Za-z0-9_.-], so no free text or token leaks. */
        fun safeReason(error: String, status: Int): String =
            error.filter { it.isLetterOrDigit() || it in "_.-" }.take(60).ifBlank { "http_$status" }

        /**
         * Reads code/state (or error) from a return URI: com.infinitum.aether:/cid/return?… (also
         * accepted with an empty authority, com.infinitum.aether:///cid/return?…). An https redirect
         * configured instead is matched on host and path. Returns null for any other URI.
         */
        fun parseReturn(uri: String, config: CidConfig): CidReturn? {
            val parsed = try {
                URI(uri)
            } catch (_: Exception) {
                return null
            }
            val redirect = try {
                URI(config.redirectUri)
            } catch (_: Exception) {
                return null
            }
            val matches = when (redirect.scheme) {
                "https" -> parsed.scheme == "https" && parsed.host == redirect.host && parsed.path == redirect.path
                else -> parsed.scheme == redirect.scheme && parsed.host.isNullOrEmpty() && parsed.path == redirect.path
            }
            if (!matches) return null
            val query = (parsed.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
            }
            return CidReturn(code = query["code"].orEmpty(), state = query["state"].orEmpty(), error = query["error"].orEmpty())
        }

        private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        private fun dec(v: String) = try {
            java.net.URLDecoder.decode(v, "UTF-8")
        } catch (_: IllegalArgumentException) {
            v
        }
    }
}

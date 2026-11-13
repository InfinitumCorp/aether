package com.infinitum.aether.cid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPair

/** Ported from FabriC++ CumulusIdTest; a recording [CidHttp] stands in for the realm. */
class CumulusIdTest {
    private val config = CidConfig()
    private val mirror = CidConfig.RU_ISSUER

    private class Call(val url: String, val form: Map<String, String>?)

    /** Serves the realm's token, certs and revoke endpoints like Cumulus://ID (or a mirror) does. */
    private class FakeRealm(private val key: KeyPair, var tokenBody: (Map<String, String>) -> Pair<Int, String>) : CidHttp {
        val calls = mutableListOf<Call>()
        override fun call(url: String, body: Map<String, String>?, head: Boolean, timeoutMillis: Int): CidHttpResponse {
            calls += Call(url, body)
            val path = URI(url).path
            return when (path) {
                "/realms/cumulus/protocol/openid-connect/token" -> tokenBody(body!!).let { (s, b) -> CidHttpResponse(s, b.toByteArray()) }
                "/realms/cumulus/protocol/openid-connect/certs" ->
                    CidHttpResponse(200, TestIdTokens.jwks(TestIdTokens.jwk(key, "k1")).toByteArray())
                "/realms/cumulus/protocol/openid-connect/revoke" -> CidHttpResponse(200, ByteArray(0))
                else -> CidHttpResponse(404, ByteArray(0))
            }
        }
    }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    private fun tokenJson(idToken: String) = JSONObject().put("access_token", "tok").put("refresh_token", "ref")
        .put("token_type", "Bearer").put("expires_in", 300).put("id_token", idToken).toString()

    @Test
    fun authorizeUrlUsesTheOidcRealmWithPkceStateAndNonce() {
        val cid = CumulusId(config)
        val pending = cid.newPending()
        val url = cid.authorizeUrl(pending)
        assertTrue(url.startsWith("https://id.cumuluscloud.app/realms/cumulus/protocol/openid-connect/auth?"))
        val q = query(url)
        assertEquals("aether", q["client_id"])
        assertEquals("com.infinitum.aether:/cid/return", q["redirect_uri"])
        assertEquals("code", q["response_type"])
        assertEquals("openid profile email", q["scope"])
        assertEquals(pending.nonce, q["nonce"])
        assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(pending.nonce))
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(CumulusId.challenge(pending.verifier), q["code_challenge"])
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(q["code_challenge"]!!))
        assertEquals(pending.state, q["state"])
        assertFalse(q.containsKey("prompt"))
        assertEquals("create", query(cid.authorizeUrl(pending, create = true))["prompt"])
    }

    @Test
    fun challengeMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CumulusId.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun parsesTheAppSchemeReturnOnly() {
        val a = CumulusId.parseReturn("com.infinitum.aether:/cid/return?code=abc&state=s%2B1", config)!!
        assertEquals("abc", a.code)
        assertEquals("s+1", a.state)
        val b = CumulusId.parseReturn("com.infinitum.aether:///cid/return?code=xyz&state=t", config)!!
        assertEquals("xyz", b.code)
        assertEquals("access_denied", CumulusId.parseReturn("com.infinitum.aether:/cid/return?error=access_denied", config)!!.error)
        assertNull(CumulusId.parseReturn("com.infinitum.aether://evil/cid/return?code=abc&state=s", config))
        assertNull(CumulusId.parseReturn("com.infinitum.aether:/other?code=abc", config))
        assertNull(CumulusId.parseReturn("infinitumfabric://cid/return?code=abc&state=s", config))
        assertNull(CumulusId.parseReturn("https://evil.example/cid/return?code=abc&state=s", config))
        assertNull(CumulusId.parseReturn("not a uri", config))
    }

    @Test
    fun redeemPostsAFormVerifiesTheIdTokenAndRevokesBothTokens() {
        val key = TestIdTokens.keyPair()
        lateinit var pending: CidPending
        val realm = FakeRealm(key) { 200 to tokenJson(TestIdTokens.sign(key, TestIdTokens.header(), TestIdTokens.claims(nonce = pending.nonce))) }
        val cid = CumulusId(config, realm, nowMillis = { 42L })
        pending = cid.newPending()
        val session = cid.redeem(pending, "code1")

        val token = realm.calls[0]
        assertEquals("https://id.cumuluscloud.app/realms/cumulus/protocol/openid-connect/token", token.url)
        val body = token.form!!
        assertEquals("authorization_code", body["grant_type"])
        assertEquals("code1", body["code"])
        assertEquals(pending.verifier, body["code_verifier"])
        assertEquals("aether", body["client_id"])
        assertEquals("com.infinitum.aether:/cid/return", body["redirect_uri"])
        // Keys come from the host that served the sign-in.
        assertEquals("https://id.cumuluscloud.app/realms/cumulus/protocol/openid-connect/certs", realm.calls[1].url)
        // ÆTHER keeps no token: refresh and access tokens are revoked at once.
        val revokes = realm.calls.drop(2)
        assertEquals(listOf("ref" to "refresh_token", "tok" to "access_token"), revokes.map { it.form!!["token"] to it.form!!["token_type_hint"] })
        assertTrue(revokes.all { it.url.endsWith("/realms/cumulus/protocol/openid-connect/revoke") && it.form!!["client_id"] == "aether" })

        assertEquals("cid_7c1e", session.profile.cid)
        assertEquals("ann", session.profile.username)
        assertEquals("@ann.veil", session.profile.handle)
        assertEquals("Ann", session.profile.name)
        assertEquals("ann@cumuluscloud.app", session.profile.display)
        assertEquals("Ann", session.profile.suggestedCallSign)
        assertEquals(42L, session.verifiedAtMillis)
        assertEquals(CidConfig.DEFAULT_ISSUER, session.issuer)
        assertFalse(session.toString().contains("tok"))
    }

    @Test
    fun redeemOnTheMirrorUsesTheMirrorForEveryCall() {
        val key = TestIdTokens.keyPair()
        lateinit var pending: CidPending
        val realm = FakeRealm(key) { 200 to tokenJson(TestIdTokens.sign(key, TestIdTokens.header(), TestIdTokens.claims(nonce = pending.nonce))) }
        val cid = CumulusId(config, realm)
        cid.pickReachableIssuer({ it == mirror }, { true })
        pending = cid.newPending()
        cid.redeem(pending, "code1")
        assertTrue(realm.calls.all { it.url.startsWith("$mirror/realms/cumulus/protocol/openid-connect/") })
    }

    @Test
    fun redeemRejectsAnIdTokenWithTheWrongNonceOrSignerAndStillRevokes() {
        val key = TestIdTokens.keyPair()
        val realm = FakeRealm(key) { 200 to tokenJson(TestIdTokens.sign(key, TestIdTokens.header(), TestIdTokens.claims(nonce = "replayed"))) }
        val cid = CumulusId(config, realm)
        val pending = cid.newPending()
        assertThrows(IdTokenException::class.java) { cid.redeem(pending, "code1") }
        assertTrue(realm.calls.any { it.url.endsWith("/revoke") && it.form!!["token"] == "tok" })
        realm.tokenBody = { 200 to tokenJson(TestIdTokens.sign(TestIdTokens.keyPair(), TestIdTokens.header(), TestIdTokens.claims(nonce = pending.nonce))) }
        assertThrows(IdTokenException::class.java) { cid.redeem(pending, "code1") }
        realm.tokenBody = { 200 to """{"access_token":"tok","expires_in":600}""" }
        assertThrows(IdTokenException::class.java) { cid.redeem(pending, "code1") }
    }

    @Test
    fun redeemErrorIsShortAndCarriesNoFreeText() {
        val realm = FakeRealm(TestIdTokens.keyPair()) { 400 to """{"error":"invalid_grant","error_description":"Code tok-123 not valid"}""" }
        val cid = CumulusId(config, realm)
        val thrown = assertThrows(CidHttpException::class.java) { cid.redeem(cid.newPending(), "x") }
        assertEquals(400, thrown.status)
        assertEquals("invalid_grant", thrown.reason)
        assertEquals("http_502", CumulusId.safeReason("", 502))
        assertEquals("Thatsign-incode", CumulusId.safeReason("That sign-in code!", 400))
    }

    @Test
    fun revokeIsBestEffort() {
        val cid = CumulusId(config, { _, _, _, _ -> throw IOException("offline") })
        cid.revokeQuietly("tok", "access_token") // does not throw
    }

    @Test
    fun mirrorHostsServeTheSameRealm() {
        assertEquals("https://ru.id.cumuluscloud.app/realms/cumulus/protocol/openid-connect", CidConfig.oidc(CidConfig.RU_ISSUER))
        assertEquals("https://id.cumuluscloud.app/realms/cumulus/protocol/openid-connect", CidConfig.oidc(CidConfig.OIDC_ISSUER + "/"))
        assertEquals("https://id.cumuluscloud.app", CidConfig.hostOf(CidConfig.OIDC_ISSUER))
    }

    @Test
    fun rejectsCleartextIssuerAndForeignRedirects() {
        assertThrows(IllegalArgumentException::class.java) { CidConfig(issuer = "http://id.cumuluscloud.app") }
        assertThrows(IllegalArgumentException::class.java) { CidConfig(fallbackIssuers = listOf("http://ru.id.cumuluscloud.app")) }
        assertThrows(IllegalArgumentException::class.java) { CidConfig(redirectUri = "otherapp:/cid/return") }
    }

    @Test
    fun fallsBackToTheRussiaMirrorWhenTheMainIssuerDoesNotAnswer() {
        val cid = CumulusId(config)
        assertEquals(mirror, cid.pickReachableIssuer({ it != CidConfig.DEFAULT_ISSUER }, { true }))
        assertEquals(mirror, cid.newPending().issuer)
        assertTrue(cid.authorizeUrl(cid.newPending()).startsWith("$mirror/realms/cumulus/protocol/openid-connect/auth?"))
    }

    @Test
    fun keepsTheMainIssuerWhenItAnswersOrNothingDoes() {
        val cid = CumulusId(config)
        assertEquals(CidConfig.DEFAULT_ISSUER, cid.pickReachableIssuer({ true }, { true }))
        assertEquals(CidConfig.DEFAULT_ISSUER, cid.pickReachableIssuer({ false }, { false }))
        assertEquals(CidConfig.DEFAULT_ISSUER, cid.newPending().issuer)
        // Main answers small requests but can't deliver the page (Cloudflare throttled): the mirror wins.
        assertEquals(mirror, cid.pickReachableIssuer({ it == mirror }, { true }))
        // Nothing delivers the page: fall back to whichever answers at all.
        assertEquals(mirror, cid.pickReachableIssuer({ false }, { it == mirror }))
    }

    @Test
    fun defaultProbeNeedsTheWholeSignInScript() {
        val small = CumulusId(config, { _, _, _, _ -> CidHttpResponse(200, ByteArray(1024)) })
        // Main and mirror both answer but neither delivers the script: the HEAD fallback picks main.
        assertEquals(CidConfig.DEFAULT_ISSUER, small.pickReachableIssuer())
        val mirrorOnly = CumulusId(config, { url, _, _, _ ->
            if (url.startsWith(mirror)) CidHttpResponse(200, ByteArray(CidConfig.MIN_SCRIPT_BYTES)) else throw IOException("stalled")
        })
        assertEquals(mirror, mirrorOnly.pickReachableIssuer())
    }

    @Test
    fun throttledNetworksTryTheMirrorFirstEvenWhenTheMainProbePasses() {
        val cid = CumulusId(config)
        assertEquals(mirror, cid.pickReachableIssuer({ true }, { true }, throttledNetwork = true))
        // The mirror is down: fall back to the main issuer.
        assertEquals(CidConfig.DEFAULT_ISSUER, cid.pickReachableIssuer({ it == CidConfig.DEFAULT_ISSUER }, { false }, throttledNetwork = true))
    }

    @Test
    fun onboardingAcceptsASignInFromTheLastDay() {
        val s = CidSession(CidProfile("cid_1", "ann", "", ""), verifiedAtMillis = 1_000L, issuer = CidConfig.DEFAULT_ISSUER)
        assertTrue(s.freshForOnboarding(1_000L + CidSession.ONBOARDING_WINDOW_MILLIS))
        assertFalse(s.freshForOnboarding(1_001L + CidSession.ONBOARDING_WINDOW_MILLIS))
        assertFalse(s.freshForOnboarding(999L))
        assertEquals("ann", s.profile.suggestedCallSign)
        assertEquals("ann.veil", CidProfile("c", "", "", "@ann.veil").suggestedCallSign)
        assertEquals("bob", CidProfile("c", "", "bob@example.org", "").suggestedCallSign)
    }
}

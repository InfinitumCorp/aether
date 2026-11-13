package com.infinitum.aether.cid

import com.infinitum.aether.cid.TestIdTokens.b64
import com.infinitum.aether.cid.TestIdTokens.claims
import com.infinitum.aether.cid.TestIdTokens.header
import com.infinitum.aether.cid.TestIdTokens.jwk
import com.infinitum.aether.cid.TestIdTokens.jwks
import com.infinitum.aether.cid.TestIdTokens.sign
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyPair

class IdTokenVerifierTest {
    private val certs = CidConfig.oidc(CidConfig.DEFAULT_ISSUER) + "/certs"
    private val mirrorCerts = CidConfig.oidc(CidConfig.RU_ISSUER) + "/certs"
    private val nowMillis = 1_800_000_000_000L
    private val now = nowMillis / 1000

    private lateinit var key: KeyPair
    private lateinit var other: KeyPair
    private lateinit var published: String
    private val fetched = mutableListOf<String>()

    @Before
    fun setUp() {
        key = TestIdTokens.keyPair()
        other = TestIdTokens.keyPair()
        published = jwks(jwk(key, "k1"))
    }

    private fun verifier() = IdTokenVerifier(CidConfig.DEFAULT_CLIENT, { url -> fetched += url; published }, nowMillis = { nowMillis })

    private fun token(c: JSONObject = claims(now), h: JSONObject = header(), kp: KeyPair = key) = sign(kp, h, c)

    private fun IdTokenVerifier.rejects(token: String?, nonce: String = "n-123", url: String = certs): String =
        assertThrows(IdTokenException::class.java) { verify(token, nonce, url) }.message.orEmpty()

    @Test
    fun acceptsAValidTokenAndKeysBySub() {
        val v = verifier().verify(token(), "n-123", certs)
        assertEquals("cid_7c1e", v.sub)
        assertEquals("ann@cumuluscloud.app", v.claims.getString("email"))
        assertEquals(listOf(certs), fetched)
    }

    @Test
    fun rejectsWrongIssuerAudienceNonceAndSubject() {
        val v = verifier()
        assertEquals("wrong issuer", v.rejects(token(claims(now).put("iss", "https://id.cumuluscloud.app"))))
        assertEquals("wrong audience", v.rejects(token(claims(now).put("aud", "architect"))))
        assertEquals("wrong audience", v.rejects(token(claims(now).put("aud", JSONArray().put(CidConfig.DEFAULT_CLIENT).put("architect")))))
        v.verify(token(claims(now).put("aud", JSONArray().put(CidConfig.DEFAULT_CLIENT).put("architect")).put("azp", CidConfig.DEFAULT_CLIENT)), "n-123", certs)
        assertEquals("nonce mismatch", v.rejects(token(), nonce = "n-other"))
        assertEquals("nonce mismatch", v.rejects(token(claims(now).put("nonce", "")), nonce = ""))
        assertEquals("no subject", v.rejects(token(claims(now).put("sub", ""))))
        assertEquals("no subject", v.rejects(token(claims(now).apply { remove("sub") })))
    }

    @Test
    fun expiryAllowsSixtySecondsOfSkew() {
        val v = verifier()
        v.verify(token(claims(now).put("exp", now - 59)), "n-123", certs)
        assertEquals("expired", v.rejects(token(claims(now).put("exp", now - 60))))
        assertEquals("expired", v.rejects(token(claims(now).apply { remove("exp") })))
        assertEquals("issued in the future", v.rejects(token(claims(now).put("iat", now + 3600))))
    }

    @Test
    fun rejectsForgedTokensAndOtherKinds() {
        val v = verifier()
        val p = token().split('.')
        val forged = p[0] + "." + b64(claims(now).put("sub", "cid_evil").toString().toByteArray()) + "." + p[2]
        assertEquals("bad signature", v.rejects(forged))
        assertEquals("bad signature", v.rejects(token(kp = other)))
        assertEquals("unexpected algorithm", v.rejects(token(h = header().put("alg", "HS256"))))
        assertEquals("unexpected algorithm", v.rejects(token(h = header().put("alg", "none"))))
        assertEquals("not an ID token", v.rejects(token(h = header().put("typ", "at+jwt"))))
        assertEquals("bad signature", v.rejects(p[0] + "." + p[1] + "."))
        assertEquals("no ID token", v.rejects(p[0] + "." + p[1]))
        assertEquals("no ID token", v.rejects(null))
        assertEquals("malformed ID token", v.rejects("not.a.jwt"))
    }

    @Test
    fun cachesKeysAndRefetchesOnceForAnUnknownKid() {
        val v = verifier()
        v.verify(token(), "n-123", certs)
        v.verify(token(), "n-123", certs)
        assertEquals(1, fetched.size)
        // Rotation: k2 is published next to k1. One refetch, then it verifies from the cache.
        published = jwks(jwk(key, "k1"), jwk(other, "k2"))
        v.verify(token(h = header("k2"), kp = other), "n-123", certs)
        v.verify(token(h = header("k2"), kp = other), "n-123", certs)
        assertEquals(2, fetched.size)
        // A kid nobody publishes: one refetch, then it fails.
        assertEquals("signing key not found", v.rejects(token(h = header("k9"), kp = other)))
        assertEquals(3, fetched.size)
    }

    @Test
    fun mirrorServesTheKeysButTheIssuerStaysCanonical() {
        val v = verifier()
        assertEquals("cid_7c1e", v.verify(token(), "n-123", mirrorCerts).sub)
        assertEquals(listOf("https://ru.id.cumuluscloud.app/realms/cumulus/protocol/openid-connect/certs"), fetched)
        assertEquals(
            "wrong issuer",
            v.rejects(token(claims(now).put("iss", "https://ru.id.cumuluscloud.app/realms/cumulus")), url = mirrorCerts),
        )
    }

    @Test
    fun ignoresKeysThatAreNotP256SigningKeys() {
        val k = jwk(key, "k1")
        published = jwks(
            JSONObject(k.toString()).put("use", "enc"),
            JSONObject(k.toString()).put("crv", "P-384"),
            JSONObject(k.toString()).put("y", k.getString("x")), // not on the curve
        )
        assertEquals("signing key not found", verifier().rejects(token()))
    }

    @Test
    fun unreachableKeysFailClosed() {
        val v = IdTokenVerifier(CidConfig.DEFAULT_CLIENT, { throw IOException("offline") }, nowMillis = { nowMillis })
        assertEquals("could not fetch signing keys", v.rejects(token()))
    }

    @Test
    fun joseToDerPadsHighBitsAndDropsLeadingZeros() {
        val raw = ByteArray(64)
        raw[0] = 0x80.toByte()
        raw[31] = 1
        raw[63] = 5
        val der = IdTokenVerifier.joseToDer(raw)
        assertEquals(0x30.toByte(), der[0])
        assertEquals((der.size - 2).toByte(), der[1])
        assertArrayEquals(byteArrayOf(0x02, 33, 0, 0x80.toByte()), der.copyOfRange(2, 6))
        assertArrayEquals(byteArrayOf(0x02, 1, 5), der.copyOfRange(der.size - 3, der.size))
        assertEquals(2 + 35 + 3, der.size)
    }
}

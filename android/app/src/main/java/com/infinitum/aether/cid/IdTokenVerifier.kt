package com.infinitum.aether.cid

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.util.Base64

/** The ID token failed a check. [message] is a short reason, never the token. */
class IdTokenException(message: String) : Exception(message)

/** The checked claims of a Cumulus://ID ID token. [sub] is the cid that keys the person. */
data class VerifiedIdToken(val sub: String, val claims: JSONObject)

/**
 * Verifies a Cumulus://ID ID token on the device before a session is kept, with the same checks
 * FabriC++ (data/IdTokenVerifier.kt, which mirrors app/src/core/cid.ts) makes:
 *
 *   ES256 signature against the issuer's JWKS (P-256 keys matched by kid; keys are cached per JWKS
 *   URL and fetched again once when a token names a kid the cache does not know, i.e. after a
 *   rotation), then iss, aud (this client; azp when there are several audiences), exp (60 s skew),
 *   nonce and a non-empty sub.
 *
 * [issuer] is always the canonical issuer: when the Russia mirror served the sign-in, the keys come
 * from the mirror's certs endpoint but the token still names https://id.cumuluscloud.app/realms/cumulus.
 *
 * Only java.security and org.json, no Android imports, so it runs in plain JVM unit tests. JWS
 * ES256 signatures are raw r||s (64 bytes); java.security wants DER, so [joseToDer] converts them.
 */
class IdTokenVerifier(
    private val clientId: String,
    /** Reads a JWKS URL and returns its body. Blocking; called off the main thread. */
    private val fetchJwks: (String) -> String,
    private val issuer: String = CidConfig.OIDC_ISSUER,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Key(val kid: String, val key: PublicKey)

    private val cache = HashMap<String, List<Key>>()

    fun verify(idToken: String?, nonce: String, jwksUrl: String): VerifiedIdToken {
        val parts = idToken?.split('.') ?: emptyList()
        if (parts.size != 3) fail("no ID token")
        val header: JSONObject
        val claims: JSONObject
        val signature: ByteArray
        try {
            header = JSONObject(String(b64urlDecode(parts[0]), Charsets.UTF_8))
            claims = JSONObject(String(b64urlDecode(parts[1]), Charsets.UTF_8))
            signature = b64urlDecode(parts[2])
        } catch (_: Exception) {
            fail("malformed ID token")
        }
        if (header.optString("alg") != "ES256") fail("unexpected algorithm")
        if (header.optString("typ", "JWT").equals("at+jwt", ignoreCase = true)) fail("not an ID token")
        if (signature.size != 64) fail("bad signature")

        val kid = if (header.has("kid")) header.optString("kid", "") else ""
        val cached = synchronized(cache) { cache[jwksUrl] }
        var key = pick(cached ?: load(jwksUrl), kid)
        // A kid we have not seen: the issuer may have rotated. Fetch the keys once more.
        if (key == null && cached != null) key = pick(load(jwksUrl), kid)
        if (key == null) fail("signing key not found")

        val signed = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(joseToDer(signature))
            }
        } catch (_: Exception) {
            false
        }
        if (!signed) fail("bad signature")

        if (claims.optString("iss") != issuer) fail("wrong issuer")
        val aud = when (val a = claims.opt("aud")) {
            is JSONArray -> (0 until a.length()).map { a.opt(it).toString() }
            is String -> listOf(a)
            else -> emptyList()
        }
        if (clientId !in aud || (aud.size > 1 && claims.optString("azp") != clientId)) fail("wrong audience")
        val now = nowMillis() / 1000
        val exp = claims.opt("exp")
        if (exp !is Number || exp.toLong() + SKEW_SECONDS <= now) fail("expired")
        val iat = claims.opt("iat")
        if (iat is Number && iat.toLong() - SKEW_SECONDS > now) fail("issued in the future")
        val got = claims.optString("nonce", "")
        if (nonce.isEmpty() || !MessageDigest.isEqual(got.toByteArray(), nonce.toByteArray())) fail("nonce mismatch")
        val sub = claims.opt("sub")
        if (sub !is String || sub.isEmpty()) fail("no subject")
        return VerifiedIdToken(sub, claims)
    }

    private fun load(jwksUrl: String): List<Key> {
        val text = try {
            fetchJwks(jwksUrl)
        } catch (_: Exception) {
            fail("could not fetch signing keys")
        }
        val keys = parseJwks(text)
        synchronized(cache) { cache[jwksUrl] = keys }
        return keys
    }

    private fun pick(keys: List<Key>, kid: String): PublicKey? =
        keys.firstOrNull { kid.isEmpty() || it.kid == kid }?.key

    private fun parseJwks(text: String): List<Key> {
        val keys = try {
            JSONObject(text).optJSONArray("keys") ?: JSONArray()
        } catch (_: Exception) {
            fail("bad JWKS")
        }
        return (0 until keys.length()).mapNotNull { i ->
            val k = keys.optJSONObject(i) ?: return@mapNotNull null
            if (k.optString("kty") != "EC" || k.optString("crv") != "P-256") return@mapNotNull null
            if (k.optString("use", "sig").ifEmpty { "sig" } != "sig") return@mapNotNull null
            if (k.optString("alg", "ES256").ifEmpty { "ES256" } != "ES256") return@mapNotNull null
            try {
                Key(k.optString("kid", ""), p256Key(b64urlDecode(k.getString("x")), b64urlDecode(k.getString("y"))))
            } catch (_: Exception) {
                null // A malformed key is skipped; the token must then match another one.
            }
        }
    }

    companion object {
        const val SKEW_SECONDS = 60L

        private fun fail(why: String): Nothing = throw IdTokenException(why)

        // P-256 (secp256r1), spelled out so no named-curve lookup is needed on any provider.
        private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        private val A = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
        private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
        private val G = ECPoint(
            BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
            BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
        )
        private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        val P256 = ECParameterSpec(EllipticCurve(ECFieldFp(P), A, B), G, N, 1)

        /** An EC public key from a JWK's x and y (32 bytes each), after checking the point is on P-256. */
        fun p256Key(xb: ByteArray, yb: ByteArray): PublicKey {
            require(xb.size == 32 && yb.size == 32) { "bad coordinate length" }
            val x = BigInteger(1, xb)
            val y = BigInteger(1, yb)
            require(x < P && y < P) { "coordinate out of range" }
            require(y.multiply(y).mod(P) == x.pow(3).add(A.multiply(x)).add(B).mod(P)) { "point not on P-256" }
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
        }

        /** JOSE ES256 signature (r||s, 32 bytes each) to DER SEQUENCE { INTEGER r, INTEGER s }. */
        fun joseToDer(raw: ByteArray): ByteArray {
            require(raw.size == 64) { "ES256 signatures are 64 bytes" }
            val r = derInteger(raw.copyOfRange(0, 32))
            val s = derInteger(raw.copyOfRange(32, 64))
            // At most 2 * 35 = 70 bytes of content, so the short length form fits.
            return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
        }

        private fun derInteger(v: ByteArray): ByteArray {
            var start = 0
            while (start < v.size - 1 && v[start] == 0.toByte()) start++
            val body = v.copyOfRange(start, v.size)
            val padded = if (body[0].toInt() and 0x80 != 0) byteArrayOf(0) + body else body
            return byteArrayOf(0x02, padded.size.toByte()) + padded
        }

        fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))
    }
}

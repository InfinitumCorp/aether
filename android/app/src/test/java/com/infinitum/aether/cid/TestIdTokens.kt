package com.infinitum.aether.cid

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Signs ID tokens the way Cumulus://ID does (ES256, JOSE r||s), with keys generated per test. */
object TestIdTokens {
    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(32, b.size)
        System.arraycopy(b, b.size - n, out, 32 - n, n)
        return out
    }

    fun jwk(kp: KeyPair, kid: String): JSONObject {
        val w = (kp.public as ECPublicKey).w
        return JSONObject().put("kty", "EC").put("crv", "P-256").put("use", "sig").put("alg", "ES256").put("kid", kid)
            .put("x", b64(fixed(w.affineX))).put("y", b64(fixed(w.affineY)))
    }

    fun jwks(vararg keys: JSONObject): String = JSONObject().put("keys", JSONArray().apply { keys.forEach { put(it) } }).toString()

    /** DER ECDSA signature to JOSE r||s. */
    fun derToJose(der: ByteArray): ByteArray {
        var off = 2
        val rLen = der[off + 1].toInt()
        val r = BigInteger(1, der.copyOfRange(off + 2, off + 2 + rLen))
        off += 2 + rLen
        val sLen = der[off + 1].toInt()
        val s = BigInteger(1, der.copyOfRange(off + 2, off + 2 + sLen))
        return fixed(r) + fixed(s)
    }

    fun header(kid: String = "k1"): JSONObject = JSONObject().put("alg", "ES256").put("typ", "JWT").put("kid", kid)

    fun claims(nowSeconds: Long = System.currentTimeMillis() / 1000, nonce: String = "n-123"): JSONObject = JSONObject()
        .put("iss", CidConfig.OIDC_ISSUER).put("aud", CidConfig.DEFAULT_CLIENT).put("sub", "cid_7c1e")
        .put("exp", nowSeconds + 300).put("iat", nowSeconds).put("nonce", nonce)
        .put("name", "Ann").put("preferred_username", "ann").put("email", "ann@cumuluscloud.app").put("handle", "@ann.veil")

    fun sign(kp: KeyPair, header: JSONObject, claims: JSONObject): String {
        val input = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return input + "." + b64(derToJose(der))
    }
}

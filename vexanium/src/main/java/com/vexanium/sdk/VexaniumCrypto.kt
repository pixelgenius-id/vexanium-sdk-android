package com.vexanium.sdk

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.math.BigInteger
import java.security.MessageDigest

// ── secp256k1 parameters ─────────────────────────────────────────────────────

internal fun secp256k1(): ECDomainParameters {
    val spec = SECNamedCurves.getByName("secp256k1")
    return ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
}

// ── Base58 with Antelope (RIPEMD160) checksum ────────────────────────────────

internal object VexBase58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        var n = BigInteger(1, bytes)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (n > BigInteger.ZERO) {
            val (q, r) = n.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(bytes.takeWhile { it == 0.toByte() }.size) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    fun decode(s: String): ByteArray {
        var n = BigInteger.ZERO
        for (c in s) {
            val idx = INDEX.getOrElse(c.code) { -1 }
            require(idx >= 0) { "Invalid Base58 char: $c" }
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(idx.toLong()))
        }
        val raw = n.toByteArray().let { if (it[0] == 0.toByte() && it.size > 1) it.drop(1).toByteArray() else it }
        val leading = s.takeWhile { it == ALPHABET[0] }.count()
        return ByteArray(leading) + raw
    }

    /** 4-byte RIPEMD160 checksum (optionally prefixed with keyType like "K1"). */
    fun checksum(data: ByteArray, keyType: String? = null): ByteArray {
        val digest = RIPEMD160Digest()
        if (keyType != null) {
            val prefix = keyType.toByteArray(Charsets.US_ASCII)
            digest.update(prefix, 0, prefix.size)
        }
        digest.update(data, 0, data.size)
        val out = ByteArray(20)
        digest.doFinal(out, 0)
        return out.copyOf(4)
    }

    fun encodeCheck(data: ByteArray, keyType: String? = null) = encode(data + checksum(data, keyType))

    fun decodeCheck(s: String, keyType: String? = null): ByteArray {
        val full = decode(s)
        val payload = full.dropLast(4).toByteArray()
        val cs = full.takeLast(4).toByteArray()
        require(cs.contentEquals(checksum(payload, keyType))) { "Checksum mismatch for $s" }
        return payload
    }
}

// ── Key management ───────────────────────────────────────────────────────────

class VexaniumKey(val privateKeyBytes: ByteArray) {

    private val privInt: BigInteger = BigInteger(1, privateKeyBytes)

    /** Compressed 33-byte public key. */
    val publicKeyBytes: ByteArray by lazy {
        secp256k1().g.multiply(privInt).normalize().getEncoded(true)
    }

    /**
     * Public key in Vexanium/Antelope format: "VEX" + Base58Check(compressed pubkey)
     * Checksum = RIPEMD160(pubkey)[:4] (no key-type prefix for legacy pubkey format).
     */
    val publicKeyString: String get() = "VEX" + VexBase58.encodeCheck(publicKeyBytes)

    /**
     * Sign a 32-byte digest.
     * Returns Antelope-encoded signature string: "SIG_K1_..."
     */
    fun sign(digest: ByteArray): String {
        val params = secp256k1()
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privInt, params))
        val (r, rawS) = signer.generateSignature(digest)
        val s = if (rawS > params.n.shiftRight(1)) params.n - rawS else rawS

        val recId = (0..3).firstOrNull { id ->
            tryRecoverPubKey(digest, r, s, id, params)?.contentEquals(publicKeyBytes) == true
        } ?: error("Cannot recover public key from signature")

        val sigBytes = ByteArray(65)
        sigBytes[0] = (recId + 31).toByte()
        r.to32Bytes().copyInto(sigBytes, 1)
        s.to32Bytes().copyInto(sigBytes, 33)
        return "SIG_K1_" + VexBase58.encodeCheck(sigBytes, "K1")
    }

    companion object {
        /**
         * Import from:
         * - WIF (starts with "5", "K", or "L")
         * - PVT_K1_ (modern Antelope format)
         * - Raw hex (64 chars)
         */
        fun fromWif(input: String): VexaniumKey = when {
            input.startsWith("PVT_K1_") -> {
                VexaniumKey(VexBase58.decodeCheck(input.removePrefix("PVT_K1_"), "K1"))
            }
            input.startsWith("5") || input.startsWith("K") || input.startsWith("L") -> {
                // Legacy WIF: version(0x80) + key(32) + [compressed_flag(1)] + sha256d_checksum(4)
                val decoded = VexBase58.decode(input)
                val withoutVersion = decoded.drop(1) // remove 0x80
                val payload = withoutVersion.dropLast(4) // remove checksum
                val key = if (input.startsWith("K") || input.startsWith("L"))
                    payload.dropLast(1).toByteArray() // remove compression flag (0x01)
                else payload.toByteArray()
                VexaniumKey(key)
            }
            else -> VexaniumKey(vexHexToBytes(input.removePrefix("0x")))
        }
    }
}

// ── Signing digest ───────────────────────────────────────────────────────────

/**
 * Antelope signing digest:
 * SHA256(chain_id_bytes + serialized_tx_bytes + 32_zero_bytes)
 *
 * The 32 trailing zeros represent the empty context_free_data hash
 * (per eosjs reference implementation).
 */
internal fun vexSigningDigest(chainId: String, packedTx: ByteArray): ByteArray {
    val chainIdBytes = vexHexToBytes(chainId)
    require(chainIdBytes.size == 32) { "chain_id must be 32 bytes" }
    val msg = chainIdBytes + packedTx + ByteArray(32)
    return MessageDigest.getInstance("SHA-256").digest(msg)
}

// ── Recovery helper ──────────────────────────────────────────────────────────

private fun tryRecoverPubKey(
    digest: ByteArray,
    r: BigInteger,
    s: BigInteger,
    recId: Int,
    params: ECDomainParameters,
): ByteArray? = runCatching {
    val n = params.n
    val x = r.add(BigInteger.valueOf((recId / 2).toLong()).multiply(n))
    if (x >= params.curve.field.characteristic) return null

    val compressedR = ByteArray(33).also { buf ->
        buf[0] = (0x02 + (recId and 1)).toByte()
        x.to32Bytes().copyInto(buf, 1)
    }
    val R = params.curve.decodePoint(compressedR)
    if (!R.multiply(n).isInfinity) return null

    val e = BigInteger(1, digest)
    val rInv = r.modInverse(n)
    // Q = r_inv * (s * R - e * G)
    val Q = R.multiply(s.multiply(rInv).mod(n))
        .add(params.g.multiply(e.negate().mod(n).multiply(rInv).mod(n)))
        .normalize()
    if (Q.isInfinity) null else Q.getEncoded(true)
}.getOrNull()

// ── Utilities ────────────────────────────────────────────────────────────────

internal fun vexHexToBytes(hex: String): ByteArray {
    val h = if (hex.length % 2 == 0) hex else "0$hex"
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toVexHex() = joinToString("") { "%02x".format(it) }

private fun BigInteger.to32Bytes(): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == 32 -> raw
        raw.size > 32 -> raw.takeLast(32).toByteArray()
        else -> ByteArray(32 - raw.size) + raw
    }
}

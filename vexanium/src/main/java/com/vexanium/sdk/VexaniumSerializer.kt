package com.vexanium.sdk

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.TimeZone

// ── Binary serializer for Antelope transactions ──────────────────────────────

internal class VexSerializer {
    private val buf = ByteArrayOutputStream()

    fun uint8(v: Int) { buf.write(v and 0xFF) }

    fun uint16(v: Int) {
        buf.write(v and 0xFF)
        buf.write((v shr 8) and 0xFF)
    }

    fun uint32(v: Long) {
        buf.write((v and 0xFF).toInt())
        buf.write(((v shr 8) and 0xFF).toInt())
        buf.write(((v shr 16) and 0xFF).toInt())
        buf.write(((v shr 24) and 0xFF).toInt())
    }

    fun int64(v: Long) {
        uint32(v and 0xFFFFFFFFL)
        uint32((v ushr 32) and 0xFFFFFFFFL)
    }

    fun uint64(v: Long) = int64(v)

    fun varuint32(v: Long) {
        var n = v
        while (true) {
            val b = (n and 0x7F).toInt()
            n = n ushr 7
            if (n == 0L) { buf.write(b); break } else buf.write(b or 0x80)
        }
    }

    fun byteArray(data: ByteArray) {
        varuint32(data.size.toLong())
        buf.write(data)
    }

    fun rawBytes(data: ByteArray) { buf.write(data) }

    /** Antelope name: 64-bit packed value, stored little-endian. */
    fun name(n: String) { uint64(packName(n)) }

    fun string(s: String) { byteArray(s.toByteArray(Charsets.UTF_8)) }

    /**
     * Antelope asset: int64 (amount * 10^precision) + 8-byte symbol
     * (1 byte precision + up to 7 ASCII symbol chars + null padding).
     *
     * Example: "1.0000 VEX" → amount=10000, precision=4, symbol="VEX"
     */
    fun asset(quantity: String) {
        val (amountStr, symbol) = quantity.trim().split(" ").let {
            require(it.size == 2) { "Invalid asset format: $quantity" }
            it[0] to it[1]
        }
        val dotIdx = amountStr.indexOf('.')
        val precision = if (dotIdx >= 0) amountStr.length - dotIdx - 1 else 0
        val amount = amountStr.replace(".", "").toLong()
        int64(amount)
        buf.write(precision and 0xFF)
        val symBytes = symbol.toByteArray(Charsets.US_ASCII)
        require(symBytes.size <= 7) { "Symbol too long: $symbol" }
        buf.write(symBytes)
        repeat(7 - symBytes.size) { buf.write(0) }
    }

    fun toBytes(): ByteArray = buf.toByteArray()

    companion object {
        private const val CHARMAP = ".12345abcdefghijklmnopqrstuvwxyz"

        fun packName(name: String): Long {
            var v = 0L
            for (i in 0 until minOf(name.length, 12)) {
                val idx = CHARMAP.indexOf(name[i]).toLong()
                require(idx >= 0) { "Invalid name character '${name[i]}' in '$name'" }
                v = v or (idx shl (64 - 5 * (i + 1)))
            }
            if (name.length == 13) {
                val idx = CHARMAP.indexOf(name[12]).toLong()
                require(idx >= 0) { "Invalid name character '${name[12]}' in '$name'" }
                v = v or (idx and 0x0F)
            }
            return v
        }
    }
}

// ── Transaction structures ────────────────────────────────────────────────────

data class VexAuthorization(val actor: String, val permission: String)

internal data class PackedAction(
    val account: String,
    val name: String,
    val authorization: List<VexAuthorization>,
    val data: ByteArray,
)

/**
 * Pack the transfer action data for eosio.token::transfer (or vexanium.token::transfer).
 */
internal fun packTransferData(from: String, to: String, quantity: String, memo: String): ByteArray {
    val s = VexSerializer()
    s.name(from)
    s.name(to)
    s.asset(quantity)
    s.string(memo)
    return s.toBytes()
}

/**
 * Serialize a transaction to bytes (without signatures).
 * This is the payload used in the signing digest and in push_transaction.
 */
internal fun packTransaction(
    expirationEpoch: Long,
    refBlockNum: Int,
    refBlockPrefix: Long,
    actions: List<PackedAction>,
): ByteArray {
    val s = VexSerializer()
    s.uint32(expirationEpoch)
    s.uint16(refBlockNum)
    s.uint32(refBlockPrefix)
    s.varuint32(0L) // max_net_usage_words
    s.uint8(0)      // max_cpu_usage_ms
    s.varuint32(0L) // delay_sec
    s.varuint32(0L) // context_free_actions (empty array)
    s.varuint32(actions.size.toLong())
    for (action in actions) {
        s.name(action.account)
        s.name(action.name)
        s.varuint32(action.authorization.size.toLong())
        for (auth in action.authorization) {
            s.name(auth.actor)
            s.name(auth.permission)
        }
        s.byteArray(action.data)
    }
    s.varuint32(0L) // transaction_extensions
    return s.toBytes()
}

internal fun isoToEpochSeconds(iso: String): Long {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    // Drop sub-second part if present
    val clean = iso.substringBefore('.')
    return (sdf.parse(clean)?.time ?: 0L) / 1000L
}

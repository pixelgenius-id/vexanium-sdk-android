package com.vexanium.sdk

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

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
 * Pack the transfer action data for vex.token::transfer.
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

internal fun packBuyRamBytesData(payer: String, receiver: String, bytes: Int): ByteArray {
    val s = VexSerializer()
    s.name(payer)
    s.name(receiver)
    s.uint32(bytes.toLong())
    return s.toBytes()
}

internal fun packSellRamData(account: String, bytes: Long): ByteArray {
    val s = VexSerializer()
    s.name(account)
    s.int64(bytes)
    return s.toBytes()
}

internal fun packDelegateBwData(
    from: String,
    receiver: String,
    stakeNet: String,
    stakeCpu: String,
    transfer: Boolean = false,
): ByteArray {
    val s = VexSerializer()
    s.name(from)
    s.name(receiver)
    s.asset(stakeNet)
    s.asset(stakeCpu)
    s.uint8(if (transfer) 1 else 0)
    return s.toBytes()
}

internal fun packUndelegateBwData(
    from: String,
    receiver: String,
    unstakeNet: String,
    unstakeCpu: String,
): ByteArray {
    val s = VexSerializer()
    s.name(from)
    s.name(receiver)
    s.asset(unstakeNet)
    s.asset(unstakeCpu)
    return s.toBytes()
}

/** Producers list MUST be sorted alphabetically per Antelope consensus rules. */
internal fun packVoteProducerData(voter: String, proxy: String, producers: List<String>): ByteArray {
    val s = VexSerializer()
    s.name(voter)
    s.name(proxy)
    val sorted = producers.sorted()
    s.varuint32(sorted.size.toLong())
    for (p in sorted) s.name(p)
    return s.toBytes()
}

internal fun packPowerupData(
    payer: String,
    receiver: String,
    days: Int,
    netFrac: Long,
    cpuFrac: Long,
    maxPayment: String,
): ByteArray {
    val s = VexSerializer()
    s.name(payer)
    s.name(receiver)
    s.uint32(days.toLong())
    s.int64(netFrac)
    s.int64(cpuFrac)
    s.asset(maxPayment)
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

/**
 * Encode action data using the contract's live ABI definition.
 * [abi] is the full response from get_abi (contains nested "abi" object with structs/actions/types).
 */
internal fun encodeAbiAction(abi: JSONObject, actionName: String, data: Map<String, Any?>): ByteArray {
    val abiContent = abi.optJSONObject("abi") ?: abi

    val typeAliases = mutableMapOf<String, String>()
    abiContent.optJSONArray("types")?.let { arr ->
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            typeAliases[t.getString("new_type_name")] = t.getString("type")
        }
    }

    val structMap = mutableMapOf<String, JSONObject>()
    abiContent.optJSONArray("structs")?.let { arr ->
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            structMap[s.getString("name")] = s
        }
    }

    var actionType = actionName
    abiContent.optJSONArray("actions")?.let { arr ->
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.getString("name") == actionName) { actionType = a.getString("type"); break }
        }
    }

    val s = VexSerializer()
    abiEncodeStruct(s, actionType, data, structMap, typeAliases)
    return s.toBytes()
}

private fun abiResolveType(type: String, aliases: Map<String, String>): String {
    var t = type
    val seen = mutableSetOf<String>()
    while (aliases.containsKey(t) && seen.add(t)) t = aliases[t]!!
    return t
}

private fun abiEncodeStruct(
    s: VexSerializer,
    typeName: String,
    data: Map<String, Any?>,
    structs: Map<String, JSONObject>,
    aliases: Map<String, String>,
) {
    val struct = structs[typeName]
        ?: throw IllegalArgumentException("Unknown ABI struct type: $typeName")
    val base = struct.optString("base", "").trim()
    if (base.isNotEmpty()) abiEncodeStruct(s, base, data, structs, aliases)
    val fields = struct.optJSONArray("fields") ?: return
    for (i in 0 until fields.length()) {
        val f = fields.getJSONObject(i)
        val name = f.getString("name")
        abiEncodeField(s, name, abiResolveType(f.getString("type"), aliases), data[name], structs, aliases)
    }
}

private fun abiEncodeField(
    s: VexSerializer,
    fieldName: String,
    fieldType: String,
    value: Any?,
    structs: Map<String, JSONObject>,
    aliases: Map<String, String>,
) {
    when {
        fieldType.endsWith("[]") -> {
            val elemType = abiResolveType(fieldType.dropLast(2), aliases)
            val list: List<Any?> = when (value) {
                is List<*>  -> value
                is JSONArray -> (0 until value.length()).map { value.get(it) }
                null        -> emptyList()
                else        -> throw IllegalArgumentException("Expected array for '$fieldName'")
            }
            s.varuint32(list.size.toLong())
            for (elem in list) abiEncodeField(s, fieldName, elemType, elem, structs, aliases)
        }
        fieldType.endsWith("?") -> {
            val elemType = abiResolveType(fieldType.dropLast(1), aliases)
            if (value == null) s.uint8(0)
            else { s.uint8(1); abiEncodeField(s, fieldName, elemType, value, structs, aliases) }
        }
        else -> abiEncodeScalar(s, fieldName, fieldType, value, structs, aliases)
    }
}

private fun abiEncodeScalar(
    s: VexSerializer,
    fieldName: String,
    fieldType: String,
    value: Any?,
    structs: Map<String, JSONObject>,
    aliases: Map<String, String>,
) {
    fun str() = value?.toString()?.ifBlank { null }
        ?: throw IllegalArgumentException("Missing field '$fieldName' (type $fieldType)")
    fun num() = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
            ?: throw IllegalArgumentException("Non-numeric value for '$fieldName': $value")
        null -> throw IllegalArgumentException("Missing numeric field '$fieldName'")
        else -> throw IllegalArgumentException("Expected number for '$fieldName'")
    }
    when (fieldType) {
        "bool" -> s.uint8(when (value) {
            is Boolean -> if (value) 1 else 0
            is Number  -> if (value.toInt() != 0) 1 else 0
            is String  -> if (value.lowercase() in setOf("true", "1", "yes")) 1 else 0
            else -> 0
        })
        "uint8", "int8"   -> s.uint8(num().toInt())
        "uint16", "int16" -> s.uint16(num().toInt())
        "uint32", "int32" -> s.uint32(num())
        "uint64"          -> s.uint64(num())
        "int64"           -> s.int64(num())
        "float32" -> s.uint32(java.lang.Float.floatToRawIntBits(str().toFloat()).toLong() and 0xFFFFFFFFL)
        "float64" -> s.int64(java.lang.Double.doubleToRawLongBits(str().toDouble()))
        "name"    -> s.name(str())
        "string"  -> s.string(value?.toString() ?: "")
        "asset"   -> s.asset(str())
        "symbol"  -> {
            val sym = str()
            val comma = sym.indexOf(',')
            val precision = if (comma >= 0) sym.substring(0, comma).toInt() else 0
            val code = (if (comma >= 0) sym.substring(comma + 1) else sym).toByteArray(Charsets.US_ASCII)
            s.uint8(precision); s.rawBytes(code); repeat(7 - code.size) { s.uint8(0) }
        }
        "symbol_code" -> {
            val cb = str().toByteArray(Charsets.US_ASCII)
            s.rawBytes(cb); repeat(8 - cb.size) { s.uint8(0) }
        }
        "checksum256" -> {
            val hex = str().removePrefix("0x")
            s.rawBytes(ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        }
        "bytes" -> {
            val hex = str().removePrefix("0x")
            s.byteArray(ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        }
        "time_point"           -> s.int64(num())
        "time_point_sec"       -> s.uint32(num())
        "block_timestamp_type" -> s.uint32(num())
        else -> {
            if (structs.containsKey(fieldType)) {
                @Suppress("UNCHECKED_CAST")
                val nested = (value as? Map<*, *>) as? Map<String, Any?>
                    ?: throw IllegalArgumentException("Expected object for '$fieldName' (type $fieldType)")
                abiEncodeStruct(s, fieldType, nested, structs, aliases)
            } else {
                throw IllegalArgumentException("Unsupported ABI type '$fieldType' for field '$fieldName'")
            }
        }
    }
}

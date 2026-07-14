package com.vexanium.sdk

import org.json.JSONObject

// ── API response models ──────────────────────────────────────────────────────

data class VexChainInfo(
    val chainId: String,
    val headBlockNum: Long,
    val headBlockId: String,
    val headBlockTime: String,
    val lastIrreversibleBlockNum: Long,
    val lastIrreversibleBlockId: String,
) {
    companion object {
        fun from(json: JSONObject) = VexChainInfo(
            chainId = json.getString("chain_id"),
            headBlockNum = json.getLong("head_block_num"),
            headBlockId = json.getString("head_block_id"),
            headBlockTime = json.getString("head_block_time"),
            lastIrreversibleBlockNum = json.getLong("last_irreversible_block_num"),
            lastIrreversibleBlockId = json.getString("last_irreversible_block_id"),
        )
    }
}

data class VexBlock(
    val blockNum: Long,
    val id: String,
    val timestamp: String,
    val refBlockPrefix: Long,
) {
    companion object {
        fun from(json: JSONObject) = VexBlock(
            blockNum = json.getLong("block_num"),
            id = json.getString("id"),
            timestamp = json.getString("timestamp"),
            refBlockPrefix = json.getLong("ref_block_prefix"),
        )
    }
}

data class VexResourceLimit(val used: Long, val available: Long, val max: Long)

data class VexAccountInfo(
    val accountName: String,
    val ramQuota: Long,
    val ramUsage: Long,
    val cpuWeight: Long,
    val netWeight: Long,
    val cpuLimit: VexResourceLimit,
    val netLimit: VexResourceLimit,
) {
    companion object {
        fun from(json: JSONObject) = VexAccountInfo(
            accountName = json.getString("account_name"),
            ramQuota = json.optLong("ram_quota", 0L),
            ramUsage = json.optLong("ram_usage", 0L),
            cpuWeight = json.optLong("cpu_weight", 0L),
            netWeight = json.optLong("net_weight", 0L),
            cpuLimit = json.optJSONObject("cpu_limit")?.let {
                VexResourceLimit(it.optLong("used"), it.optLong("available"), it.optLong("max"))
            } ?: VexResourceLimit(0, 0, 0),
            netLimit = json.optJSONObject("net_limit")?.let {
                VexResourceLimit(it.optLong("used"), it.optLong("available"), it.optLong("max"))
            } ?: VexResourceLimit(0, 0, 0),
        )
    }
}

// ── High-level domain models ─────────────────────────────────────────────────

data class VexBalance(
    val account: String,
    val amount: Double,
    val symbol: String,
) {
    override fun toString() = "${formatAmount(amount)} $symbol"

    private fun formatAmount(v: Double): String {
        val s = "%.4f".format(v)
        return s.trimEnd('0').trimEnd('.')
            .let { if (!it.contains('.')) it else it }
    }
}

/**
 * A single action trace from a submitted transaction.
 *
 * With `ACTION_RETURN_VALUE` enabled on the chain, contract actions can return a value
 * that appears in the trace as [returnValueHex] (raw bytes) and, when the node was able
 * to decode using the contract ABI, [returnValueData] (decoded JSON as a string).
 *
 * For actions that do not declare a return type, both fields are blank / null.
 */
data class VexActionTrace(
    val contract: String,
    val actionName: String,
    /** Raw hex bytes of the action's return value, or "" if none. */
    val returnValueHex: String,
    /** Decoded return value as a JSON string, or null if the node did not decode it. */
    val returnValueData: String?,
    /** stdout printed by the contract action (via `print`), or "" if none. */
    val console: String,
) {
    companion object {
        fun from(json: JSONObject): VexActionTrace {
            val act = json.optJSONObject("act") ?: JSONObject()
            val decoded = when {
                json.has("return_value_data") -> json.opt("return_value_data")?.toString()
                else -> null
            }
            return VexActionTrace(
                contract = act.optString("account", ""),
                actionName = act.optString("name", ""),
                returnValueHex = json.optString("return_value_hex_data", ""),
                returnValueData = decoded?.takeIf { it.isNotBlank() && it != "null" },
                console = json.optString("console", ""),
            )
        }
    }
}

data class VexTransferResult(
    val transactionId: String,
    val blockNum: Long,
    val blockTime: String,
    /** Action traces returned by the node, including any ACTION_RETURN_VALUE payloads. */
    val traces: List<VexActionTrace> = emptyList(),
) {
    /** First trace's raw return value hex, or null if no action returned data. */
    val returnValueHex: String? get() = traces.firstOrNull()?.returnValueHex?.takeIf { it.isNotBlank() }

    /** First trace's decoded return value (JSON string), or null if the node did not decode it. */
    val returnValueData: String? get() = traces.firstOrNull()?.returnValueData
}

/**
 * A single fungible token held by an account, as reported by Hyperion `/v2/state/get_tokens`.
 * Works for VEX (native) and any ecosystem token published by a token contract.
 */
data class VexToken(
    val symbol: String,
    val amount: Double,
    val precision: Int,
    val contract: String,
) {
    /** Antelope asset string, e.g. "1.5000 VEX". */
    fun asAsset(): String = "%.${precision}f %s".format(amount, symbol)

    companion object {
        fun from(json: JSONObject) = VexToken(
            symbol = json.optString("symbol", ""),
            amount = json.optDouble("amount", 0.0),
            precision = json.optInt("precision", 4),
            contract = json.optString("contract", ""),
        )
    }
}

data class VexTableResult(
    val rows: List<JSONObject>,
    val more: Boolean,
    val nextKey: String,
) {
    companion object {
        fun from(json: JSONObject) = VexTableResult(
            rows = json.optJSONArray("rows")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } ?: emptyList(),
            more = json.optBoolean("more", false),
            nextKey = json.optString("next_key", ""),
        )
    }
}

data class VexTransferRequest(
    val from: String,
    val to: String,
    /** Formatted as "1.0000 VEX" */
    val quantity: String,
    val memo: String = "",
    val permission: String = "active",
)

// ── Hyperion history models ───────────────────────────────────────────────────

data class VexAction(
    val transactionId: String,
    val blockNum: Long,
    val timestamp: String,
    val contract: String,
    val action: String,
    val from: String,
    val to: String,
    val quantity: String,
    val symbol: String,
    val memo: String,
    val irreversible: Boolean,
) {
    companion object {
        fun from(json: JSONObject): VexAction {
            val act = json.optJSONObject("act") ?: JSONObject()
            val data = act.optJSONObject("data") ?: JSONObject()
            val qty = data.optString("quantity", "0 ").trim()
            val parts = qty.split(" ")
            return VexAction(
                transactionId = json.optString("trx_id", ""),
                blockNum = json.optLong("block_num", 0),
                timestamp = json.optString("@timestamp", ""),
                contract = act.optString("account", ""),
                action = act.optString("name", ""),
                from = data.optString("from", ""),
                to = data.optString("to", ""),
                quantity = parts[0],
                symbol = parts.getOrElse(1) { "" },
                memo = data.optString("memo", ""),
                irreversible = json.optBoolean("irreversible", false),
            )
        }
    }
}

data class VexTransaction(
    val transactionId: String,
    val blockNum: Long,
    val blockTime: String,
    val irreversible: Boolean,
    val actions: List<VexAction>,
) {
    companion object {
        fun from(json: JSONObject): VexTransaction {
            val actionsArr = json.optJSONArray("actions")
            return VexTransaction(
                transactionId = json.optString("trx_id", ""),
                blockNum = json.optLong("block_num", 0),
                blockTime = json.optString("@timestamp", ""),
                irreversible = json.optBoolean("irreversible", false),
                actions = (0 until (actionsArr?.length() ?: 0))
                    .map { VexAction.from(actionsArr!!.getJSONObject(it)) },
            )
        }
    }
}

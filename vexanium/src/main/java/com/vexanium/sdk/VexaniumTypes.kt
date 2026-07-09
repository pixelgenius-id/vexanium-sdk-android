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

data class VexTransferResult(
    val transactionId: String,
    val blockNum: Long,
    val blockTime: String,
)

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

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

data class VexTransferRequest(
    val from: String,
    val to: String,
    /** Formatted as "1.0000 VEX" */
    val quantity: String,
    val memo: String = "",
    val permission: String = "active",
)

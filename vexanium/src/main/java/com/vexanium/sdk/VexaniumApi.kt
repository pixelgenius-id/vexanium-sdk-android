package com.vexanium.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Antelope Chain API client (Spring-compatible).
 * All calls are blocking — invoke from a coroutine with Dispatchers.IO.
 *
 * @param nodeUrl  Chain API base URL, e.g. "https://api.yournode.com"
 */
class VexaniumApi(
    val nodeUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = "application/json".toMediaType()

    // ── Chain endpoints ──────────────────────────────────────────────────────

    fun getInfo(): VexChainInfo {
        return VexChainInfo.from(post("/v1/chain/get_info", JSONObject()))
    }

    fun getBlock(blockNum: Long): VexBlock {
        return VexBlock.from(post("/v1/chain/get_block", JSONObject().put("block_num_or_id", blockNum)))
    }

    fun getAccount(accountName: String): VexAccountInfo {
        return VexAccountInfo.from(post("/v1/chain/get_account", JSONObject().put("account_name", accountName)))
    }

    /**
     * Returns balance strings like ["1.5000 VEX"].
     * Pass [symbol] to filter by token, or null for all.
     */
    fun getCurrencyBalance(
        contract: String,
        account: String,
        symbol: String? = null,
    ): List<String> {
        val req = JSONObject().put("code", contract).put("account", account)
        if (symbol != null) req.put("symbol", symbol)
        val arr = postArray("/v1/chain/get_currency_balance", req)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Push a signed transaction.
     * [packedTrxHex] = hex-encoded serialized transaction (without signatures).
     * [signatures]   = list of "SIG_K1_..." strings.
     */
    fun pushTransaction(packedTrxHex: String, signatures: List<String>): VexTransferResult {
        val sigsArray = JSONArray().also { arr -> signatures.forEach { arr.put(it) } }
        val req = JSONObject()
            .put("signatures", sigsArray)
            .put("compression", 0)
            .put("packed_context_free_data", "")
            .put("packed_trx", packedTrxHex)
        val body = post("/v1/chain/push_transaction", req)
        val processed = body.optJSONObject("processed")
        return VexTransferResult(
            transactionId = body.optString("transaction_id", body.optString("id", "")),
            blockNum = processed?.optLong("block_num") ?: 0L,
            blockTime = processed?.optString("block_time") ?: "",
        )
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    internal fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("$nodeUrl$path")
            .post(body.toString().toRequestBody(json))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: throw VexaniumException("Empty response from $path")
            val obj = JSONObject(text)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                val details = err.optJSONObject("details")?.optString("message") ?: ""
                throw VexaniumException("RPC error: ${err.optString("what")} $details".trim())
            }
            return obj
        }
    }

    internal fun get(path: String): JSONObject {
        val request = Request.Builder().url("$nodeUrl$path").get().build()
        httpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: throw VexaniumException("Empty response from $path")
            return JSONObject(text)
        }
    }

    private fun postArray(path: String, body: JSONObject): JSONArray {
        val request = Request.Builder()
            .url("$nodeUrl$path")
            .post(body.toString().toRequestBody(json))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: throw VexaniumException("Empty response from $path")
            return JSONArray(text)
        }
    }

    companion object {
        const val SYSTEM_CONTRACT = "vexcore"
        const val TOKEN_CONTRACT = "vex.token"
        const val NATIVE_SYMBOL = "VEX"
    }
}

/**
 * Hyperion History API client (v2).
 * Compatible with Spring/Leap 5.x nodes — use this instead of the old /v1/history plugin.
 *
 * @param hyperionUrl  Hyperion base URL, e.g. "https://hyperion.yournode.com"
 */
class VexaniumHyperion(
    val hyperionUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {

    /**
     * Get paginated actions for an account.
     *
     * @param account   Account name
     * @param filter    Optional action filter, e.g. "vex.token:transfer"
     * @param limit     Number of results (max 100 per request)
     * @param skip      Offset for pagination
     * @param after     ISO timestamp lower bound, e.g. "2024-01-01T00:00:00"
     * @param before    ISO timestamp upper bound
     */
    fun getActions(
        account: String,
        filter: String? = null,
        limit: Int = 20,
        skip: Int = 0,
        after: String? = null,
        before: String? = null,
        sort: String = "desc",
    ): List<VexAction> {
        val sb = StringBuilder("/v2/history/get_actions?account=$account&limit=$limit&skip=$skip&sort=$sort")
        if (filter != null) sb.append("&filter=$filter")
        if (after != null) sb.append("&after=$after")
        if (before != null) sb.append("&before=$before")
        val body = get(sb.toString())
        val arr = body.optJSONArray("actions") ?: return emptyList()
        return (0 until arr.length()).map { VexAction.from(arr.getJSONObject(it)) }
    }

    /**
     * Get token transfer history for an account.
     * Shorthand for getActions with filter = "vex.token:transfer".
     */
    fun getTransfers(
        account: String,
        symbol: String? = null,
        limit: Int = 20,
        skip: Int = 0,
        sort: String = "desc",
    ): List<VexAction> {
        val sb = StringBuilder("/v2/history/get_actions?account=$account&filter=vex.token%3Atransfer&limit=$limit&skip=$skip&sort=$sort")
        if (symbol != null) sb.append("&symbol=$symbol")
        val body = get(sb.toString())
        val arr = body.optJSONArray("actions") ?: return emptyList()
        return (0 until arr.length()).map { VexAction.from(arr.getJSONObject(it)) }
    }

    /**
     * Get a transaction by its ID.
     */
    fun getTransaction(txId: String): VexTransaction {
        val body = get("/v2/history/get_transaction?id=$txId")
        return VexTransaction.from(body)
    }

    /**
     * Check Hyperion node health.
     * Returns true if the node is healthy and in sync.
     */
    fun isHealthy(): Boolean = runCatching {
        val body = get("/v2/health")
        body.optString("status") == "OK"
    }.getOrDefault(false)

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun get(path: String): JSONObject {
        val request = Request.Builder().url("$hyperionUrl$path").get().build()
        httpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: throw VexaniumException("Empty response from $path")
            if (!resp.isSuccessful) throw VexaniumException("Hyperion error ${resp.code}: $text")
            return JSONObject(text)
        }
    }
}

class VexaniumException(message: String) : Exception(message)

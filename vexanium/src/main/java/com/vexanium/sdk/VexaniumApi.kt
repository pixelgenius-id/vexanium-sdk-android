package com.vexanium.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Low-level Antelope chain API client.
 * All calls are blocking — call from a coroutine with Dispatchers.IO.
 */
class VexaniumApi(
    private val nodeUrl: String = DEFAULT_NODE,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = "application/json".toMediaType()

    // ── Chain endpoints ──────────────────────────────────────────────────────

    fun getInfo(): VexChainInfo {
        val body = post("/v1/chain/get_info", JSONObject())
        return VexChainInfo.from(body)
    }

    fun getBlock(blockNum: Long): VexBlock {
        val body = post("/v1/chain/get_block", JSONObject().put("block_num_or_id", blockNum))
        return VexBlock.from(body)
    }

    fun getAccount(accountName: String): VexAccountInfo {
        val body = post("/v1/chain/get_account", JSONObject().put("account_name", accountName))
        return VexAccountInfo.from(body)
    }

    /**
     * Returns list of balance strings like ["1.5000 VEX"].
     * Pass symbol = null to get all tokens, or "VEX" to filter.
     */
    fun getCurrencyBalance(
        contract: String,
        account: String,
        symbol: String? = null,
    ): List<String> {
        val req = JSONObject()
            .put("code", contract)
            .put("account", account)
        if (symbol != null) req.put("symbol", symbol)
        val arr = postArray("/v1/chain/get_currency_balance", req)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Push a signed transaction.
     * [packedTrxHex] = hex of serialized transaction bytes (without signatures).
     * [signatures] = list of "SIG_K1_..." strings.
     */
    fun pushTransaction(packedTrxHex: String, signatures: List<String>): VexTransferResult {
        val sigsArray = JSONArray().also { arr -> signatures.forEach { arr.put(it) } }
        val req = JSONObject()
            .put("signatures", sigsArray)
            .put("compression", 0)
            .put("packed_context_free_data", "")
            .put("packed_trx", packedTrxHex)
        val body = post("/v1/chain/push_transaction", req)
        return VexTransferResult(
            transactionId = body.optString("transaction_id", body.optString("id", "")),
            blockNum = body.optLong("processed", 0).let {
                body.optJSONObject("processed")?.optLong("block_num") ?: 0L
            },
            blockTime = body.optJSONObject("processed")?.optString("block_time") ?: "",
        )
    }

    // ── History endpoints ────────────────────────────────────────────────────

    /**
     * Fetch action history for an account (requires history plugin on node).
     * Returns raw JSON array of action objects.
     */
    fun getActions(
        accountName: String,
        pos: Int = -1,
        offset: Int = -20,
    ): JSONArray {
        val req = JSONObject()
            .put("account_name", accountName)
            .put("pos", pos)
            .put("offset", offset)
        return post("/v1/history/get_actions", req).optJSONArray("actions") ?: JSONArray()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun post(path: String, body: JSONObject): JSONObject {
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
        const val DEFAULT_NODE = "https://api.vexanium.com"
        const val TOKEN_CONTRACT = "eosio.token"
        const val NATIVE_SYMBOL = "VEX"
    }
}

class VexaniumException(message: String) : Exception(message)

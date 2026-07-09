package com.vexanium.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level Vexanium wallet operations.
 *
 * Usage:
 * ```
 * val key = VexaniumKey.fromWif("5J...")
 * val wallet = VexaniumWallet(accountName = "myaccount", key = key)
 *
 * val balance = wallet.getBalance()           // VexBalance
 * val result  = wallet.transfer(VexTransferRequest("myaccount", "otheraccount", "1.0000 VEX", "memo"))
 * println(result.transactionId)
 * ```
 *
 * @param accountName  Antelope account name (e.g. "myaccount")
 * @param key          Imported private key
 * @param permission   Permission level used for signing (default "active")
 * @param api          Injected API client; can pass custom nodeUrl via VexaniumApi(nodeUrl = "...")
 */
class VexaniumWallet(
    val accountName: String,
    private val key: VexaniumKey,
    private val permission: String = "active",
    private val api: VexaniumApi = VexaniumApi(),
) {

    // ── Queries ──────────────────────────────────────────────────────────────

    suspend fun getBalance(
        contract: String = VexaniumApi.TOKEN_CONTRACT,
        symbol: String = VexaniumApi.NATIVE_SYMBOL,
    ): VexBalance = withContext(Dispatchers.IO) {
        val balances = api.getCurrencyBalance(contract, accountName, symbol)
        val raw = balances.firstOrNull() ?: return@withContext VexBalance(accountName, 0.0, symbol)
        val parts = raw.trim().split(" ")
        VexBalance(
            account = accountName,
            amount = parts[0].toDoubleOrNull() ?: 0.0,
            symbol = parts.getOrElse(1) { symbol },
        )
    }

    suspend fun getAllBalances(contract: String = VexaniumApi.TOKEN_CONTRACT): List<VexBalance> =
        withContext(Dispatchers.IO) {
            api.getCurrencyBalance(contract, accountName).map { raw ->
                val parts = raw.trim().split(" ")
                VexBalance(
                    account = accountName,
                    amount = parts[0].toDoubleOrNull() ?: 0.0,
                    symbol = parts.getOrElse(1) { "?" },
                )
            }
        }

    suspend fun getAccountInfo(): VexAccountInfo = withContext(Dispatchers.IO) {
        api.getAccount(accountName)
    }

    // ── Transfers ────────────────────────────────────────────────────────────

    /**
     * Transfer tokens and return the resulting transaction ID.
     *
     * The [quantity] in [VexTransferRequest] must already include precision and symbol,
     * e.g. "1.0000 VEX". Use [formatQuantity] for convenience.
     */
    suspend fun transfer(request: VexTransferRequest): VexTransferResult =
        withContext(Dispatchers.IO) {
            val info = api.getInfo()
            val refBlock = api.getBlock(info.lastIrreversibleBlockNum)

            val expiration = (System.currentTimeMillis() / 1000L) + TX_EXPIRY_SECONDS
            val refBlockNum = (refBlock.blockNum and 0xFFFF).toInt()
            val refBlockPrefix = refBlock.refBlockPrefix

            val actionData = packTransferData(
                from = request.from,
                to = request.to,
                quantity = request.quantity,
                memo = request.memo,
            )

            val action = PackedAction(
                account = VexaniumApi.TOKEN_CONTRACT,
                name = "transfer",
                authorization = listOf(VexAuthorization(request.from, request.permission.ifEmpty { permission })),
                data = actionData,
            )

            val packedTx = packTransaction(expiration, refBlockNum, refBlockPrefix, listOf(action))
            val digest = vexSigningDigest(info.chainId, packedTx)
            val signature = key.sign(digest)

            api.pushTransaction(packedTx.toVexHex(), listOf(signature))
        }

    /**
     * Build and sign a custom action without broadcasting.
     * Returns (packedTxHex, signatures) — useful for offline signing or multi-sig.
     */
    suspend fun signAction(
        contract: String,
        actionName: String,
        actionData: ByteArray,
        extraAuth: List<VexAuthorization> = emptyList(),
    ): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val info = api.getInfo()
        val refBlock = api.getBlock(info.lastIrreversibleBlockNum)
        val expiration = (System.currentTimeMillis() / 1000L) + TX_EXPIRY_SECONDS
        val auth = listOf(VexAuthorization(accountName, permission)) + extraAuth
        val action = PackedAction(contract, actionName, auth, actionData)
        val packedTx = packTransaction(
            expirationEpoch = expiration,
            refBlockNum = (refBlock.blockNum and 0xFFFF).toInt(),
            refBlockPrefix = refBlock.refBlockPrefix,
            actions = listOf(action),
        )
        val digest = vexSigningDigest(info.chainId, packedTx)
        Pair(packedTx.toVexHex(), listOf(key.sign(digest)))
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    val publicKey: String get() = key.publicKeyString

    companion object {
        /** Transaction valid for 2 minutes after signing. */
        private const val TX_EXPIRY_SECONDS = 120L

        /**
         * Format an amount as Antelope asset string.
         * Example: formatQuantity(1.5, 4, "VEX") → "1.5000 VEX"
         */
        fun formatQuantity(amount: Double, precision: Int = 4, symbol: String = "VEX"): String {
            return "%.${precision}f $symbol".format(amount)
        }
    }
}

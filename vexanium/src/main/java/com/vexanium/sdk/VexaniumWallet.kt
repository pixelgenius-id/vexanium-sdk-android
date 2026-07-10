package com.vexanium.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level Vexanium wallet operations.
 *
 * Usage:
 * ```
 * val api      = VexaniumApi(nodeUrl = "https://api.vexanium.com")
 * val hyperion = VexaniumHyperion(hyperionUrl = "https://hyperion.vexanium.com")
 * val key      = VexaniumKey.fromWif("5J...")
 * val wallet   = VexaniumWallet(accountName = "myaccount", key = key, api = api, hyperion = hyperion)
 *
 * val balance = wallet.getBalance()
 * val result  = wallet.transfer(VexTransferRequest("myaccount", "otheraccount", "1.0000 VEX", "memo"))
 * println(result.transactionId)
 * ```
 *
 * @param accountName  Antelope account name (e.g. "myaccount")
 * @param key          Imported private key
 * @param api          Chain API client — supply your own node URL via VexaniumApi(nodeUrl = "...")
 * @param hyperion     Hyperion history client — supply your own URL via VexaniumHyperion(hyperionUrl = "...")
 * @param permission   Permission level used for signing (default "active")
 */
class VexaniumWallet(
    val accountName: String,
    private val key: VexaniumKey,
    private val api: VexaniumApi,
    private val hyperion: VexaniumHyperion,
    private val permission: String = "active",
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

    // ── History (Hyperion) ───────────────────────────────────────────────────

    /**
     * Get token transfer history via Hyperion.
     * @param contract  Token contract (default: vex.token). Pass a custom contract for ecosystem tokens.
     * @param limit     Number of records per page (max 100)
     * @param skip      Pagination offset
     */
    suspend fun getTransferHistory(
        contract: String = VexaniumApi.TOKEN_CONTRACT,
        symbol: String? = null,
        limit: Int = 20,
        skip: Int = 0,
    ): List<VexAction> = withContext(Dispatchers.IO) {
        hyperion.getTransfers(accountName, contract = contract, symbol = symbol, limit = limit, skip = skip)
    }

    /**
     * Get all action history via Hyperion.
     * @param filter Optional Hyperion filter, e.g. "vex.token:transfer"
     */
    suspend fun getActionHistory(
        filter: String? = null,
        limit: Int = 20,
        skip: Int = 0,
    ): List<VexAction> = withContext(Dispatchers.IO) {
        hyperion.getActions(accountName, filter = filter, limit = limit, skip = skip)
    }

    /** Get a specific transaction by ID. */
    suspend fun getTransaction(txId: String): VexTransaction = withContext(Dispatchers.IO) {
        hyperion.getTransaction(txId)
    }

    /**
     * Read rows from any smart contract table.
     * Works for system contracts, DEX pools, NFT contracts, or any ecosystem contract.
     */
    suspend fun getTableRows(
        code: String,
        scope: String,
        table: String,
        limit: Int = 10,
        lowerBound: String? = null,
        upperBound: String? = null,
        indexPos: Int = 1,
        keyType: String = "",
        reverse: Boolean = false,
    ): VexTableResult = withContext(Dispatchers.IO) {
        api.getTableRows(code, scope, table, limit, lowerBound, upperBound, indexPos, keyType, reverse)
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

    // ── Resource management ──────────────────────────────────────────────────

    /** Buy RAM for [receiver] (defaults to self) specified in bytes. */
    suspend fun buyRamBytes(bytes: Int, receiver: String = accountName): VexTransferResult =
        pushAction(
            contract = VexaniumApi.SYSTEM_CONTRACT,
            actionName = "buyrambytes",
            data = packBuyRamBytesData(accountName, receiver, bytes),
        )

    /** Stake [stakeCpu] and [stakeNet] to [receiver] (defaults to self). Quantities e.g. "1.0000 VEX". */
    suspend fun delegateBw(
        stakeCpu: String,
        stakeNet: String,
        receiver: String = accountName,
    ): VexTransferResult = pushAction(
        contract = VexaniumApi.SYSTEM_CONTRACT,
        actionName = "delegatebw",
        data = packDelegateBwData(accountName, receiver, stakeNet, stakeCpu),
    )

    /** Unstake [unstakeCpu] and [unstakeNet] from [receiver] (defaults to self). */
    suspend fun undelegateBw(
        unstakeCpu: String,
        unstakeNet: String,
        receiver: String = accountName,
    ): VexTransferResult = pushAction(
        contract = VexaniumApi.SYSTEM_CONTRACT,
        actionName = "undelegatebw",
        data = packUndelegateBwData(accountName, receiver, unstakeNet, unstakeCpu),
    )

    /** Vote for up to 30 [producers] (or set [proxy] for proxy voting). */
    suspend fun voteProducer(
        producers: List<String> = emptyList(),
        proxy: String = "",
    ): VexTransferResult = pushAction(
        contract = VexaniumApi.SYSTEM_CONTRACT,
        actionName = "voteproducer",
        data = packVoteProducerData(accountName, proxy, producers),
    )

    /**
     * Powerup NET and CPU resources.
     * [netFrac] and [cpuFrac] are values 0..10^15 representing the fraction of network resources.
     * [maxPayment] is the max VEX to spend, e.g. "1.0000 VEX".
     */
    suspend fun powerup(
        netFrac: Long,
        cpuFrac: Long,
        maxPayment: String,
        days: Int = 1,
        receiver: String = accountName,
    ): VexTransferResult = pushAction(
        contract = VexaniumApi.SYSTEM_CONTRACT,
        actionName = "powerup",
        data = packPowerupData(accountName, receiver, days, netFrac, cpuFrac, maxPayment),
    )

    // ── Internal helpers ─────────────────────────────────────────────────────

    private suspend fun pushAction(
        contract: String,
        actionName: String,
        data: ByteArray,
    ): VexTransferResult = withContext(Dispatchers.IO) {
        val info = api.getInfo()
        val refBlock = api.getBlock(info.lastIrreversibleBlockNum)
        val expiration = (System.currentTimeMillis() / 1000L) + TX_EXPIRY_SECONDS
        val action = PackedAction(
            account = contract,
            name = actionName,
            authorization = listOf(VexAuthorization(accountName, permission)),
            data = data,
        )
        val packedTx = packTransaction(
            expirationEpoch = expiration,
            refBlockNum = (refBlock.blockNum and 0xFFFF).toInt(),
            refBlockPrefix = refBlock.refBlockPrefix,
            actions = listOf(action),
        )
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

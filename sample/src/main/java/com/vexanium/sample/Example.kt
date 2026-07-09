package com.vexanium.sample

import com.vexanium.sdk.VexaniumApi
import com.vexanium.sdk.VexaniumHyperion
import com.vexanium.sdk.VexaniumKey
import com.vexanium.sdk.VexaniumWallet
import com.vexanium.sdk.VexTransferRequest
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Configure RPC nodes
    val api      = VexaniumApi(nodeUrl = "https://api.vexanium.com")
    val hyperion = VexaniumHyperion(hyperionUrl = "https://hyperion.vexanium.com")

    // 2. Import private key (WIF format)
    val key = VexaniumKey.fromWif("5J...your_private_key...")
    println("Public key: ${key.publicKeyString}")

    // 3. Create wallet instance
    val wallet = VexaniumWallet(
        accountName = "myaccount",
        key = key,
        api = api,
        hyperion = hyperion,
    )

    // 4. Get VEX balance
    val balance = wallet.getBalance()
    println("Balance: $balance")

    // 5. Get account info (CPU/NET/RAM)
    val info = wallet.getAccountInfo()
    println("CPU used: ${info.cpuLimit.used} / ${info.cpuLimit.max}")

    // 6. Get transfer history (via Hyperion)
    val history = wallet.getTransferHistory(limit = 10)
    history.forEach { println("${it.timestamp}  ${it.from} → ${it.to}  ${it.quantity} ${it.symbol}") }

    // 7. Transfer VEX
    val result = wallet.transfer(
        VexTransferRequest(
            from = "myaccount",
            to = "receiver",
            quantity = VexaniumWallet.formatQuantity(1.0),  // "1.0000 VEX"
            memo = "test transfer",
        )
    )
    println("Transaction: ${result.transactionId}")
}

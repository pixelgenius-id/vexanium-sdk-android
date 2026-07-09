package com.vexanium.sample

import com.vexanium.sdk.VexaniumApi
import com.vexanium.sdk.VexaniumKey
import com.vexanium.sdk.VexaniumWallet
import com.vexanium.sdk.VexTransferRequest
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Import private key (WIF format)
    val key = VexaniumKey.fromWif("5J...your_private_key...")

    println("Public key: ${key.publicKeyString}")

    // 2. Create wallet instance
    val wallet = VexaniumWallet(
        accountName = "myaccount",
        key = key,
        api = VexaniumApi(nodeUrl = VexaniumApi.DEFAULT_NODE),
    )

    // 3. Get VEX balance
    val balance = wallet.getBalance()
    println("Balance: $balance")

    // 4. Get account info (CPU/NET/RAM)
    val info = wallet.getAccountInfo()
    println("CPU used: ${info.cpuLimit.used} / ${info.cpuLimit.max}")

    // 5. Transfer VEX
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

package jp.co.soramitsu.feature_wallet_api.domain.model

import jp.co.soramitsu.feature_account_api.domain.model.Node
import java.math.BigDecimal
import java.math.BigInteger

private const val DEFAULT_MANTISSA = 12

class Asset(
    val token: Token,
    val balanceInPlanks: BigInteger,
    val dollarRate: BigDecimal?,
    val recentRateChange: BigDecimal?
) {
    val balance = balanceInPlanks.toBigDecimal(scale = token.mantissa)

    val dollarAmount = dollarRate?.multiply(balance)

    enum class Token(
        val displayName: String,
        val networkType: Node.NetworkType,
        val mantissa: Int = DEFAULT_MANTISSA
    ) {

        KSM("KSM", Node.NetworkType.KUSAMA),
        DOT("DOT", Node.NetworkType.POLKADOT, 10),
        WND("WND", Node.NetworkType.WESTEND);

        companion object {
            fun fromNetworkType(networkType: Node.NetworkType): Token {
                return when (networkType) {
                    Node.NetworkType.KUSAMA -> KSM
                    Node.NetworkType.POLKADOT -> DOT
                    Node.NetworkType.WESTEND -> WND
                }
            }
        }
    }
}
package com.karyo.fulfillment.vo

import java.math.BigDecimal

/**
 * Cross-module picked-quantity rollup for one delivery-order line, split by SKU origin:
 * [pickedAmount] is the ordered-SKU quantity, [substitutedAmount] the substitute-SKU quantity
 * credited to the same line by substitution follow-up picks. Consumed through the
 * [com.karyo.fulfillment.spi.PickRollupLookup] SPI.
 */
data class PickRollup(
    val pickedAmount: BigDecimal,
    val substitutedAmount: BigDecimal,
)

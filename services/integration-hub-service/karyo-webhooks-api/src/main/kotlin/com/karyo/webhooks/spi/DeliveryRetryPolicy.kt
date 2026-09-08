package com.karyo.webhooks.spi

/** Returns seconds to wait before the next attempt, given the attempt count just completed. */
interface DeliveryRetryPolicy {
    fun backoffSeconds(attempts: Int): Long
}

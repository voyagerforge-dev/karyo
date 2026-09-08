package com.karyo.demo.config

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class DemoConfig(
    @ConfigProperty(name = "karyo.demo.enabled", defaultValue = "off") private val enabledRaw: String,
    @ConfigProperty(name = "karyo.demo.history-days", defaultValue = "120") val historyDays: Int,
    @ConfigProperty(name = "karyo.demo.seed", defaultValue = "42") val seed: Long,
    @ConfigProperty(name = "karyo.demo.orders-per-day", defaultValue = "8") val ordersPerDay: Int,
    @ConfigProperty(name = "karyo.demo.sku-count", defaultValue = "24") val skuCount: Int,
    @ConfigProperty(name = "karyo.demo.location-count", defaultValue = "40") val locationCount: Int,
) {
    val enabled: Boolean get() = enabledRaw.equals("on", true) || enabledRaw.equals("true", true)
}

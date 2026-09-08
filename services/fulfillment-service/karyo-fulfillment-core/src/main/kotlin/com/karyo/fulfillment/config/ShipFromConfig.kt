package com.karyo.fulfillment.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "karyo.shipping.ship-from")
interface ShipFromConfig {
    @WithDefault("Karyo Demo Warehouse") fun name(): String
    @WithDefault("1 Logistics Way") fun street(): String
    @WithDefault("Distribution City") fun city(): String
    @WithDefault("00000") fun zipCode(): String
    @WithDefault("US") fun country(): String
}

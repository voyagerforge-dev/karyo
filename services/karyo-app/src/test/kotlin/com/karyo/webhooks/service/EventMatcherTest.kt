package com.karyo.webhooks.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventMatcherTest {
    @Test fun `star matches anything`() =
        assertTrue(EventMatcher.matches(listOf("*"), "DeliveryOrderStateChanged"))

    @Test fun `exact matches only itself`() {
        assertTrue(EventMatcher.matches(listOf("PickConfirmed"), "PickConfirmed"))
        assertFalse(EventMatcher.matches(listOf("PickConfirmed"), "PackConfirmed"))
    }

    @Test fun `prefix wildcard matches by prefix`() {
        assertTrue(EventMatcher.matches(listOf("DeliveryOrder*"), "DeliveryOrderStateChanged"))
        assertFalse(EventMatcher.matches(listOf("DeliveryOrder*"), "GoodsReceiptStateChanged"))
    }

    @Test fun `any entry can match`() =
        assertTrue(EventMatcher.matches(listOf("Foo", "Bar*", "PickConfirmed"), "PickConfirmed"))

    @Test fun `empty list matches nothing`() =
        assertFalse(EventMatcher.matches(emptyList(), "PickConfirmed"))
}

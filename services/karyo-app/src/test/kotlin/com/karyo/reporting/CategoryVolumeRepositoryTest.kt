package com.karyo.reporting

import com.karyo.product.domain.model.ItemData
import com.karyo.product.domain.model.ItemUnit
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.reporting.repository.CategoryVolumeRepository
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

@QuarkusTest
class CategoryVolumeRepositoryTest {
    @Inject lateinit var repo: CategoryVolumeRepository
    @Inject lateinit var itemUnitRepository: ItemUnitRepository
    @Inject lateinit var itemDataRepository: ItemDataRepository
    @Inject lateinit var em: EntityManager

    @Test
    @TestTransaction
    fun `groups pick volume by category and sums picked amount for the tenant`() {
        val client = 7171L
        val suffix = System.nanoTime()
        val shortSuffix = suffix % 100_000

        val unit = ItemUnit().apply { name = "PCS-CV-$shortSuffix" }
        itemUnitRepository.persist(unit)

        val electronics = itemData(client, "SKU-ELEC-$suffix", "Electronics", unit)
        val apparel = itemData(client, "SKU-APRL-$suffix", "Apparel", unit)
        val uncategorized = itemData(client, "SKU-NONE-$suffix", null, unit)
        itemDataRepository.persist(electronics, apparel, uncategorized)

        val poId = (em.createNativeQuery(
            "INSERT INTO karyo.pick_orders (client_id, pick_order_number, delivery_order_id, delivery_order_number, version, created, modified) " +
            "VALUES ($client, 'PO-CATVOL-$suffix', 1, 'DO-CATVOL-$suffix', 0, now(), now()) RETURNING id"
        ).singleResult as Number).toLong()

        // In-window picks: two Electronics lines (5 + 3 = 8), one Apparel line (4), one Uncategorized (2).
        insertPick(client, poId, electronics.id!!, "SKU-ELEC-$suffix", BigDecimal("5"), Instant.now())
        insertPick(client, poId, electronics.id!!, "SKU-ELEC-$suffix", BigDecimal("3"), Instant.now())
        insertPick(client, poId, apparel.id!!, "SKU-APRL-$suffix", BigDecimal("4"), Instant.now())
        insertPick(client, poId, uncategorized.id!!, "SKU-NONE-$suffix", BigDecimal("2"), Instant.now())
        // Out-of-window pick (older than start) — must be excluded from the aggregation.
        insertPick(client, poId, electronics.id!!, "SKU-ELEC-$suffix", BigDecimal("99"), Instant.now().minus(Duration.ofDays(60)))

        val start = Instant.now().minus(Duration.ofDays(30))
        val rows = repo.volumeByCategory(client, start)

        val byCategory = rows.associateBy { it.category }
        assertEquals(3, rows.size)
        assertEquals(0, BigDecimal("8").compareTo(byCategory.getValue("Electronics").volume))
        assertEquals(2L, byCategory.getValue("Electronics").lineCount)
        assertEquals(0, BigDecimal("4").compareTo(byCategory.getValue("Apparel").volume))
        assertEquals(1L, byCategory.getValue("Apparel").lineCount)
        assertEquals(0, BigDecimal("2").compareTo(byCategory.getValue("Uncategorized").volume))
        // Ordered by volume DESC.
        assertTrue(rows[0].category == "Electronics")
    }

    @Test
    @TestTransaction
    fun `EXTINGUISH picks do not count toward category volume`() {
        val client = 7172L
        val suffix = System.nanoTime()
        val shortSuffix = suffix % 100_000

        val unit = ItemUnit().apply { name = "PCS-CV-EXT-$shortSuffix" }
        itemUnitRepository.persist(unit)

        val electronics = itemData(client, "SKU-ELEC-EXT-$suffix", "Electronics", unit)
        itemDataRepository.persist(electronics)

        val poId = (em.createNativeQuery(
            "INSERT INTO karyo.pick_orders (client_id, pick_order_number, delivery_order_id, delivery_order_number, version, created, modified) " +
            "VALUES ($client, 'PO-CATVOL-EXT-$suffix', 1, 'DO-CATVOL-EXT-$suffix', 0, now(), now()) RETURNING id"
        ).singleResult as Number).toLong()

        insertPick(client, poId, electronics.id!!, "SKU-ELEC-EXT-$suffix", BigDecimal("5"), Instant.now(), pickingType = "PICK")
        insertPick(client, poId, electronics.id!!, "SKU-ELEC-EXT-$suffix", BigDecimal("7"), Instant.now(), pickingType = "EXTINGUISH")

        val start = Instant.now().minus(Duration.ofDays(30))
        val rows = repo.volumeByCategory(client, start)

        val byCategory = rows.associateBy { it.category }
        assertEquals(0, BigDecimal("5").compareTo(byCategory.getValue("Electronics").volume))
        assertEquals(1L, byCategory.getValue("Electronics").lineCount)
    }

    private fun itemData(client: Long, number: String, tradeGroup: String?, unit: ItemUnit): ItemData =
        ItemData().apply {
            this.clientId = client
            this.number = number
            this.name = number
            this.itemUnit = unit
            this.tradeGroup = tradeGroup
        }

    private fun insertPick(
        client: Long,
        poId: Long,
        itemDataId: Long,
        itemDataNumber: String,
        amount: BigDecimal,
        created: Instant,
        pickingType: String = "PICK",
    ) {
        em.createNativeQuery(
            "INSERT INTO karyo.picks (client_id, pick_order_id, delivery_order_line_id, item_data_id, item_data_number, " +
            "source_stock_unit_id, planned_amount, picked_amount, picking_type, version, created, modified) " +
            "VALUES (?1, ?2, 1, ?3, ?4, 1, ?5, ?5, ?7, 0, ?6, ?6)"
        ).setParameter(1, client)
         .setParameter(2, poId)
         .setParameter(3, itemDataId)
         .setParameter(4, itemDataNumber)
         .setParameter(5, amount)
         .setParameter(6, java.sql.Timestamp.from(created))
         .setParameter(7, pickingType)
         .executeUpdate()
    }
}

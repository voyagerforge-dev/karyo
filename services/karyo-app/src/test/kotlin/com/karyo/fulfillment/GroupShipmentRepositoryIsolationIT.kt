package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.vo.ShipmentState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Bulk Allocation Sprint C final-review fix wave, IMPORTANT 7: the spec-mandated two-client
 * isolation test for Sprint C's new repository queries. Two paired tenants (9408 and 9409) hold
 * structurally identical group-shipment graphs, and every new query is then asked -- with 9408's
 * `clientId` but BOTH tenants' ids in the argument -- to return 9408's rows and nothing else.
 *
 * The queries under test:
 *  - [ShipmentOrderRepository.findShipmentIdsByOrderIds]  (also IMPORTANT 8: `s.clientId` filter)
 *  - [ShipmentOrderRepository.findByShipmentIds]
 *  - [ShipmentRepository.findOpenByGroupId]
 *  - [ShipmentRepository.findOpenByGroupIds]  (burndown-6 A8, the batched form)
 *  - [ShippingUnitRepository.sumAmountByOrderLineIds]  (burndown-6 A6, group shipments only)
 *  - [ShippingUnitRepository.findLinesByUnitIds]
 *
 * Persisted through the repositories directly (the [GroupShipmentModelIT] precedent) rather than
 * through the pack-out flow: the subject here is the SQL's tenant scoping, and a repository-level
 * fixture is the only way to build a second tenant's mirror-image graph without a second licensed
 * wave. `clientId` is a PARAMETER on every seed helper for the same implicit-receiver-shadowing
 * reason [GroupShipmentModelIT.persistGroupShipment] documents.
 *
 * IMPORTANT 2's fulfillment V613 partial unique index is exercised here too -- it is a schema
 * object no service path can be made to violate on purpose, so a repository-level insert is what
 * proves the migration actually applied.
 */
@QuarkusTest
class GroupShipmentRepositoryIsolationIT {

    @Inject
    lateinit var shipmentRepository: ShipmentRepository

    @Inject
    lateinit var shipmentOrderRepository: ShipmentOrderRepository

    @Inject
    lateinit var shippingUnitRepository: ShippingUnitRepository

    /** One tenant's whole graph: group -> shipment -> member order -> shipping unit -> line. */
    data class Graph(
        val clientId: Long,
        val groupId: Long,
        val shipmentId: Long,
        val orderId: Long,
        val orderLineId: Long,
        val unitId: Long,
        val lineId: Long,
    )

    @Transactional
    fun seedGraph(clientId: Long, groupId: Long, orderId: Long, orderLineId: Long, state: Int): Graph {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-ISO-${System.nanoTime()}"
            this.consolidationGroupId = groupId
            this.waveId = 4242L
            this.state = state
        }
        shipmentRepository.persist(shp)
        shipmentOrderRepository.persist(
            ShipmentOrder().apply {
                this.clientId = clientId
                this.shipmentId = shp.id!!
                this.deliveryOrderId = orderId
                this.deliveryOrderNumber = "ORD-ISO-$orderId"
            },
        )
        val unit = ShippingUnit().apply {
            this.clientId = clientId
            this.shipmentId = shp.id!!
            positionIndex = 1
            shippingUnitNumber = "${shp.shipmentNumber}-SU1"
            type = "CARTON"
            weight = BigDecimal("1.0")
            this.state = ShipmentState.PACKED.code
            origin = ShippingUnit.ORIGIN_CONSOLIDATION
        }
        shippingUnitRepository.persist(unit)
        val line = ShippingUnitLine().apply {
            this.clientId = clientId
            shippingUnitId = unit.id!!
            itemDataId = 777L
            itemDataNumber = "ISO-SKU"
            amount = BigDecimal(if (clientId == CLIENT_A) A_AMOUNT else B_AMOUNT)
            deliveryOrderId = orderId
            deliveryOrderLineId = orderLineId
        }
        shippingUnitRepository.persistLine(line)
        return Graph(clientId, groupId, shp.id!!, orderId, orderLineId, unit.id!!, line.id!!)
    }

    /**
     * A DISCRETE (per-order PACKOUT) shipment on the same delivery-order line as a group graph:
     * `consolidationGroupId = null`, `deliveryOrderId` set, and a [ShippingUnit.ORIGIN_PACKOUT]
     * unit. This is the shape a HYBRID wave produces when one member's COMPLETE-picked lines are
     * packed through [com.karyo.fulfillment.service.PackingService.pack] while its batch-picked
     * remainder goes over the put wall -- the leak burndown-6 A6 fixes.
     */
    @Transactional
    fun seedDiscreteShipment(clientId: Long, orderId: Long, orderLineId: Long, amount: Int) {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-DISC-${System.nanoTime()}"
            this.consolidationGroupId = null
            this.deliveryOrderId = orderId
            this.deliveryOrderNumber = "ORD-ISO-$orderId"
            this.state = ShipmentState.PACKING.code
        }
        shipmentRepository.persist(shp)
        val unit = ShippingUnit().apply {
            this.clientId = clientId
            this.shipmentId = shp.id!!
            positionIndex = 1
            shippingUnitNumber = "${shp.shipmentNumber}-SU1"
            type = "CARTON"
            weight = BigDecimal("1.0")
            this.state = ShipmentState.PACKED.code
            origin = ShippingUnit.ORIGIN_PACKOUT
        }
        shippingUnitRepository.persist(unit)
        shippingUnitRepository.persistLine(
            ShippingUnitLine().apply {
                this.clientId = clientId
                shippingUnitId = unit.id!!
                itemDataId = 777L
                itemDataNumber = "ISO-SKU"
                this.amount = BigDecimal(amount)
                deliveryOrderId = orderId
                deliveryOrderLineId = orderLineId
            },
        )
    }

    /** Two mirror-image graphs, one per tenant, with ids unique across the suite's shared DB. */
    private fun pairedGraphs(): Pair<Graph, Graph> {
        val base = System.nanoTime()
        val a = seedGraph(CLIENT_A, base, base + 1, base + 2, ShipmentState.PACKING.code)
        val b = seedGraph(CLIENT_B, base + 10, base + 11, base + 12, ShipmentState.PACKING.code)
        return a to b
    }

    @Test
    fun `every Sprint C group-shipment query returns only the calling client's rows`() {
        val (a, b) = pairedGraphs()
        val bothOrders = listOf(a.orderId, b.orderId)
        val bothShipments = listOf(a.shipmentId, b.shipmentId)
        val bothLines = listOf(a.orderLineId, b.orderLineId)
        val bothUnits = listOf(a.unitId, b.unitId)

        assertThat(shipmentOrderRepository.findShipmentIdsByOrderIds(bothOrders, CLIENT_A))
            .containsExactly(entry(a.orderId, a.shipmentId))
        assertThat(shipmentOrderRepository.findShipmentIdsByOrderIds(bothOrders, CLIENT_B))
            .containsExactly(entry(b.orderId, b.shipmentId))

        assertThat(shipmentOrderRepository.findByShipmentIds(bothShipments, CLIENT_A).map { it.deliveryOrderId })
            .containsExactly(a.orderId)
        assertThat(shipmentOrderRepository.findByShipmentIds(bothShipments, CLIENT_B).map { it.deliveryOrderId })
            .containsExactly(b.orderId)

        assertThat(shipmentRepository.findOpenByGroupId(a.groupId, CLIENT_A)?.id).isEqualTo(a.shipmentId)
        assertThat(shipmentRepository.findOpenByGroupId(a.groupId, CLIENT_B))
            .`as`("B must not see A's group shipment").isNull()
        assertThat(shipmentRepository.findOpenByGroupId(b.groupId, CLIENT_A))
            .`as`("A must not see B's group shipment").isNull()

        assertThat(shippingUnitRepository.sumAmountByOrderLineIds(bothLines, CLIENT_A).keys)
            .containsExactly(a.orderLineId)
        assertThat(shippingUnitRepository.sumAmountByOrderLineIds(bothLines, CLIENT_A)[a.orderLineId])
            .isEqualByComparingTo(BigDecimal(A_AMOUNT))
        assertThat(shippingUnitRepository.sumAmountByOrderLineIds(bothLines, CLIENT_B).keys)
            .containsExactly(b.orderLineId)

        assertThat(shippingUnitRepository.findLinesByUnitIds(bothUnits, CLIENT_A).map { it.id })
            .containsExactly(a.lineId)
        assertThat(shippingUnitRepository.findLinesByUnitIds(bothUnits, CLIENT_B).map { it.id })
            .containsExactly(b.lineId)
    }

    /**
     * Burndown-6 A6 (row :2075): `sumAmountByOrderLineIds` is the put wall's packed ledger, so its
     * universe is live GROUP shipments only. A per-order PACKOUT box on the SAME delivery-order
     * line -- what a HYBRID member's COMPLETE-picked lines produce -- must not be added in, or the
     * group's `packedAmount` overshoots what the wall ever sorted.
     */
    @Test
    fun `sumAmountByOrderLineIds ignores a discrete per-order shipment on the same line`() {
        val (a, _) = pairedGraphs()
        seedDiscreteShipment(CLIENT_A, a.orderId, a.orderLineId, DISCRETE_AMOUNT)

        assertThat(shippingUnitRepository.sumAmountByOrderLineIds(listOf(a.orderLineId), CLIENT_A)[a.orderLineId])
            .`as`("only the group shipment's %s counts, not the discrete box's %s", A_AMOUNT, DISCRETE_AMOUNT)
            .isEqualByComparingTo(BigDecimal(A_AMOUNT))
    }

    /**
     * Burndown-6 A8 (row :2085): the batched form of [ShipmentRepository.findOpenByGroupId] behind
     * `ConsolidationPackPort.findOpenGroupShipmentIds`. Same universe as the per-group read -- one
     * tenant, live rows only.
     */
    @Test
    fun `findOpenByGroupIds returns only the calling client's live group shipments`() {
        val (a, b) = pairedGraphs()
        val base = System.nanoTime()
        val a2 = seedGraph(CLIENT_A, base + 300, base + 301, base + 302, ShipmentState.PACKING.code)
        val canceledGroup = base + 400
        seedGraph(CLIENT_A, canceledGroup, base + 401, base + 402, ShipmentState.CANCELED.code)

        val found = shipmentRepository.findOpenByGroupIds(
            listOf(a.groupId, a2.groupId, b.groupId, canceledGroup), CLIENT_A,
        )

        assertThat(found.map { it.id })
            .`as`("A's two live group shipments; B's is another tenant's, the fourth is CANCELED")
            .containsExactlyInAnyOrder(a.shipmentId, a2.shipmentId)
        assertThat(shipmentRepository.findOpenByGroupIds(emptyList(), CLIENT_A)).isEmpty()
    }

    /**
     * IMPORTANT 8 specifically: the join's Shipment side is tenant-filtered too. A `ShipmentOrder`
     * row whose own `clientId` is A but whose shipment belongs to B is the corruption shape the
     * added `s.clientId = :clientId` predicate exists for -- before it, A's query happily returned
     * B's shipment id.
     */
    @Test
    fun `findShipmentIdsByOrderIds ignores a member row pointing at another client's shipment`() {
        val (_, b) = pairedGraphs()
        val strayOrderId = System.nanoTime()
        seedStrayMember(CLIENT_A, b.shipmentId, strayOrderId)

        assertThat(shipmentOrderRepository.findShipmentIdsByOrderIds(listOf(strayOrderId), CLIENT_A))
            .`as`("a member row of A on a shipment of B resolves to nothing for A")
            .isEmpty()
    }

    @Transactional
    fun seedStrayMember(clientId: Long, shipmentId: Long, orderId: Long) {
        shipmentOrderRepository.persist(
            ShipmentOrder().apply {
                this.clientId = clientId
                this.shipmentId = shipmentId
                this.deliveryOrderId = orderId
                this.deliveryOrderNumber = "ORD-STRAY-$orderId"
            },
        )
    }

    /**
     * IMPORTANT 2: fulfillment V613's `idx_shipments_group_live`. A canceled shipment for the same
     * group is deliberately outside the index, so a group whose first pack-out was canceled can be
     * packed again -- both halves are asserted here.
     */
    @Test
    fun `V613 refuses a second live shipment for one consolidation group, but allows one after a cancel`() {
        val base = System.nanoTime()
        val liveGroup = base + 100
        seedGraph(CLIENT_A, liveGroup, base + 101, base + 102, ShipmentState.PACKING.code)

        val thrown = catchThrowable { seedGraph(CLIENT_A, liveGroup, base + 103, base + 104, ShipmentState.PACKING.code) }
        assertThat(thrown).`as`("a second live shipment for one group must be refused").isNotNull
        assertThat(causeChain(thrown)).contains("idx_shipments_group_live")

        val canceledGroup = base + 200
        seedGraph(CLIENT_A, canceledGroup, base + 201, base + 202, ShipmentState.CANCELED.code)
        val reopened = seedGraph(CLIENT_A, canceledGroup, base + 203, base + 204, ShipmentState.PACKING.code)
        assertThat(shipmentRepository.findOpenByGroupId(canceledGroup, CLIENT_A)?.id).isEqualTo(reopened.shipmentId)
    }

    /** The constraint name surfaces somewhere in the JTA/Hibernate/JDBC wrapper chain, not at its head. */
    private fun causeChain(t: Throwable): String =
        generateSequence(t) { current -> current.cause?.takeIf { it !== current } }
            .joinToString(" | ") { it.message ?: it::class.java.name }

    private companion object {
        const val CLIENT_A = 9408L
        const val CLIENT_B = 9409L
        const val A_AMOUNT = 7
        const val DISCRETE_AMOUNT = 5
        const val B_AMOUNT = 11
    }
}

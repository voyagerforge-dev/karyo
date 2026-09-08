package com.karyo.fulfillment

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.vo.PickState
import com.karyo.security.TenantContext
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

@QuarkusTest
class ShortPickServiceTest {

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var outboxRepository: OutboxEventRepository
    @Inject lateinit var entityManager: EntityManager

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun createStockWithLotAndBestBefore(
        ulId: Long, itemDataId: Long, number: String, amount: Double, lotNumber: String, bestBefore: LocalDate,
    ): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,
                    |"lotNumber":"$lotNumber","bestBefore":"$bestBefore","state":300}""".trimMargin(),
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun createOrderFor(itemDataId: Long, amount: Double): Long {
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"C","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    private fun registerSubstitution(primary: Long, substitute: Long) {
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$primary,"substituteItemDataId":$substitute,"priority":1}""")
            .`when`().post("/api/v1/item-substitutions").then().statusCode(201)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a short pick is covered by a follow-up from a different unit, then completes`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "P-$s"
        val pid = createProduct(num, iu)
        val lotA = "LOT-A-$s"; val bbA = LocalDate.of(2027, 1, 10)
        val lotB = "LOT-B-$s"; val bbB = LocalDate.of(2027, 6, 20)
        // A created FIRST (FIFO reserves it for the order's 60); B is the only free same-item stock left.
        // Distinct lot/best-before per source so a captured actual is provably from ITS OWN pick's
        // source, not inherited from the parent.
        val ulA = createUnitLoad("ULA-$s"); val a = createStockWithLotAndBestBefore(ulA, pid, num, 60.0, lotA, bbA)
        val ulB = createUnitLoad("ULB-$s"); val b = createStockWithLotAndBestBefore(ulB, pid, num, 40.0, lotB, bbB)
        val orderId = createOrderFor(pid, 60.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val initial = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(initial).hasSize(1)
        val parentId = initial.single().id!!
        assertThat(initial.single().sourceStockUnitId).isEqualTo(a)

        // Short by 10: pick 50 from A. Follow-up must re-select the missing 10 from B (A excluded).
        pickOrderService.confirmPick(parentId, BigDecimal(50), targetUnitLoadId = null)
        entityManager.clear()

        val afterShort = pickRepository.findByPickOrderId(pickOrder.id!!)
        val parent = afterShort.single { it.followUpForPickId == null }
        assertThat(parent.id).isEqualTo(parentId)
        assertThat(parent.state).isEqualTo(PickState.PICKED.code)
        assertThat(parent.pickedAmount).isEqualByComparingTo(BigDecimal(50))
        // Parent captured A's own lot/best-before at confirm.
        assertThat(parent.pickedLotNumber).isEqualTo(lotA)
        assertThat(parent.pickedBestBefore).isEqualTo(bbA)

        val followUp = afterShort.single { it.followUpForPickId == parentId }
        assertThat(followUp.substitutedItemDataId).isNull()
        assertThat(followUp.sourceStockUnitId).isEqualTo(b)
        assertThat(followUp.plannedAmount).isEqualByComparingTo(BigDecimal(10))
        assertThat(followUp.state).isEqualTo(PickState.RELEASED.code)
        // Not yet confirmed -- no actuals stamped yet.
        assertThat(followUp.pickedLotNumber).isNull()
        assertThat(followUp.pickedBestBefore).isNull()

        // PickOrder not complete while the follow-up is still RELEASED.
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(500))

        // Confirm the follow-up in full → all picks PICKED → order advances to PICKED(600).
        pickOrderService.confirmPick(followUp.id!!, followUp.plannedAmount, targetUnitLoadId = null)
        entityManager.clear()
        val allPicked = pickRepository.findByPickOrderId(pickOrder.id!!)
        assertThat(allPicked.all { it.state == PickState.PICKED.code }).isTrue
        // Follow-up captured ITS OWN source B's actuals, distinct from the parent's A actuals.
        val confirmedFollowUp = allPicked.single { it.followUpForPickId == parentId }
        assertThat(confirmedFollowUp.pickedLotNumber).isEqualTo(lotB)
        assertThat(confirmedFollowUp.pickedBestBefore).isEqualTo(bbB)
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).body("state", `is`(600))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a short pick falls through to a substitute when same-item stock is exhausted`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s")
        val pNum = "P-$s"; val sNum = "S-$s"
        val primary = createProduct(pNum, iu)
        val substitute = createProduct(sNum, iu)
        val lotP = "LOT-P-$s"; val bbP = LocalDate.of(2027, 2, 1)
        val lotS = "LOT-S-$s"; val bbS = LocalDate.of(2027, 9, 30)
        // Primary = EXACTLY 60 (fully reserved by the order); substitute S = 50 free. Distinct lot/
        // best-before so the substitute follow-up's captured actuals are provably from S, not P.
        val ulP = createUnitLoad("ULP-$s"); createStockWithLotAndBestBefore(ulP, primary, pNum, 60.0, lotP, bbP)
        val ulS = createUnitLoad("ULS-$s"); createStockWithLotAndBestBefore(ulS, substitute, sNum, 50.0, lotS, bbS)
        registerSubstitution(primary, substitute)
        val orderId = createOrderFor(primary, 60.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val parentId = pickRepository.findByPickOrderId(pickOrder.id!!).single().id!!

        // Short by 15: same-item follow-up of P finds nothing (P's only unit is the excluded short
        // source) → substitution covers the 15 from S.
        pickOrderService.confirmPick(parentId, BigDecimal(45), targetUnitLoadId = null)
        entityManager.clear()

        val afterShort = pickRepository.findByPickOrderId(pickOrder.id!!)
        val parent = afterShort.single { it.followUpForPickId == null }
        // Parent captured PRIMARY's own actuals.
        assertThat(parent.pickedLotNumber).isEqualTo(lotP)
        assertThat(parent.pickedBestBefore).isEqualTo(bbP)

        val followUp = afterShort.single { it.followUpForPickId == parentId }
        assertThat(followUp.substitutedItemDataId).isEqualTo(substitute)
        assertThat(followUp.itemDataId).isEqualTo(substitute)
        assertThat(followUp.plannedAmount).isEqualByComparingTo(BigDecimal(15))
        assertThat(followUp.state).isEqualTo(PickState.RELEASED.code)

        // D4: confirm the substitute follow-up too — the line's rollup must SPLIT by SKU:
        // ordered-SKU 45 in pickedAmount, substitute-SKU 15 in substitutedAmount (never summed).
        pickOrderService.confirmPick(followUp.id!!, followUp.plannedAmount, targetUnitLoadId = null)
        entityManager.clear()
        // The substitute follow-up captured ITS OWN source S's actuals — never the primary's.
        val confirmedFollowUp = pickRepository.findByPickOrderId(pickOrder.id!!).single { it.followUpForPickId == parentId }
        assertThat(confirmedFollowUp.pickedLotNumber).isEqualTo(lotS)
        assertThat(confirmedFollowUp.pickedBestBefore).isEqualTo(bbS)
        val order = given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath()
        assertThat(order.getDouble("lines[0].pickedAmount")).isEqualTo(45.0)
        assertThat(order.getDouble("lines[0].substitutedAmount")).isEqualTo(15.0)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an uncovered short pick is partial-shipped and the PickOrder still completes`() {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "P-$s"
        val pid = createProduct(num, iu)
        val lot = "LOT-$s"; val bb = LocalDate.of(2027, 5, 5)
        // EXACTLY 60 on one unit, no other P stock and no substitute registered.
        val ul = createUnitLoad("UL-$s"); createStockWithLotAndBestBefore(ul, pid, num, 60.0, lot, bb)
        val orderId = createOrderFor(pid, 60.0)
        tenantContext.clientId = 1L

        val pickOrder = pickOrderService.releaseToPicking(orderId).single()
        val parentId = pickRepository.findByPickOrderId(pickOrder.id!!).single().id!!

        // Short by 20: no follow-up (P exhausted/excluded), no substitute → remainder 20 partial-shipped.
        pickOrderService.confirmPick(parentId, BigDecimal(40), targetUnitLoadId = null)
        entityManager.clear()

        val afterShort = pickRepository.findByPickOrderId(pickOrder.id!!)
        // No follow-up/substitute Pick is ever created for the uncovered remainder (it goes
        // straight to the ShortfallStrategy) -- so there is no "zero-picked" Pick row to (wrongly)
        // stamp actuals onto; the sole surviving row is the parent, which DOES carry actuals for
        // what it actually picked.
        assertThat(afterShort).hasSize(1)
        val parent = afterShort.single()
        assertThat(parent.followUpForPickId).isNull()
        assertThat(parent.state).isEqualTo(PickState.PICKED.code)
        assertThat(parent.pickedAmount).isEqualByComparingTo(BigDecimal(40))
        assertThat(parent.pickedLotNumber).isEqualTo(lot)
        assertThat(parent.pickedBestBefore).isEqualTo(bb)

        // All picks PICKED → PickOrder complete → order advances to PICKED(600).
        // D4: a short-picked line LEGITIMATELY shows pickedAmount < amount (PARTIAL_SHIP is the
        // default) — the derived value reports the lesser confirmed 40, never inflated to 60.
        val order = given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .body("state", `is`(600)).extract().jsonPath()
        assertThat(order.getDouble("lines[0].pickedAmount")).isEqualTo(40.0)
        assertThat(order.getDouble("lines[0].pickedAmount")).isLessThan(order.getDouble("lines[0].amount"))

        // The uncovered remainder emitted exactly one PickShortfallReported outbox row for this pick.
        val shortfallRows = QuarkusTransaction.requiringNew().call {
            outboxRepository.find(
                "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
                "Pick", parentId, "PickShortfallReported",
            ).count()
        }
        assertThat(shortfallRows).isEqualTo(1L)
    }
}

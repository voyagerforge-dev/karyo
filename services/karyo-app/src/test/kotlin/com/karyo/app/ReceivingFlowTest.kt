package com.karyo.app

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.repository.InventoryJournalRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.orders.repository.GoodsReceiptLineRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val FLOW_ROLES_USER = "manager"
private const val DOCK_LOCATION_ID = 900L
private const val DOCK_LOCATION_NAME = "DOCK-01"

/**
 * Integration test (REAL beans, no mocks) for the receiving flow:
 * ASN -> GoodsReceipt -> StockReceiver SPI -> inventory INCOMING stock (+QA lock)
 * -> AsnLine.receivedAmount bookkeeping -> receipt finish (INCOMING->ON_STOCK)
 * -> ASN force-finish shortage summary -> outbox feed.
 *
 * Locations are passed as raw id/name pairs (no layout seeding) — the same
 * convention as OrderReservationFlowTest; 2.3's LocationLookup will validate them.
 */
@QuarkusTest
class ReceivingFlowTest {

    @Inject
    lateinit var outboxRepository: OutboxEventRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var goodsReceiptLineRepository: GoodsReceiptLineRepository

    @Inject
    lateinit var journalRepository: InventoryJournalRepository

    // ── REST seeding helpers (existing services) ─────────────────────────

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Receiving Flow Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun seedTwoProducts(suffix: Long): Pair<Long, Long> {
        val itemUnitId = createItemUnit("RU-${suffix.toString().takeLast(10)}")
        return createProduct("RCV-SKU-A-$suffix", itemUnitId) to createProduct("RCV-SKU-B-$suffix", itemUnitId)
    }

    /**
     * Product with B6 receive-time constraint flags. NB: ProductService enforces
     * `shelflife > 0 -> bestBeforeMandatory` at create — fixtures must respect it.
     */
    private fun createConstrainedProduct(
        number: String,
        itemUnitId: Long,
        lotMandatory: Boolean = false,
        bestBeforeMandatory: Boolean = false,
        shelflife: Int? = null,
    ): Long {
        val fields = mutableListOf(
            """"number":"$number"""",
            """"name":"Receiving Constraint Product"""",
            """"itemUnitId":$itemUnitId""",
            """"lotMandatory":$lotMandatory""",
            """"bestBeforeMandatory":$bestBeforeMandatory""",
        )
        shelflife?.let { fields.add(""""shelflife":$it""") }
        return given()
            .contentType(ContentType.JSON)
            .body("{${fields.joinToString(",")}}")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    /** Creates a real PackagingUnit owned by [productId] — for A5 packagingUnitId validation fixtures. */
    private fun createPackagingUnit(productId: Long, name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","amount":1.0}""")
            .`when`().post("/api/v1/products/$productId/packaging-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createAsn(productA: Long, productB: Long, expectedA: Double, expectedB: Double): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"carrierName":"DHL","lines":[""" +
                    """{"itemDataId":$productA,"expectedAmount":$expectedA},""" +
                    """{"itemDataId":$productB,"expectedAmount":$expectedB}]}"""
            )
            .`when`().post("/api/v1/asns")
            .then().statusCode(201)
            .extract().jsonPath()

    private fun createReceipt(asnId: Long?, receiptType: Int? = null): JsonPath {
        return given()
            .contentType(ContentType.JSON)
            .body(receiptBody(asnId, receiptType))
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(201)
            .extract().jsonPath()
    }

    private fun receiptBody(asnId: Long?, receiptType: Int? = null): String {
        val fields = mutableListOf(""""carrierName":"DHL"""")
        asnId?.let { fields.add(""""asnId":$it""") }
        receiptType?.let { fields.add(""""receiptType":$it""") }
        return "{${fields.joinToString(",")}}"
    }

    private fun receiveLineBody(
        asnLineId: Long? = null,
        itemDataId: Long? = null,
        amount: Double,
        lotNumber: String? = null,
        bestBefore: String? = null,
        lockType: Int? = null,
        note: String? = null,
        allowOverReceipt: Boolean = false,
        unitLoadLabel: String? = null,
        serialNumber: String? = null,
        packagingUnitId: Long? = null,
    ): String {
        val fields = mutableListOf(
            """"amount":$amount""",
            """"locationId":$DOCK_LOCATION_ID""",
            """"locationName":"$DOCK_LOCATION_NAME"""",
            """"allowOverReceipt":$allowOverReceipt""",
        )
        asnLineId?.let { fields.add(""""asnLineId":$it""") }
        itemDataId?.let { fields.add(""""itemDataId":$it""") }
        lotNumber?.let { fields.add(""""lotNumber":"$it"""") }
        bestBefore?.let { fields.add(""""bestBefore":"$it"""") }
        lockType?.let { fields.add(""""lockType":$it""") }
        note?.let { fields.add(""""note":"$it"""") }
        unitLoadLabel?.let { fields.add(""""unitLoadLabel":"$it"""") }
        serialNumber?.let { fields.add(""""serialNumber":"$it"""") }
        packagingUnitId?.let { fields.add(""""packagingUnitId":$it""") }
        return "{${fields.joinToString(",")}}"
    }

    private fun receiveLine(receiptId: Long, body: String, expectedStatus: Int = 201): JsonPath =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun getStockUnit(stockUnitId: Long): JsonPath =
        given()
            .`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200)
            .extract().jsonPath()

    private fun assertStockState(stockUnitId: Long, state: Int, lockType: Int) {
        getStockUnit(stockUnitId).let {
            assertThat(it.getInt("state")).isEqualTo(state)
            assertThat(it.getInt("lockType")).isEqualTo(lockType)
        }
    }

    private fun releaseAsn(asnId: Long) {
        given()
            .`when`().post("/api/v1/asns/$asnId/release")
            .then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(100))
    }

    private fun getAsn(asnId: Long): JsonPath =
        given()
            .`when`().get("/api/v1/asns/$asnId")
            .then().statusCode(200)
            .extract().jsonPath()

    private fun lineReceivedOutboxCount(receiptId: Long): Long =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "GoodsReceipt", receiptId, "GoodsReceiptLineReceived",
        ).count()

    // ── B3: reverse a received line ───────────────────────────────────────

    private fun reverseLine(receiptId: Long, lineId: Long, expectedStatus: Int = 200): JsonPath =
        given()
            .`when`().delete("/api/v1/goods-receipts/$receiptId/lines/$lineId")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun finishReceipt(receiptId: Long, expectedStatus: Int = 200): JsonPath =
        given()
            .`when`().post("/api/v1/goods-receipts/$receiptId/finish")
            .then().statusCode(expectedStatus)
            .extract().jsonPath()

    private fun pauseReceipt(receiptId: Long) {
        given().contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/$receiptId/pause")
            .then().statusCode(200)
    }

    private fun adjustStockAmount(stockUnitId: Long, newAmount: Double) {
        given().contentType(ContentType.JSON)
            .body("""{"newAmount":$newAmount,"activityCode":"B3-TEST-ADJUST"}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/adjust")
            .then().statusCode(200)
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `full receiving flow - ASN, receipt, QA hold, finish, shortage summary`() {
        val suffix = System.nanoTime()
        val (productA, productB) = seedTwoProducts(suffix)

        // ASN with 2 expected lines: CREATED(50)
        val asnJson = createAsn(productA, productB, expectedA = 10.0, expectedB = 10.0)
        val asnId = asnJson.getLong("id")
        val lineAId = asnJson.getLong("lines[0].id")
        val lineBId = asnJson.getLong("lines[1].id")
        assertThat(asnJson.getInt("state")).isEqualTo(50)

        // Binding a receipt to a CREATED ASN is rejected (409 asn-not-receivable)
        given()
            .contentType(ContentType.JSON)
            .body("""{"asnId":$asnId}""")
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/asn-not-receivable"))

        releaseAsn(asnId)
        val receiptId = createReceipt(asnId).getLong("id")

        // Receive line A in full (bestBefore drives FIFO strategyDate)
        val receiveA = receiveLine(receiptId, receiveLineBody(asnLineId = lineAId, amount = 10.0, bestBefore = "2027-01-01"))
        val stockA = receiveA.getLong("stockUnitId")
        assertThat(receiveA.getInt("receipt.state")).isEqualTo(500) // receipt STARTED
        assertIncomingStockAtDock(stockA, receiveA.getString("unitLoadLabel"))

        // ASN side: STARTED(500), line A fully received -> FINISHED(700)
        getAsn(asnId).let {
            assertThat(it.getInt("state")).isEqualTo(500)
            assertThat(it.getDouble("lines[0].receivedAmount")).isEqualTo(10.0)
            assertThat(it.getInt("lines[0].state")).isEqualTo(700)
            assertThat(it.getInt("lines[0].progressPercent")).isEqualTo(100)
        }
        assertThat(lineReceivedOutboxCount(receiptId)).isEqualTo(1)

        // Receive line B partially (4 of 10) with a QUALITY_FAULT(103) lock (the QA hold)
        val receiveB = receiveLine(receiptId, receiveLineBody(asnLineId = lineBId, amount = 4.0, lockType = 103))
        val stockB = receiveB.getLong("stockUnitId")
        assertStockState(stockB, state = 100, lockType = 103)
        assertThat(lineReceivedOutboxCount(receiptId)).isEqualTo(2)

        // Finish receipt: non-held stock ON_STOCK(300); QA-held stays INCOMING + locked
        given()
            .`when`().post("/api/v1/goods-receipts/$receiptId/finish")
            .then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(700))
        assertStockState(stockA, state = 300, lockType = 0)
        assertStockState(stockB, state = 100, lockType = 103)

        // Force-finish ASN: line B closed short (4 of 10) -> shortage summary
        val finish = given()
            .`when`().post("/api/v1/asns/$asnId/finish")
            .then().statusCode(200)
            .extract().jsonPath()
        assertThat(finish.getInt("asn.state")).isEqualTo(700)
        assertThat(finish.getList<Any>("shortages")).hasSize(1)
        assertThat(finish.getLong("shortages[0].lineId")).isEqualTo(lineBId)
        assertThat(finish.getDouble("shortages[0].shortfall")).isEqualTo(6.0)
        assertThat(finish.getInt("asn.lines[1].state")).isEqualTo(700)
    }

    /** StockUnit: INCOMING(100), unlocked, strategyDate = bestBefore, UL at the dock. */
    private fun assertIncomingStockAtDock(stockUnitId: Long, expectedLabel: String) {
        getStockUnit(stockUnitId).let {
            assertThat(it.getInt("state")).isEqualTo(100)
            assertThat(it.getInt("lockType")).isEqualTo(0)
            assertThat(it.getString("strategyDate")).startsWith("2027-01-01")
            assertThat(it.getString("bestBefore")).isEqualTo("2027-01-01")
            assertThat(it.getString("unitLoadLabel")).isEqualTo(expectedLabel)
            assertThat(it.getLong("locationId")).isEqualTo(DOCK_LOCATION_ID)
            assertThat(it.getString("locationName")).isEqualTo(DOCK_LOCATION_NAME)
        }
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `over-receipt is rejected with 409 unless allowOverReceipt`() {
        val suffix = System.nanoTime()
        val (productA, productB) = seedTwoProducts(suffix)
        val asnJson = createAsn(productA, productB, expectedA = 10.0, expectedB = 10.0)
        val asnId = asnJson.getLong("id")
        val lineAId = asnJson.getLong("lines[0].id")
        releaseAsn(asnId)
        val receiptId = createReceipt(asnId).getLong("id")

        // 8 of 10 is fine
        receiveLine(receiptId, receiveLineBody(asnLineId = lineAId, amount = 8.0))

        // +5 would exceed expected 10 -> 409 over-receipt
        given()
            .contentType(ContentType.JSON)
            .body(receiveLineBody(asnLineId = lineAId, amount = 5.0))
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/over-receipt"))

        // Same receive with allowOverReceipt=true succeeds; receivedAmount = 13
        receiveLine(receiptId, receiveLineBody(asnLineId = lineAId, amount = 5.0, allowOverReceipt = true))
        getAsn(asnId).let {
            assertThat(it.getDouble("lines[0].receivedAmount")).isEqualTo(13.0)
            assertThat(it.getInt("lines[0].state")).isEqualTo(700) // FINISHED at >= expected
        }
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `blind receipt - no ASN, unit load reuse by label, finish puts stock on-stock`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val label = "UL-BLIND-$suffix"

        // First blind line creates the unit load
        val first = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 5.0, unitLoadLabel = label))
        assertThat(first.getString("unitLoadLabel")).isEqualTo(label)

        // Second line with the same label at the same location reuses the unit load
        val second = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 3.0, unitLoadLabel = label))
        assertThat(second.getLong("unitLoadId")).isEqualTo(first.getLong("unitLoadId"))

        // Cancel is rejected once lines were received
        given()
            .`when`().post("/api/v1/goods-receipts/$receiptId/cancel")
            .then().statusCode(409)

        // Finish: both stock units ON_STOCK(300)
        given()
            .`when`().post("/api/v1/goods-receipts/$receiptId/finish")
            .then().statusCode(200)
        assertThat(getStockUnit(first.getLong("stockUnitId")).getInt("state")).isEqualTo(300)
        assertThat(getStockUnit(second.getLong("stockUnitId")).getInt("state")).isEqualTo(300)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `arbitrary lockType and note at receipt - lock applied, note truncated visibly on journal, qaHold derived`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")

        // 80-char note: the GR line keeps the full text, the journal hop truncates VISIBLY
        // to take(47) + ellipsis (activity_code is VARCHAR(50)) — never a silent cut.
        val longNote = "0123456789".repeat(8)
        val expectedTruncated = longNote.take(47) + "…"

        // Line 1: LOT_EXPIRED(202) + long note
        val expired = receiveLine(
            receiptId,
            receiveLineBody(itemDataId = productA, amount = 3.0, lockType = 202, note = longNote),
        )
        val expiredLineId = expired.getLong("lineId")
        val expiredStockId = expired.getLong("stockUnitId")
        assertStockState(expiredStockId, state = 100, lockType = 202)
        assertThat(expired.getInt("receipt.lines[0].lockType")).isEqualTo(202)
        assertThat(expired.getString("receipt.lines[0].note")).isEqualTo(longNote)
        assertThat(expired.getBoolean("receipt.lines[0].qaHold")).isTrue() // derived: ANY lock

        // Journal hop: the setLock CHANGED row carries the visible truncation.
        val journalRows = journalRepository.find(
            "productNumber = ?1 and recordType = ?2", "RCV-SKU-A-$suffix", JournalRecordType.CHANGED.code,
        ).list()
        assertThat(journalRows).hasSize(1)
        assertThat(journalRows.first().activityCode).isEqualTo(expectedTruncated)
        assertThat(journalRows.first().activityCode).hasSize(48)

        // Line 2: GENERAL(1) + short note -> journal reason passes through untruncated
        val general = receiveLine(
            receiptId,
            receiveLineBody(itemDataId = productA, amount = 2.0, lockType = 1, note = "short note"),
        )
        assertStockState(general.getLong("stockUnitId"), state = 100, lockType = 1)
        val generalRows = journalRepository.find(
            "productNumber = ?1 and recordType = ?2 and activityCode = ?3",
            "RCV-SKU-A-$suffix", JournalRecordType.CHANGED.code, "short note",
        ).list()
        assertThat(generalRows).hasSize(1)

        // Line 3: no lock -> stock unlocked, derived qaHold false
        val plain = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 1.0))
        val plainLineId = plain.getLong("lineId")
        assertStockState(plain.getLong("stockUnitId"), state = 100, lockType = 0)
        assertThat(plain.getBoolean("receipt.lines[2].qaHold")).isFalse()
        assertThat(plain.get<Any?>("receipt.lines[2].lockType")).isNull()

        // Persisted rows: derived qaHold matches lockType (qa_hold column is gone).
        goodsReceiptLineRepository.findById(expiredLineId)!!.let {
            assertThat(it.lockType).isEqualTo(202)
            assertThat(it.note).isEqualTo(longNote)
            assertThat(it.qaHold).isTrue()
        }
        goodsReceiptLineRepository.findById(plainLineId)!!.let {
            assertThat(it.lockType).isNull()
            assertThat(it.note).isNull()
            assertThat(it.qaHold).isFalse()
        }
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `lockType outside the receive subset is rejected with 422`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")

        // Explicit UNLOCKED(0), STOCKTAKING(7) and an unknown code are all refused —
        // omission (null) is the only way to say "no lock".
        for (rejected in listOf(0, 7, 999)) {
            given()
                .contentType(ContentType.JSON)
                .body(receiveLineBody(itemDataId = productA, amount = 1.0, lockType = rejected))
                .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
                .then().statusCode(422)
                .body(
                    "type",
                    org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/unsupported-lock-type"),
                )
        }

        // Nothing was received: the receipt has no lines and never left CREATED.
        val receipt = given()
            .`when`().get("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200)
            .extract().jsonPath()
        assertThat(receipt.getList<Any>("lines")).isEmpty()
        assertThat(receipt.getInt("state")).isEqualTo(50)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `serialNumber and packagingUnitId are carried on both the GR line and the stock unit`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val packagingUnitId = createPackagingUnit(productA, "Case-$suffix")
        val receiptId = createReceipt(asnId = null).getLong("id")
        val serial = "SN-$suffix"

        // Line 1: received WITH serialNumber + packagingUnitId
        val withFields = receiveLine(
            receiptId,
            receiveLineBody(itemDataId = productA, amount = 1.0, serialNumber = serial, packagingUnitId = packagingUnitId),
        )
        val lineWithId = withFields.getLong("lineId")
        val stockWithId = withFields.getLong("stockUnitId")

        // Line 2: received WITHOUT either -> both stay null on both entities
        val without = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 2.0))
        val lineWithoutId = without.getLong("lineId")
        val stockWithoutId = without.getLong("stockUnitId")

        // Persisted GR line rows
        goodsReceiptLineRepository.findById(lineWithId)!!.let {
            assertThat(it.serialNumber).isEqualTo(serial)
            assertThat(it.packagingUnitId).isEqualTo(packagingUnitId)
        }
        goodsReceiptLineRepository.findById(lineWithoutId)!!.let {
            assertThat(it.serialNumber).isNull()
            assertThat(it.packagingUnitId).isNull()
        }

        // GR line response surface (fresh read; lines ordered by id ASC)
        val receipt = given()
            .`when`().get("/api/v1/goods-receipts/$receiptId")
            .then().statusCode(200)
            .extract().jsonPath()
        assertThat(receipt.getString("lines[0].serialNumber")).isEqualTo(serial)
        assertThat(receipt.getLong("lines[0].packagingUnitId")).isEqualTo(packagingUnitId)
        assertThat(receipt.get<Any?>("lines[1].serialNumber")).isNull()
        assertThat(receipt.get<Any?>("lines[1].packagingUnitId")).isNull()

        // Created stock units carry both onward -- A5: packagingUnitId is now VALIDATED
        // (exists + belongs to the received item) via PackagingUnitLookup, not stored blind.
        stockUnitRepository.findById(stockWithId)!!.let {
            assertThat(it.serialNumber).isEqualTo(serial)
            assertThat(it.packagingUnitId).isEqualTo(packagingUnitId)
        }
        assertThat(getStockUnit(stockWithId).getString("serialNumber")).isEqualTo(serial)
        assertThat(getStockUnit(stockWithId).getLong("packagingUnitId")).isEqualTo(packagingUnitId)
        stockUnitRepository.findById(stockWithoutId)!!.let {
            assertThat(it.serialNumber).isNull()
            assertThat(it.packagingUnitId).isNull()
        }
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `A5 receiving with an unknown packagingUnitId is rejected`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val bogusPackagingUnitId = 987_654_321L

        receiveLine(
            receiptId,
            receiveLineBody(itemDataId = productA, amount = 1.0, packagingUnitId = bogusPackagingUnitId),
            expectedStatus = 400,
        )
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `A5 receiving with a packagingUnitId belonging to a different item is rejected`() {
        val suffix = System.nanoTime()
        val (productA, productB) = seedTwoProducts(suffix)
        // Packaging unit belongs to productA, but the line receives productB against it.
        val packagingUnitId = createPackagingUnit(productA, "Case-mismatch-$suffix")
        val receiptId = createReceipt(asnId = null).getLong("id")

        receiveLine(
            receiptId,
            receiveLineBody(itemDataId = productB, amount = 1.0, packagingUnitId = packagingUnitId),
            expectedStatus = 400,
        )
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `RETOUR receipt - returns land inspected by default, explicit lock wins, locks survive finish`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)

        // Blind RETOUR receipt (myWMS GoodsReceiptType RETOUR = 1) — type echoed on create.
        val receipt = createReceipt(asnId = null, receiptType = 1)
        val receiptId = receipt.getLong("id")
        assertThat(receipt.getInt("receiptType")).isEqualTo(1)

        // Line 1: NO caller lockType -> defaults to QUALITY_FAULT(103): returned goods
        // are inspected before restocking. Derived qaHold follows.
        val defaulted = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 4.0))
        val defaultedStockId = defaulted.getLong("stockUnitId")
        assertStockState(defaultedStockId, state = 100, lockType = 103)
        assertThat(defaulted.getInt("receipt.receiptType")).isEqualTo(1)
        assertThat(defaulted.getInt("receipt.lines[0].lockType")).isEqualTo(103)
        assertThat(defaulted.getBoolean("receipt.lines[0].qaHold")).isTrue()

        // Line 2: explicit GENERAL(1) wins over the RETOUR default.
        val explicit = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 2.0, lockType = 1))
        val explicitStockId = explicit.getLong("stockUnitId")
        assertStockState(explicitStockId, state = 100, lockType = 1)
        assertThat(explicit.getInt("receipt.lines[1].lockType")).isEqualTo(1)

        // Finish: both lines are locked (derived qaHold) -> nothing promotes to ON_STOCK.
        given()
            .`when`().post("/api/v1/goods-receipts/$receiptId/finish")
            .then().statusCode(200)
            .body("state", org.hamcrest.CoreMatchers.`is`(700))
        assertStockState(defaultedStockId, state = 100, lockType = 103)
        assertStockState(explicitStockId, state = 100, lockType = 1)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `RETOUR cannot bind an ASN, unknown receiptType rejected, NORMAL gets no default lock, type immutable`() {
        val suffix = System.nanoTime()
        val (productA, productB) = seedTwoProducts(suffix)

        // RETOUR + asnId -> 422: customer returns do not arrive on supplier ASNs.
        val asnId = createAsn(productA, productB, expectedA = 5.0, expectedB = 5.0).getLong("id")
        releaseAsn(asnId)
        given()
            .contentType(ContentType.JSON)
            .body(receiptBody(asnId = asnId, receiptType = 1))
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(422)
            .body("type", org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/retour-with-asn"))

        // Unknown receiptType code -> 422.
        given()
            .contentType(ContentType.JSON)
            .body(receiptBody(asnId = null, receiptType = 5))
            .`when`().post("/api/v1/goods-receipts")
            .then().statusCode(422)
            .body("type", org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/unsupported-receipt-type"))

        // Regression pin: an ordinary receipt defaults to NORMAL(0) and a line without
        // lockType stays UNLOCKED — the RETOUR default must not leak.
        val normal = createReceipt(asnId = null)
        val normalId = normal.getLong("id")
        assertThat(normal.getInt("receiptType")).isEqualTo(0)
        val plain = receiveLine(normalId, receiveLineBody(itemDataId = productA, amount = 1.0))
        assertStockState(plain.getLong("stockUnitId"), state = 100, lockType = 0)
        assertThat(plain.get<Any?>("receipt.lines[0].lockType")).isNull()
        assertThat(plain.getBoolean("receipt.lines[0].qaHold")).isFalse()

        // Type is immutable: the B7 header-update path exists, but UpdateGoodsReceiptRequest
        // deliberately carries NO receiptType field — a smuggled receiptType in the PUT body
        // is ignored (unknown property) and the type stays NORMAL(0).
        given()
            .contentType(ContentType.JSON)
            .body("""{"receiptType":1,"prio":60}""")
            .`when`().put("/api/v1/goods-receipts/$normalId")
            .then().statusCode(200)
            .body("receiptType", org.hamcrest.CoreMatchers.`is`(0))
            .body("prio", org.hamcrest.CoreMatchers.`is`(60))
    }

    // ── B6: receive-time constraint enforcement ──────────────────────────

    private fun assertConstraintViolation(receiptId: Long, body: String) {
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .`when`().post("/api/v1/goods-receipts/$receiptId/lines")
            .then().statusCode(422)
            .body(
                "type",
                org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/receipt-constraint-violation"),
            )
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `lot-mandatory product without a lot is rejected on the blind path`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("RB-${suffix.toString().takeLast(10)}")
        val lotProduct = createConstrainedProduct("RCV-LOTM-$suffix", itemUnitId, lotMandatory = true)
        val receiptId = createReceipt(asnId = null).getLong("id")

        // Absent AND blank lots are both refused — nothing is written.
        assertConstraintViolation(receiptId, receiveLineBody(itemDataId = lotProduct, amount = 2.0))
        assertConstraintViolation(receiptId, receiveLineBody(itemDataId = lotProduct, amount = 2.0, lotNumber = "  "))

        // With a lot the line receives normally.
        val ok = receiveLine(receiptId, receiveLineBody(itemDataId = lotProduct, amount = 2.0, lotNumber = "LOT-$suffix"))
        assertThat(ok.getString("receipt.lines[0].lotNumber")).isEqualTo("LOT-$suffix")
        assertThat(ok.getList<Any>("receipt.lines")).hasSize(1) // the 422s wrote nothing
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `lot-mandatory is enforced on the ASN-bound path too`() {
        // Regression pin for the B6 blind spot: the ASN-bound path used to take item
        // identity from the AsnLine's denormalized fields and never loaded the product,
        // so lotMandatory was silently unenforced exactly where receiving really happens.
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("RA-${suffix.toString().takeLast(10)}")
        val lotProduct = createConstrainedProduct("RCV-ALOTM-$suffix", itemUnitId, lotMandatory = true)
        val plainProduct = createProduct("RCV-APLN-$suffix", itemUnitId)

        val asnJson = createAsn(lotProduct, plainProduct, expectedA = 5.0, expectedB = 5.0)
        val asnId = asnJson.getLong("id")
        val lotLineId = asnJson.getLong("lines[0].id")
        releaseAsn(asnId)
        val receiptId = createReceipt(asnId).getLong("id")

        assertConstraintViolation(receiptId, receiveLineBody(asnLineId = lotLineId, amount = 5.0))

        val ok = receiveLine(receiptId, receiveLineBody(asnLineId = lotLineId, amount = 5.0, lotNumber = "LOT-A-$suffix"))
        assertThat(ok.getString("receipt.lines[0].lotNumber")).isEqualTo("LOT-A-$suffix")
        getAsn(asnId).let {
            assertThat(it.getDouble("lines[0].receivedAmount")).isEqualTo(5.0) // only the compliant receive counted
        }
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `best-before-mandatory product without a date is rejected`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("RM-${suffix.toString().takeLast(10)}")
        // shelflife > 0 requires bestBeforeMandatory = true (ProductService config rule).
        val bbProduct = createConstrainedProduct(
            "RCV-BBM-$suffix", itemUnitId, bestBeforeMandatory = true, shelflife = 30,
        )
        val receiptId = createReceipt(asnId = null).getLong("id")

        assertConstraintViolation(receiptId, receiveLineBody(itemDataId = bbProduct, amount = 1.0))

        val future = java.time.LocalDate.now().plusDays(60).toString()
        val ok = receiveLine(receiptId, receiveLineBody(itemDataId = bbProduct, amount = 1.0, bestBefore = future))
        assertThat(ok.getString("receipt.lines[0].bestBefore")).isEqualTo(future)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `expired bestBefore is rejected for any product and unflagged products accept nulls`() {
        val suffix = System.nanoTime()
        val (plainProduct, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")

        // Expired goods are never receivable — even on a product with NO flags set.
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        assertConstraintViolation(receiptId, receiveLineBody(itemDataId = plainProduct, amount = 1.0, bestBefore = yesterday))

        // "Strictly before today": a bestBefore of today is still receivable.
        receiveLine(receiptId, receiveLineBody(itemDataId = plainProduct, amount = 1.0, bestBefore = java.time.LocalDate.now().toString()))

        // And an unflagged product still accepts null lot + null bestBefore, as before.
        val plain = receiveLine(receiptId, receiveLineBody(itemDataId = plainProduct, amount = 1.0))
        assertThat(plain.get<Any?>("receipt.lines[1].lotNumber")).isNull()
        assertThat(plain.get<Any?>("receipt.lines[1].bestBefore")).isNull()
    }

    // ── B3 removeGoodsReceiptLineWithStocks: reverse a received line ─────

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse line soft-deletes stock, marks line reversed, decrements ASN`() {
        val suffix = System.nanoTime()
        val (productA, productB) = seedTwoProducts(suffix)
        val asnJson = createAsn(productA, productB, expectedA = 10.0, expectedB = 10.0)
        val asnId = asnJson.getLong("id")
        val lineAId = asnJson.getLong("lines[0].id")
        releaseAsn(asnId)
        val receiptId = createReceipt(asnId).getLong("id")

        val receiveA = receiveLine(receiptId, receiveLineBody(asnLineId = lineAId, amount = 10.0))
        val stockA = receiveA.getLong("stockUnitId")
        val grLineId = receiveA.getLong("lineId")

        // Fully received -> ASN line FINISHED(700).
        getAsn(asnId).let {
            assertThat(it.getDouble("lines[0].receivedAmount")).isEqualTo(10.0)
            assertThat(it.getInt("lines[0].state")).isEqualTo(700)
        }

        val reversed = reverseLine(receiptId, grLineId)
        assertThat(reversed.getBoolean("lines[0].reversed")).isTrue()
        assertThat(reversed.get<Any?>("lines[0].reversedAt")).isNotNull()

        // Stock soft-deleted -> DELETABLE(1000).
        assertThat(getStockUnit(stockA).getInt("state")).isEqualTo(1000)

        // ASN line decremented back to 0 but its state does NOT walk backward
        // (OrderState is forward-only) -- quantity is the truth, state is the history.
        getAsn(asnId).let {
            assertThat(it.getDouble("lines[0].receivedAmount")).isEqualTo(0.0)
            assertThat(it.getInt("lines[0].state")).isEqualTo(700)
        }
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse refuses on FINISHED receipt with 409`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val received = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 5.0))
        val grLineId = received.getLong("lineId")

        finishReceipt(receiptId)

        reverseLine(receiptId, grLineId, expectedStatus = 409)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse refuses while receipt paused with 409`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val received = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 5.0))
        val grLineId = received.getLong("lineId")

        pauseReceipt(receiptId)

        given()
            .`when`().delete("/api/v1/goods-receipts/$receiptId/lines/$grLineId")
            .then().statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.`is`("https://karyo.com/errors/receipt-paused"))
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse refuses when stock amount changed with 409`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val received = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 10.0))
        val grLineId = received.getLong("lineId")
        val stockId = received.getLong("stockUnitId")

        // Mutate the stock amount out from under the receipt line -> amount-equality guard trips.
        adjustStockAmount(stockId, newAmount = 7.0)

        reverseLine(receiptId, grLineId, expectedStatus = 409)

        // Nothing was touched: stock keeps the adjusted amount and its INCOMING state, line unreversed.
        getStockUnit(stockId).let {
            assertThat(it.getDouble("amount")).isEqualTo(7.0)
            assertThat(it.getInt("state")).isEqualTo(100)
        }
        given().`when`().get("/api/v1/goods-receipts/$receiptId").then().statusCode(200)
            .body("lines[0].reversed", org.hamcrest.CoreMatchers.`is`(false))
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse is not repeatable - second DELETE 409s`() {
        val suffix = System.nanoTime()
        val (productA, _) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val received = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 5.0))
        val grLineId = received.getLong("lineId")

        reverseLine(receiptId, grLineId, expectedStatus = 200)
        reverseLine(receiptId, grLineId, expectedStatus = 409)
    }

    @Test
    @TestSecurity(
        user = FLOW_ROLES_USER,
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `finish skips reversed lines when promoting to ON_STOCK`() {
        val suffix = System.nanoTime()
        val (productA, productB) = seedTwoProducts(suffix)
        val receiptId = createReceipt(asnId = null).getLong("id")
        val lineOne = receiveLine(receiptId, receiveLineBody(itemDataId = productA, amount = 4.0))
        val lineTwo = receiveLine(receiptId, receiveLineBody(itemDataId = productB, amount = 6.0))
        val stockOne = lineOne.getLong("stockUnitId")
        val stockTwo = lineTwo.getLong("stockUnitId")

        reverseLine(receiptId, lineOne.getLong("lineId"))
        finishReceipt(receiptId)

        // Reversed line's stock stays DELETABLE (finish does not attempt to promote it).
        assertThat(getStockUnit(stockOne).getInt("state")).isEqualTo(1000)
        // The other line promotes normally.
        assertThat(getStockUnit(stockTwo).getInt("state")).isEqualTo(300)
    }
}

package com.karyo.inventory.api.v1

import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.domain.model.GoodsReceiptLine
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.vo.OrderState
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.CoreMatchers.*
import org.hamcrest.Matchers.greaterThan
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class StockUnitResourceTest {

    @Inject
    lateinit var goodsReceiptRepository: GoodsReceiptRepository

    @Inject
    lateinit var asnRepository: AsnRepository

    private fun createUnitLoad(label: String, unitLoadTypeId: Long = 1): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":$unitLoadTypeId,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Persists a GoodsReceipt (bound to a fresh Asn) whose sole line points at [stockUnitId]. */
    @Transactional
    fun receiveViaGr(stockUnitId: Long, clientId: Long = 1L, supplierName: String = "Northwind Traders"): GoodsReceipt {
        val seq = System.nanoTime()
        val asn = Asn().apply {
            this.clientId = clientId
            this.asnNumber = "ASN-SU-TEST-$seq"
            this.supplierName = supplierName
            this.state = OrderState.FINISHED.code
        }
        asn.lines.add(
            AsnLine().apply {
                this.asn = asn
                this.lineNumber = 1
                this.itemDataId = 10L
                this.itemDataNumber = "SKU-SU-TEST"
                this.expectedAmount = BigDecimal.TEN
                this.receivedAmount = BigDecimal.TEN
                this.state = OrderState.FINISHED.code
            },
        )
        asnRepository.persist(asn) // cascades AsnLine (ALL) -- line id available after this call

        val receipt = GoodsReceipt().apply {
            this.clientId = clientId
            this.receiptNumber = "GR-SU-TEST-$seq"
            this.state = OrderState.FINISHED.code
        }
        receipt.lines.add(
            GoodsReceiptLine().apply {
                this.goodsReceipt = receipt
                this.asnLineId = asn.lines.first().id
                this.itemDataId = 10L
                this.itemDataNumber = "SKU-SU-TEST"
                this.amount = BigDecimal.TEN
                this.locationId = 100L
                this.locationName = "A-01-01"
                this.unitLoadLabel = "UL-SU-TEST-$seq"
                this.stockUnitId = stockUnitId
                this.unitLoadId = 1L
            },
        )
        goodsReceiptRepository.persist(receipt)
        return receipt
    }

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, name: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"$name","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Creates a real PackagingUnit owned by [productId] — for A5 packagingUnitId validation fixtures. */
    private fun createPackagingUnit(productId: Long, name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","amount":1.0}""")
            .`when`().post("/api/v1/products/$productId/packaging-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `create stock unit and retrieve it`() {
        val ulId = createUnitLoad("UL-SU-TEST-001")

        val suId = given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-001","amount":100.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then()
            .statusCode(201)
            .body("amount", `is`(100.0f))
            .body("itemDataNumber", `is`("SKU-001"))
            .body("stateName", `is`("UNDEFINED"))
            .extract().jsonPath().getLong("id")

        given()
            .`when`().get("/api/v1/stock-units/$suId")
            .then()
            .statusCode(200)
            .body("id", `is`(suId.toInt()))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `adjust stock amount writes journal`() {
        val ulId = createUnitLoad("UL-SU-TEST-002")
        val suId = given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-002","amount":50.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        given()
            .contentType(ContentType.JSON)
            .body("""{"newAmount":30.0,"activityCode":"CYCLE-COUNT"}""")
            .`when`().post("/api/v1/stock-units/$suId/adjust")
            .then()
            .statusCode(200)
            .body("amount", `is`(30.0f))

        // Verify journal entry was created
        given()
            .queryParam("productNumber", "SKU-002")
            .`when`().get("/api/v1/journals")
            .then()
            .statusCode(200)
            .body("size()", greaterThan(0))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `state only moves forward`() {
        val ulId = createUnitLoad("UL-SU-TEST-003")
        val suId = given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-003","amount":10.0,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        // Forward transition should work
        given()
            .contentType(ContentType.JSON)
            .body("""{"state":600}""")
            .`when`().post("/api/v1/stock-units/$suId/change-state")
            .then()
            .statusCode(200)
            .body("state", `is`(600))

        // Backward transition should fail
        given()
            .contentType(ContentType.JSON)
            .body("""{"state":300}""")
            .`when`().post("/api/v1/stock-units/$suId/change-state")
            .then()
            .statusCode(409)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `lock and unlock stock`() {
        val ulId = createUnitLoad("UL-SU-TEST-004")
        val suId = given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-004","amount":10.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        // Lock
        given()
            .contentType(ContentType.JSON)
            .body("""{"lockType":7}""")
            .`when`().post("/api/v1/stock-units/$suId/lock")
            .then()
            .statusCode(200)
            .body("lockType", `is`(7))
            .body("lockTypeName", `is`("STOCKTAKING"))

        // Unlock
        given()
            .contentType(ContentType.JSON)
            .body("""{"lockType":0}""")
            .`when`().post("/api/v1/stock-units/$suId/lock")
            .then()
            .statusCode(200)
            .body("lockType", `is`(0))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `lock with reason writes the reason as journal activityCode`() {
        val ulId = createUnitLoad("UL-SU-TEST-005")
        val suId = given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-005","amount":10.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        given()
            .contentType(ContentType.JSON)
            .body("""{"lockType":103,"reason":"suspected damage on inbound"}""")
            .`when`().post("/api/v1/stock-units/$suId/lock")
            .then()
            .statusCode(200)
            .body("lockType", `is`(103))

        given()
            .queryParam("productNumber", "SKU-005")
            .`when`().get("/api/v1/journals")
            .then()
            .statusCode(200)
            .body("[0].activityCode", `is`("suspected damage on inbound"))
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `listed stock unit carries the product name via ProductLookup`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("SU-NAME-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("SU-NAME-SKU-$suffix", "Named Widget", itemUnitId)
        val ulId = createUnitLoad("UL-SU-NAME-$suffix")

        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":$productId,"itemDataNumber":"SU-NAME-SKU-$suffix","amount":5.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .body("itemDataName", `is`("Named Widget"))

        given()
            .queryParam("itemDataId", productId)
            .`when`().get("/api/v1/stock-units")
            .then()
            .statusCode(200)
            .body("content[0].itemDataName", `is`("Named Widget"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `stock unit received via a GR carries supplier, source ASN and received-at`() {
        val suffix = System.nanoTime()
        val ulId = createUnitLoad("UL-SU-GR-$suffix")
        val suId = given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-SU-GR-$suffix","amount":10.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .body("supplierName", nullValue())
            .body("sourceAsn", nullValue())
            .body("receivedAt", nullValue())
            .extract().jsonPath().getLong("id")

        receiveViaGr(suId, supplierName = "Northwind Traders")

        given()
            .`when`().get("/api/v1/stock-units/$suId")
            .then()
            .statusCode(200)
            .body("supplierName", `is`("Northwind Traders"))
            .body("sourceAsn", startsWith("ASN-SU-TEST-"))
            .body("receivedAt", notNullValue())

        given()
            .queryParam("unitLoadId", ulId)
            .`when`().get("/api/v1/stock-units")
            .then()
            .statusCode(200)
            .body("content[0].supplierName", `is`("Northwind Traders"))
            .body("content[0].sourceAsn", startsWith("ASN-SU-TEST-"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `a stock unit never received via a GR honest-gaps supplier, ASN and received`() {
        val suffix = System.nanoTime()
        val ulId = createUnitLoad("UL-SU-NOGR-$suffix")
        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-SU-NOGR-$suffix","amount":10.0,"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .body("supplierName", nullValue())
            .body("sourceAsn", nullValue())
            .body("receivedAt", nullValue())
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `aggregateStocks reflects the unit-load type — false for Euro Pallet, true for Pick Bin`() {
        val suffix = System.nanoTime()
        // unitLoadTypeId 1 = "Euro Pallet" (aggregateStocks=false), seeded by V105.
        val palletUlId = createUnitLoad("UL-AGG-PALLET-$suffix", unitLoadTypeId = 1)
        // unitLoadTypeId 2 = "Pick Bin" (aggregateStocks=true), seeded by V105.
        val binUlId = createUnitLoad("UL-AGG-BIN-$suffix", unitLoadTypeId = 2)

        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-AGG-PALLET-$suffix","amount":10.0,"unitLoadId":$palletUlId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .body("aggregateStocks", `is`(false))

        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-AGG-BIN-$suffix","amount":10.0,"unitLoadId":$binUlId}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .body("aggregateStocks", `is`(true))
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `A5 creating stock with an unknown packagingUnitId is rejected`() {
        val ulId = createUnitLoad("UL-SU-PU-UNKNOWN")

        given()
            .contentType(ContentType.JSON)
            .body("""{"itemDataId":10,"itemDataNumber":"SKU-PU-UNKNOWN","amount":10.0,"unitLoadId":$ulId,"packagingUnitId":987654321}""")
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `A5 creating stock with a packagingUnitId belonging to a different item is rejected`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("SU-PUM-IU-${suffix.toString().takeLast(8)}")
        val productA = createProduct("SU-PU-MISMATCH-A-$suffix", "Product A", itemUnitId)
        val productB = createProduct("SU-PU-MISMATCH-B-$suffix", "Product B", itemUnitId)
        val packagingUnitId = createPackagingUnit(productA, "Case-$suffix")
        val ulId = createUnitLoad("UL-SU-PU-MISMATCH-$suffix")

        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$productB,"itemDataNumber":"SU-PU-MISMATCH-B-$suffix",""" +
                    """"amount":10.0,"unitLoadId":$ulId,"packagingUnitId":$packagingUnitId}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(
        user = "operator",
        roles = ["inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `A5 creating stock with a valid packagingUnitId stores and exposes it`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("SU-PU-OK-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("SU-PU-OK-$suffix", "Product OK", itemUnitId)
        val packagingUnitId = createPackagingUnit(productId, "Case-$suffix")
        val ulId = createUnitLoad("UL-SU-PU-OK-$suffix")

        val suId = given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$productId,"itemDataNumber":"SU-PU-OK-$suffix",""" +
                    """"amount":10.0,"unitLoadId":$ulId,"packagingUnitId":$packagingUnitId}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .body("packagingUnitId", `is`(packagingUnitId.toInt()))
            .extract().jsonPath().getLong("id")

        given()
            .`when`().get("/api/v1/stock-units/$suId")
            .then()
            .statusCode(200)
            .body("packagingUnitId", `is`(packagingUnitId.toInt()))
    }
}

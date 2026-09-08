package com.karyo.product.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PageMetadata
import com.karyo.common.patch.Patchable
import com.karyo.product.dto.*
import com.karyo.product.exception.ProductException
import com.karyo.product.service.DefaultBarcodeResolver
import com.karyo.product.service.ProductService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import com.karyo.sequence.util.CheckDigit
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import java.math.BigDecimal

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = org.mockito.ArgumentMatchers.any<T>() ?: null as T

private fun anyLong(): Long = org.mockito.ArgumentMatchers.anyLong()

@QuarkusTest
class ProductResourceTest {

    @InjectMock
    lateinit var productService: ProductService

    @InjectMock
    lateinit var barcodeResolver: DefaultBarcodeResolver

    @AfterEach
    fun resetStructuralErrorResolver() {
        TestStructuralErrorResolver.structuralErrorFor = null
    }

    private fun sampleProduct(
        id: Long = 1L,
        number: String = "SKU-001",
        name: String = "Test Product",
        state: Int = 100,
    ) = ProductResponse(
        id = id,
        number = number,
        name = name,
        description = "A test product",
        state = state,
        itemUnit = ItemUnitResponse(id = 1L, name = "PCS", unitType = "PIECE"),
        scale = 0,
        weight = BigDecimal("1.500"),
        height = BigDecimal("10.000"),
        width = BigDecimal("5.000"),
        depth = BigDecimal("3.000"),
        volume = BigDecimal("150.000"),
        lotMandatory = false,
        bestBeforeMandatory = false,
        shelflife = null,
        serialNoRecordType = "NO_RECORD",
        defaultUnitLoadTypeId = null,
        defaultStorageStrategyId = null,
        zoneId = null,
        tradeGroup = null,
        imageUrl = null,
        numbers = emptyList(),
        packagingUnits = emptyList(),
        created = "2026-03-02T00:00:00Z",
        modified = "2026-03-02T00:00:00Z",
    )

    private fun validCreateRequest() = CreateProductRequest(
        number = "SKU-001",
        name = "Test Product",
        itemUnitId = 1L,
    )

    // ── 1. Can create product ────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create product - 201`() {
        doReturn(sampleProduct()).`when`(productService).createProduct(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/products")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("number", `is`("SKU-001"))
            .body("name", `is`("Test Product"))
            .body("state", `is`(100))
    }

    // ── 2. Can get product by ID ─────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get product by ID - 200`() {
        doReturn(sampleProduct()).`when`(productService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/products/1")
            .then()
            .statusCode(200)
            .body("id", `is`(1))
            .body("number", `is`("SKU-001"))
    }

    // ── 3. Can list products ─────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list products - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleProduct()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(productService).listProductsPaginated(anyLong(), anyObj())

        given()
            .`when`().get("/api/v1/products")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].number", `is`("SKU-001"))
            .body("page.totalElements", `is`(1))
    }

    // ── 4. Can update product ────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can update product - 200`() {
        doReturn(sampleProduct(state = 700)).`when`(productService).updateProduct(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            // Raw JSON, not the Kotlin object -- RestAssured's own client-side ObjectMapper
            // never sees the server's PatchableModule/JsonInclude wiring, so serializing
            // UpdateProductRequest() directly would emit `"description":{}` for the untouched
            // Patchable field (the pre-D2 default constructor still works fine for
            // non-Patchable fields like state).
            .body("""{"state":700}""")
            .`when`().put("/api/v1/products/1")
            .then()
            .statusCode(200)
            .body("state", `is`(700))
    }

    // ── 4a. Update product setting defaultPackagingUnitId echoes it ──────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update product setting defaultPackagingUnitId - response echoes it`() {
        doReturn(sampleProduct().copy(defaultPackagingUnitId = 5L))
            .`when`(productService).updateProduct(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"defaultPackagingUnitId":5}""")
            .`when`().put("/api/v1/products/1")
            .then()
            .statusCode(200)
            .body("defaultPackagingUnitId", `is`(5))
    }

    // ── 4b. Foreign defaultPackagingUnitId maps to 422 ───────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update product with foreign defaultPackagingUnitId returns 422`() {
        doThrow(ProductException.InvalidPackagingUnit(999L))
            .`when`(productService).updateProduct(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"defaultPackagingUnitId":999}""")
            .`when`().put("/api/v1/products/1")
            .then()
            .statusCode(422)
    }

    // ── 4c. D2 tri-state deserialization (Patchable<String>/<Long>) ──────
    // These pin the DESERIALIZATION shape only (productService is mocked, so business
    // behavior is proven separately by ProductPatchSemanticsTest's real-DB round trip).
    // Inspects the real recorded invocation (mockingDetails) rather than ArgumentCaptor --
    // Kotlin's non-null return-type check on `ArgumentCaptor.capture()` throws immediately
    // against Mockito's null matcher-recording placeholder, which corrupts the matcher
    // stack for every later test in the same JVM.

    /** Last `updateProduct(...)` call's request argument, straight off the recorded invocation. */
    private fun lastUpdateRequest(): UpdateProductRequest {
        val call = org.mockito.Mockito.mockingDetails(productService).invocations
            .last { it.method.name == "updateProduct" }
        return call.arguments[1] as UpdateProductRequest
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT omitting description deserializes it to Patchable Absent`() {
        doReturn(sampleProduct()).`when`(productService).updateProduct(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"Renamed"}""")
            .`when`().put("/api/v1/products/1")
            .then()
            .statusCode(200)

        assertThat(lastUpdateRequest().description).isEqualTo(Patchable.Absent)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT with explicit null description deserializes it to Patchable Null`() {
        doReturn(sampleProduct()).`when`(productService).updateProduct(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"description":null}""")
            .`when`().put("/api/v1/products/1")
            .then()
            .statusCode(200)

        assertThat(lastUpdateRequest().description).isEqualTo(Patchable.Null)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT with a description value deserializes it to Patchable Value`() {
        doReturn(sampleProduct()).`when`(productService).updateProduct(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"description":"a new description"}""")
            .`when`().put("/api/v1/products/1")
            .then()
            .statusCode(200)

        assertThat(lastUpdateRequest().description).isEqualTo(Patchable.Value("a new description"))
    }

    // ── 5. Can get product by SKU ────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get product by SKU - 200`() {
        doReturn(sampleProduct()).`when`(productService).findByNumber("SKU-001", 1L)

        given()
            .`when`().get("/api/v1/products/by-number/SKU-001")
            .then()
            .statusCode(200)
            .body("number", `is`("SKU-001"))
    }

    // ── 6. Can lookup product by barcode ─────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can lookup product by barcode - 200`() {
        // ProductResource consults the BarcodeResolver SPI chain: supports() gate then resolve()
        // The real DefaultBarcodeResolver supports all barcodes; stub the mock to match.
        doReturn(true).`when`(barcodeResolver).supports("EAN-123")
        doReturn(sampleProduct()).`when`(barcodeResolver).resolve("EAN-123", 1L)

        given()
            .`when`().get("/api/v1/products/by-barcode/EAN-123")
            .then()
            .statusCode(200)
            .body("number", `is`("SKU-001"))
    }

    // ── 7. Barcode not found returns 404 ─────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `barcode not found returns 404`() {
        doReturn(true).`when`(barcodeResolver).supports("UNKNOWN")
        doReturn(null).`when`(barcodeResolver).resolve("UNKNOWN", 1L)

        given()
            .`when`().get("/api/v1/products/by-barcode/UNKNOWN")
            .then()
            .statusCode(404)
    }

    // ── 7a. structuralError short-circuits by-barcode lookup to 400, not 404 ─────
    // SC18: a resolver declaring the code structurally invalid pre-empts the resolve chain
    // entirely -- TestStructuralErrorResolver (priority 1, ahead of the mocked
    // DefaultBarcodeResolver at DEFAULT_PRIORITY) is a REAL CDI bean, not a Mockito mock, so
    // this exercises ProductResource's actual chain-consultation code.

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `by-barcode lookup with a structural error returns 400 not 404`() {
        TestStructuralErrorResolver.structuralErrorFor = "STRUCT-BAD"
        TestStructuralErrorResolver.structuralErrorMessage = "bad format"

        given()
            .`when`().get("/api/v1/products/by-barcode/STRUCT-BAD")
            .then()
            .statusCode(400)
            .body("detail", containsString("bad format"))
    }

    // ── 8. Can add barcode to product ────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can add barcode to product - 201`() {
        val numberResponse = ItemDataNumberResponse(id = 10L, number = "EAN-456", numberType = "EAN", packagingUnitId = null)
        doReturn(numberResponse).`when`(productService).addBarcode(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateItemDataNumberRequest(number = "EAN-456", numberType = "EAN"))
            .`when`().post("/api/v1/products/1/numbers")
            .then()
            .statusCode(201)
            .body("number", `is`("EAN-456"))
    }

    // ── 8a. Barcode with manufacturerName round-trips ────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add barcode with manufacturerName - response echoes it`() {
        val numberResponse = ItemDataNumberResponse(
            id = 11L, number = "EAN-789", numberType = "EAN",
            packagingUnitId = null, manufacturerName = "Acme Mfg",
        )
        doReturn(numberResponse).`when`(productService).addBarcode(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateItemDataNumberRequest(number = "EAN-789", numberType = "EAN", manufacturerName = "Acme Mfg"))
            .`when`().post("/api/v1/products/1/numbers")
            .then()
            .statusCode(201)
            .body("manufacturerName", `is`("Acme Mfg"))
    }

    // ── 8b. SC18 wiring: valid EAN-13 accepted ────────────────────────────
    // productService is mocked here (as throughout this class), so this pins the REST-layer
    // wiring only -- the real Gs1BarcodeValidation math is exercised in ProductServiceTest
    // (unmocked ProductService bean), which is mutation-verifiable against the actual code.

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add barcode with valid EAN-13 - 201`() {
        val numberResponse = ItemDataNumberResponse(id = 12L, number = "4006381333931", numberType = "EAN13", packagingUnitId = null)
        doReturn(numberResponse).`when`(productService).addBarcode(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateItemDataNumberRequest(number = "4006381333931", numberType = "EAN13"))
            .`when`().post("/api/v1/products/1/numbers")
            .then()
            .statusCode(201)
            .body("number", `is`("4006381333931"))
    }

    // ── 8c. SC18 wiring: ValidationFailed maps to 400 naming the expected digit ───

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add barcode with EAN-13 wrong final digit - 400 names the expected digit`() {
        // Real CheckDigit math on the test side (same lib the SUT uses) computes the expected
        // digit for the known-good payload "400638133393" -> 1 (see CheckDigitTest).
        val expected = CheckDigit.gs1Mod10("400638133393")
        doThrow(ProductException.ValidationFailed("Barcode '4006381333932' has an invalid GS1 check digit: expected $expected, got '2'"))
            .`when`(productService).addBarcode(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateItemDataNumberRequest(number = "4006381333932", numberType = "EAN13"))
            .`when`().post("/api/v1/products/1/numbers")
            .then()
            .statusCode(400)
            .body("detail", containsString("expected $expected"))
    }

    // ── 8d. SC18 wiring: non-GS1 numberType accepted (skips validation) ───

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add barcode with internal numberType - 201, validation skipped`() {
        val numberResponse = ItemDataNumberResponse(id = 13L, number = "NOT-A-BARCODE", numberType = "internal", packagingUnitId = null)
        doReturn(numberResponse).`when`(productService).addBarcode(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateItemDataNumberRequest(number = "NOT-A-BARCODE", numberType = "internal"))
            .`when`().post("/api/v1/products/1/numbers")
            .then()
            .statusCode(201)
            .body("numberType", `is`("internal"))
    }

    // ── 9. Can remove barcode from product ───────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can remove barcode from product - 204`() {
        given()
            .`when`().delete("/api/v1/products/1/numbers/10")
            .then()
            .statusCode(204)
    }

    // ── 10. Can add packaging unit ───────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can add packaging unit - 201`() {
        val puResponse = PackagingUnitResponse(
            id = 5L, name = "Box of 12", amount = BigDecimal("12"),
            itemUnitName = "PCS", weight = null, height = null, width = null, depth = null,
        )
        doReturn(puResponse).`when`(productService).addPackagingUnit(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreatePackagingUnitRequest(name = "Box of 12", amount = BigDecimal("12")))
            .`when`().post("/api/v1/products/1/packaging-units")
            .then()
            .statusCode(201)
            .body("name", `is`("Box of 12"))
    }

    // ── 10a. Packaging unit with explicit packingLevel round-trips ───────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add packaging unit with packingLevel 1 - response carries it`() {
        val puResponse = PackagingUnitResponse(
            id = 5L, name = "Carton", amount = BigDecimal("12"),
            itemUnitName = "PCS", weight = null, height = null, width = null, depth = null,
            packingLevel = 1,
        )
        doReturn(puResponse).`when`(productService).addPackagingUnit(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreatePackagingUnitRequest(name = "Carton", amount = BigDecimal("12"), packingLevel = 1))
            .`when`().post("/api/v1/products/1/packaging-units")
            .then()
            .statusCode(201)
            .body("packingLevel", `is`(1))
    }

    // ── 10b. Packaging unit omitting packingLevel defaults to 0 ──────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add packaging unit without packingLevel - defaults to 0`() {
        val puResponse = PackagingUnitResponse(
            id = 5L, name = "Box of 12", amount = BigDecimal("12"),
            itemUnitName = "PCS", weight = null, height = null, width = null, depth = null,
        )
        doReturn(puResponse).`when`(productService).addPackagingUnit(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreatePackagingUnitRequest(name = "Box of 12", amount = BigDecimal("12")))
            .`when`().post("/api/v1/products/1/packaging-units")
            .then()
            .statusCode(201)
            .body("packingLevel", `is`(0))
    }

    // ── 10c. Negative packingLevel is rejected ────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `add packaging unit with negative packingLevel returns 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreatePackagingUnitRequest(name = "Box of 12", amount = BigDecimal("12"), packingLevel = -1))
            .`when`().post("/api/v1/products/1/packaging-units")
            .then()
            .statusCode(400)
    }

    // ── 11. Can remove packaging unit ────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can remove packaging unit - 204`() {
        given()
            .`when`().delete("/api/v1/products/1/packaging-units/5")
            .then()
            .statusCode(204)
    }

    // ── 11a. Can delete product ──────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can delete product - 204`() {
        given()
            .`when`().delete("/api/v1/products/1")
            .then()
            .statusCode(204)
    }

    // ── 11b. Delete missing product returns 404 ──────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delete missing product returns 404`() {
        doThrow(ProductException.NotFound("Product", "id=999"))
            .`when`(productService).deleteProduct(anyLong(), anyLong())

        given()
            .`when`().delete("/api/v1/products/999")
            .then()
            .statusCode(404)
    }

    // ── 11c. Read-only user cannot delete product ────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `product-read role cannot delete product - 403`() {
        given()
            .`when`().delete("/api/v1/products/1")
            .then()
            .statusCode(403)
    }

    // ── 12. Read-only user cannot create product ─────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `product-read role cannot create product - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/products")
            .then()
            .statusCode(403)
    }

    // ── 13. No auth returns 401 ──────────────────────────────────────────

    @Test
    fun `unauthenticated request gets 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/products")
            .then()
            .statusCode(401)
    }

    // ── 14. Validation rejects blank SKU ─────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create product with blank SKU returns 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateProductRequest(number = "", name = "Test", itemUnitId = 1L))
            .`when`().post("/api/v1/products")
            .then()
            .statusCode(400)
    }

    // ── 15. Validation rejects blank name ────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create product with blank name returns 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateProductRequest(number = "SKU-BAD", name = "", itemUnitId = 1L))
            .`when`().post("/api/v1/products")
            .then()
            .statusCode(400)
    }
}

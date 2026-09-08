package com.karyo.inventory.exception

import com.karyo.common.exception.ProblemDetail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.mockk.mockk
import io.mockk.every
import jakarta.ws.rs.core.UriInfo
import java.math.BigDecimal
import java.net.URI

class ExceptionMapperTest {
    private val mapper = InventoryExceptionMapper()
    private val uriInfo = mockk<UriInfo>()

    @BeforeEach
    fun setup() {
        mapper.uriInfo = uriInfo
        every { uriInfo.requestUri } returns URI("/api/v1/stock-units/42")
    }

    @Test
    fun `NotFound maps to 404`() {
        val ex = InventoryException.NotFound("StockUnit", 42L)
        val response = mapper.toResponse(ex)
        assertThat(response.status).isEqualTo(404)
        val body = response.entity as ProblemDetail
        assertThat(body.type).isEqualTo("https://karyo.com/errors/not-found")
        assertThat(body.detail).contains("42")
    }

    @Test
    fun `InsufficientStock maps to 409`() {
        val ex = InventoryException.InsufficientStock(BigDecimal("5"), BigDecimal("10"))
        val response = mapper.toResponse(ex)
        assertThat(response.status).isEqualTo(409)
        val body = response.entity as ProblemDetail
        assertThat(body.type).isEqualTo("https://karyo.com/errors/insufficient-stock")
    }

    @Test
    fun `StockLocked maps to 409`() {
        val ex = InventoryException.StockLocked(1L, 7)
        val response = mapper.toResponse(ex)
        assertThat(response.status).isEqualTo(409)
    }

    @Test
    fun `InvalidStateTransition maps to 409`() {
        val ex = InventoryException.InvalidStateTransition(1L, 0, 300)
        val response = mapper.toResponse(ex)
        assertThat(response.status).isEqualTo(409)
    }

    @Test
    fun `DuplicateName maps to 409`() {
        val ex = InventoryException.DuplicateName("UnitLoadType", "Standard Pallet")
        val response = mapper.toResponse(ex)
        assertThat(response.status).isEqualTo(409)
        val body = response.entity as ProblemDetail
        assertThat(body.type).isEqualTo("https://karyo.com/errors/duplicate-name")
        assertThat(body.detail).contains("Standard Pallet")
    }

    @Test
    fun `ValidationFailed maps to 400`() {
        val ex = InventoryException.ValidationFailed("amount must be positive")
        val response = mapper.toResponse(ex)
        assertThat(response.status).isEqualTo(400)
    }
}

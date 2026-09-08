package com.karyo.product.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.product.dto.*
import com.karyo.product.exception.ProductException
import com.karyo.product.service.ProductService
import com.karyo.product.spi.BarcodeResolver
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/products")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ProductResource(
    private val productService: ProductService,
    private val barcodeResolvers: Instance<BarcodeResolver>,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("product-read")
    fun listProducts(@BeanParam pagination: PaginationParams): PaginatedResponse<ProductResponse> =
        productService.listProductsPaginated(tenantContext.clientId, pagination)

    @GET
    @Path("/{id}")
    @RolesAllowed("product-read")
    fun getProduct(@PathParam("id") id: Long): ProductResponse =
        productService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("product-write")
    fun createProduct(@Valid request: CreateProductRequest): Response {
        val product = productService.createProduct(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(product).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("product-write")
    fun updateProduct(@PathParam("id") id: Long, @Valid request: UpdateProductRequest): ProductResponse =
        productService.updateProduct(id, request, tenantContext.clientId)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("product-write")
    fun deleteProduct(@PathParam("id") id: Long): Response {
        productService.deleteProduct(id, tenantContext.clientId)
        return Response.noContent().build()
    }

    @GET
    @Path("/by-number/{number}")
    @RolesAllowed("product-read")
    fun getByNumber(@PathParam("number") number: String): ProductResponse =
        productService.findByNumber(number, tenantContext.clientId)

    @GET
    @Path("/by-barcode/{code}")
    @RolesAllowed("product-read")
    fun getByBarcode(@PathParam("code") code: String): Response {
        // Strategy-SPI: consult resolvers in priority order, first non-null wins
        // (built-in DefaultBarcodeResolver runs last; a custom resolver pre-empts it).
        val sortedResolvers = barcodeResolvers.sortedBy { it.priority() }

        // SC18: structural pre-check runs BEFORE the resolve chain, same priority order,
        // first non-null wins -- a structural objection means the code is malformed for a
        // symbology this resolver recognizes, so it's a 422 (bad request), never a 404.
        sortedResolvers.firstNotNullOfOrNull { it.structuralError(code) }?.let { error ->
            throw ProductException.ValidationFailed(error)
        }

        val product = sortedResolvers
            .filter { it.supports(code) }
            .firstNotNullOfOrNull { it.resolve(code, tenantContext.clientId) }
            ?: throw ProductException.NotFound("Product", "barcode=$code")
        return Response.ok(product).build()
    }

    @POST
    @Path("/{id}/numbers")
    @RolesAllowed("product-write")
    fun addBarcode(@PathParam("id") id: Long, @Valid request: CreateItemDataNumberRequest): Response {
        val number = productService.addBarcode(id, request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(number).build()
    }

    @DELETE
    @Path("/{id}/numbers/{numberId}")
    @RolesAllowed("product-write")
    fun removeBarcode(@PathParam("id") id: Long, @PathParam("numberId") numberId: Long): Response {
        productService.removeBarcode(id, numberId, tenantContext.clientId)
        return Response.noContent().build()
    }

    @POST
    @Path("/{id}/packaging-units")
    @RolesAllowed("product-write")
    fun addPackagingUnit(@PathParam("id") id: Long, @Valid request: CreatePackagingUnitRequest): Response {
        val pu = productService.addPackagingUnit(id, request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(pu).build()
    }

    @DELETE
    @Path("/{id}/packaging-units/{puId}")
    @RolesAllowed("product-write")
    fun removePackagingUnit(@PathParam("id") id: Long, @PathParam("puId") puId: Long): Response {
        productService.removePackagingUnit(id, puId, tenantContext.clientId)
        return Response.noContent().build()
    }
}

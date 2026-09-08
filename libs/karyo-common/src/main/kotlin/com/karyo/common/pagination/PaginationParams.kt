package com.karyo.common.pagination

import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.QueryParam

/**
 * JAX-RS @BeanParam for standard pagination query parameters.
 * Use as: fun list(@BeanParam pagination: PaginationParams)
 *
 * Supports: ?page=0&size=20&sort=name,asc
 * Per api-standards.md Section 5.
 */
class PaginationParams {
    @QueryParam("page")
    @DefaultValue("0")
    var page: Int = 0

    @QueryParam("size")
    @DefaultValue("20")
    var size: Int = 20

    @QueryParam("sort")
    var sort: List<String>? = null
}

package com.karyo.common.pagination

/**
 * Standard paginated response wrapper matching api-standards.md format.
 * Frontend expects: { content: T[], page: { number, size, totalElements, totalPages } }
 */
data class PaginatedResponse<T>(
    val content: List<T>,
    val page: PageMetadata,
)

data class PageMetadata(
    val number: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** Helper to build PaginatedResponse from a content list and pagination info. */
fun <T> paginatedResponse(
    content: List<T>,
    pageNumber: Int,
    pageSize: Int,
    totalElements: Long,
): PaginatedResponse<T> = PaginatedResponse(
    content = content,
    page = PageMetadata(
        number = pageNumber,
        size = pageSize,
        totalElements = totalElements,
        totalPages = if (pageSize > 0) ((totalElements + pageSize - 1) / pageSize).toInt() else 0,
    ),
)

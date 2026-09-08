package com.karyo.ai.exception

/**
 * Thrown when a confirm-time re-authorization check fails: the authenticated user
 * lacks the write role required to execute the proposed action. Mapped to HTTP 403
 * in CopilotResource.confirm. This re-validates authority at execution time (the
 * confirm endpoint is only @RolesAllowed("inventory-read")), mirroring the create-time
 * role guard in WarehouseActionTools.
 */
class CopilotForbiddenException(message: String) : RuntimeException(message)

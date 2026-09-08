package com.karyo.work.exception

/** Thrown when a claim loses a race (the item was already taken). Mapped to HTTP 409. */
class WorkClaimConflictException(message: String) : RuntimeException(message)

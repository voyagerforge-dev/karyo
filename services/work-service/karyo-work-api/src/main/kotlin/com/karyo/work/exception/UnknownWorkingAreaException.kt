package com.karyo.work.exception

/** Thrown when `?workingAreaId=` on `GET /api/v1/work/available` names a nonexistent working area. Mapped to HTTP 400. */
class UnknownWorkingAreaException(workingAreaId: Long) : RuntimeException("Unknown working area: $workingAreaId")

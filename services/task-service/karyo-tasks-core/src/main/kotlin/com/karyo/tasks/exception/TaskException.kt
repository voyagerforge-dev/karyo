package com.karyo.tasks.exception

import com.karyo.common.exception.KaryoException

sealed class TaskException(message: String) : KaryoException(message) {

    class NotFound(entityType: String, identifier: Any) :
        TaskException("$entityType not found: $identifier")

    /** A referenced entity (unit load, location) does not exist. */
    class InvalidReference(entityType: String, identifier: Any) :
        TaskException("$entityType not found: $identifier")

    class InvalidTransition(entityId: Long, currentState: Int, targetState: Int) :
        TaskException("Invalid state transition for transport order $entityId: $currentState -> $targetState")

    class ValidationFailed(detail: String) :
        TaskException(detail)

    /** Completing a task needs a destination — neither a suggestion nor an override is present. */
    class NoDestination(entityId: Long) :
        TaskException("Transport order $entityId cannot be completed: no destination (no suggestion and none supplied)")

    /** Cancellation gate beyond the state machine (task already STARTED/closed). */
    class NotCancelable(entityId: Long, reason: String) :
        TaskException("Transport order $entityId cannot be canceled: $reason")

    /** PT18: assign/start/complete refused because the task is paused (409). */
    class TransportPaused(entityId: Long) :
        TaskException("Transport order $entityId is paused — resume it before assigning, starting, or completing")

    /** PT18 pause/resume conflict (409): double pause, resume of a non-paused task, or pausing a closed one. */
    class TransportPauseConflict(entityId: Long, reason: String) :
        TaskException("Transport order $entityId pause conflict: $reason")

    /**
     * PT16 confirm-merge refused (409): the order's own unit load must carry EXACTLY ONE live
     * (non-DELETABLE) stock unit to fold into the destination unit load — a mixed-content load
     * has no single "the stock" to merge, so mixing is refused rather than guessed at. Karyo-
     * native rule; myWMS has no confirm-merge concept to be faithful to.
     */
    class MixedSourceLoad(entityId: Long) :
        TaskException(
            "Transport order $entityId cannot confirm-merge: source unit load must carry exactly one live stock unit",
        )
}

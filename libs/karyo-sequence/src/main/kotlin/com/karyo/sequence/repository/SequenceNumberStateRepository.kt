package com.karyo.sequence.repository

import com.karyo.sequence.domain.SequenceNumberState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

@ApplicationScoped
class SequenceNumberStateRepository : PanacheRepository<SequenceNumberState> {

    /** Pessimistic-write-locks the row for [name] so concurrent counter bumps serialize. */
    fun findLocked(name: String): SequenceNumberState? =
        find("name", name).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
}

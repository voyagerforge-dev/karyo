package com.karyo.layout.repository

import com.karyo.layout.domain.model.StorageStrategyArea
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StorageStrategyAreaRepository : PanacheRepository<StorageStrategyArea> {
    /** Produced interface (Task 1, consumed by Tasks 3/5): a strategy's areas, in order. */
    fun orderedByStrategy(strategyId: Long): List<StorageStrategyArea> =
        list("storageStrategy.id = ?1 order by orderIndex asc", strategyId)

    fun deleteByStrategy(strategyId: Long): Long = delete("storageStrategy.id", strategyId)

    fun countByArea(areaId: Long): Long = count("storageArea.id", areaId)
}

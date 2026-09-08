package com.karyo.reporting.service

import com.karyo.reporting.api.v1.dto.CategoryVolumeResponse
import com.karyo.reporting.repository.CategoryVolumeRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class CategoryVolumeService(private val repo: CategoryVolumeRepository) {

    fun build(clientId: Long, range: KpiRange, now: Instant): List<CategoryVolumeResponse> {
        val (start, _) = range.window(now)
        return repo.volumeByCategory(clientId, start)
            .map { CategoryVolumeResponse(it.category, it.volume, it.lineCount) }
    }
}

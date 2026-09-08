package com.karyo.demo.gen

import com.karyo.reporting.domain.model.ReportDefinition
import com.karyo.reporting.repository.ReportDefinitionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Phase B (B19): a few real, honest saved-report examples so the Reports page's
 * "Saved reports" card has real content on a freshly seeded demo instance. Not idempotent
 * (like the other generators) — re-seeding is expected to `reset` first.
 */
@ApplicationScoped
class ReportDefinitionSeedGenerator(
    private val reportDefinitionRepository: ReportDefinitionRepository,
) {
    @Transactional
    fun generate(clientId: Long): Int {
        val definitions = EXAMPLES.map { (name, type) ->
            ReportDefinition().apply {
                this.clientId = clientId
                this.name = name
                this.reportType = type
                this.params = "{}"
                this.owner = "demo-seed"
            }
        }
        reportDefinitionRepository.persist(definitions)
        return definitions.size
    }

    companion object {
        private val EXAMPLES = listOf(
            "Daily throughput" to "throughput",
            "Aging stock" to "aging-stock",
            "Category volume" to "category-volume",
        )
    }
}

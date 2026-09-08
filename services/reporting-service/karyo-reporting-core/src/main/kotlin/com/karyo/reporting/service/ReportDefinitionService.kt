package com.karyo.reporting.service

import com.karyo.reporting.domain.model.ReportDefinition
import com.karyo.reporting.dto.CreateReportDefinitionRequest
import com.karyo.reporting.dto.ReportDefinitionResponse
import com.karyo.reporting.repository.ReportDefinitionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.NotFoundException

@ApplicationScoped
class ReportDefinitionService(private val repository: ReportDefinitionRepository) {

    fun list(clientId: Long): List<ReportDefinitionResponse> =
        repository.findByClient(clientId).map { toResponse(it) }

    @Transactional
    fun create(request: CreateReportDefinitionRequest, clientId: Long): ReportDefinitionResponse {
        val entity = ReportDefinition().apply {
            this.clientId = clientId
            this.name = request.name
            this.reportType = request.reportType
            this.params = request.params
            this.owner = request.owner
        }
        repository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun delete(id: Long, clientId: Long) {
        val entity = repository.findByIdAndClient(id, clientId) ?: throw NotFoundException("report definition $id")
        repository.delete(entity)
    }

    private fun toResponse(e: ReportDefinition) = ReportDefinitionResponse(
        id = requireNotNull(e.id), name = e.name, reportType = e.reportType,
        params = e.params, owner = e.owner, created = e.created,
    )
}

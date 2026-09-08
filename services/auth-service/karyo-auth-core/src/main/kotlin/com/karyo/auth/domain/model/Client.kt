package com.karyo.auth.domain.model

import com.karyo.auth.vo.ClientState
import com.karyo.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Transient

/**
 * A goods owner — the entity behind the `client_id` on [com.karyo.common.domain.TenantEntity].
 *
 * Extends [BaseEntity], not `TenantEntity`: a client *is* the tenant dimension and so carries
 * no `client_id` of its own.
 *
 * `number` is the business key (myWMS `toUniqueString()` returns it), though `name` is unique too.
 */
@Entity
@Table(name = "clients")
class Client : BaseEntity() {

    @Column(nullable = false, length = 255)
    lateinit var name: String

    @Column(nullable = false, length = 64)
    lateinit var number: String

    @Column(nullable = false, length = 64)
    var code: String = ""

    @Column(nullable = false, length = 255)
    var email: String = ""

    @Column(nullable = false, length = 64)
    var phone: String = ""

    @Column(nullable = false, length = 64)
    var fax: String = ""

    @Column(nullable = false)
    var state: Int = ClientState.ACTIVE.code

    /**
     * The one and only system client, derived from `id == 0` exactly as myWMS does
     * (`Client.isSystemClient()`). Deliberately not a column: id 0 is also
     * `TenantEntity`'s default, so the two must never be able to disagree.
     */
    @get:Transient
    val isSystemClient: Boolean
        get() = id == 0L
}

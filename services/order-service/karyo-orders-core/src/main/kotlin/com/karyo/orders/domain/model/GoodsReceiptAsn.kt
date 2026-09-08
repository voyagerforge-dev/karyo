package com.karyo.orders.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/**
 * Join row for the [GoodsReceipt] <-> [Asn] many-to-many (V424): a receipt may span
 * several ASNs (one truck, several supplier shipments) and, in principle, one ASN
 * may be received across several receipts. Deliberately a plain ID-pair entity, not
 * a [jakarta.persistence.ManyToMany] mapping -- the codebase's convention of explicit
 * join entities / ID-sets over implicit collection mappings (ADR-004's cross-aggregate
 * ID-only style, kept here even though both sides live in the same `orders` module,
 * because [GoodsReceipt] and [Asn] are independent aggregates with their own lifecycles).
 *
 * No surrogate id: the natural composite key IS the relationship, matching the exact
 * `goods_receipt_asns` table shape (V424).
 */
@Entity
@Table(name = "goods_receipt_asns")
class GoodsReceiptAsn() {

    @EmbeddedId
    var id: GoodsReceiptAsnId = GoodsReceiptAsnId()

    constructor(goodsReceiptId: Long, asnId: Long) : this() {
        id = GoodsReceiptAsnId(goodsReceiptId, asnId)
    }
}

/** Composite key for [GoodsReceiptAsn] -- must be Serializable + equals/hashCode per the JPA `@EmbeddedId` contract. */
@Embeddable
data class GoodsReceiptAsnId(
    @Column(name = "goods_receipt_id") var goodsReceiptId: Long = 0,
    @Column(name = "asn_id") var asnId: Long = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

package com.karyo.product.exception

import com.karyo.common.exception.KaryoException

sealed class ProductException(message: String) : KaryoException(message) {
    class NotFound(entityType: String, identifier: Any) :
        ProductException("$entityType not found: $identifier")

    class DuplicateSku(number: String) :
        ProductException("Product with SKU '$number' already exists in this tenant")

    class DuplicateBarcode(barcode: String) :
        ProductException("Barcode '$barcode' is already assigned to another product in this tenant")

    class DuplicateSubstitution(itemDataId: Long, substituteItemDataId: Long) :
        ProductException("Substitution $itemDataId -> $substituteItemDataId already exists in this tenant")

    class InvalidStateTransition(currentState: Int, targetState: Int) :
        ProductException("Invalid state transition: $currentState -> $targetState")

    class InvalidConfiguration(detail: String) :
        ProductException("Invalid product configuration: $detail")

    class InvalidItemUnit(id: Long) :
        ProductException("Item unit with id $id not found")

    class InvalidPackagingUnit(id: Long) :
        ProductException("Packaging unit with id $id not found on this product")

    /** SC18: GS1 check-digit / structural barcode validation failures (write path + by-barcode lookup). */
    class ValidationFailed(detail: String) : ProductException(detail)
}

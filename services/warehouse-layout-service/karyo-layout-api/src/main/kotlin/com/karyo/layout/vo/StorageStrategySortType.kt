package com.karyo.layout.vo

/**
 * myWMS `StorageStrategy.sorts` (locations-layout sprint Task 5) — the ordered list of
 * candidate-ranking dimensions a putaway strategy can request, replacing the previously
 * free-text/unenforced `sorts` column. `code` mirrors myWMS's own ordinal values (0-8), same
 * convention as [com.karyo.layout.vo.LockType]/`com.karyo.inventory.api.vo.StockState`.
 *
 * The CSV token in `StorageStrategy.sorts` is the enum's [name] (case-insensitive) — see
 * `com.karyo.layout.service.StorageStrategySortParser` for read-time (lenient, skip-unknown)
 * vs. save-time (strict, throws) parsing, and `com.karyo.layout.service.CandidateOrdering`
 * for how each type is turned into a comparator.
 */
enum class StorageStrategySortType(val code: Int) {
    CLIENT(0),
    STORAGEAREA(1),
    ZONE(2),
    CAPACITY(3),
    ALLOCATION(4),
    POSITION_X(5),
    POSITION_Y(6),
    NAME(7),
    ORDERINDEX(8);

    companion object {
        fun fromCode(code: Int): StorageStrategySortType =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown StorageStrategySortType code: $code")

        /**
         * Case-insensitive lookup by [name] — the `sorts` CSV token format. Returns `null`
         * (never throws) for an unrecognized token: callers decide whether that means "skip
         * it" (read-time, existing rows may hold garbage) or "reject the whole request"
         * (save-time validation) — see `StorageStrategySortParser`.
         */
        fun fromToken(token: String): StorageStrategySortType? =
            entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
    }
}

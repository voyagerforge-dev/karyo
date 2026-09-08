package com.karyo.documents

/**
 * Tiny RFC4180 CSV writer, Excel-compatible (D12 -- CSV was chosen over Apache POI: the row's
 * actual intent is "get the data into Excel", which a BOM-prefixed CSV satisfies with zero new
 * dependencies; a real `.xlsx` writer is not needed).
 *
 * - A field is quoted only when it contains a comma, a double quote, or a CR/LF; an embedded
 *   quote is escaped by doubling it (RFC4180 §2.5/2.7).
 * - A field is additionally escaped against **CSV/formula injection** before quoting -- see
 *   [asCsvField] KDoc for the rule. This matters specifically because these CSVs are explicitly
 *   Excel-targeted (BOM below) and carry untrusted strings (customer names, SKUs, lot numbers).
 * - Rows end with CRLF ("\r\n") -- the RFC4180 line ending.
 * - Output is prefixed with a UTF-8 byte-order mark (`EF BB BF`, i.e. `﻿` encoded as UTF-8):
 *   Excel has no CSV-encoding picker, it sniffs the BOM to choose UTF-8 over the system codepage
 *   -- without it, non-ASCII bytes (accented names, etc.) render as mojibake on import.
 *
 * Not a streaming writer -- callers build the whole export as a `List<List<String?>>` in memory.
 * That's an acceptable tradeoff at the [EXPORT_MAX_ROWS] (10k) cap used by the CSV export REST
 * endpoints; revisit (`StreamingOutput` + incremental writes) only if that cap is ever raised
 * materially.
 */
object CsvWriter {

    private const val BOM = "﻿"
    private const val CRLF = "\r\n"
    private val NEEDS_QUOTING = charArrayOf(',', '"', '\r', '\n')

    /**
     * Characters that Excel/Google Sheets/LibreOffice treat as "this cell is a formula" when
     * they are the FIRST character of a cell -- the OWASP CSV Injection trigger set
     * (https://owasp.org/www-community/attacks/CSV_Injection). `=` and `@` start a formula
     * directly; `+` and `-` also do in Excel (e.g. `=1+1` can be typed as `+1+1`); a leading tab
     * or CR is included per the same OWASP guidance (whitespace-prefixed formulas some parsers
     * still evaluate).
     */
    private val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * A field matching this in full is a plain (optionally negative) decimal amount -- e.g. a
     * `-5.00` reserved-amount or on-hand quantity -- and is exempted from formula-injection
     * escaping even though `-` is a trigger char, so numeric export columns keep opening as
     * numbers in Excel rather than as apostrophe-prefixed text.
     *
     * The regex has no `+` alternative: a value like `+123` (leading `+`, all digits) does NOT
     * match (only a leading `-` is accepted) and therefore IS escaped. That is deliberately
     * conservative -- no numeric field this writer emits is ever `+`-prefixed on the wire, so
     * treating any literal leading `+` as a possible formula trigger costs nothing real and
     * closes the OWASP-flagged `+1+1`-style vector.
     */
    private val PLAIN_DECIMAL = Regex("""^-?\d+(\.\d+)?$""")

    /**
     * Row cap shared by every CSV export endpoint (D12 follow-up F2) -- single source of truth
     * for the three CSV export REST resources (`DeliveryOrderResource`, `StockUnitResource`,
     * `PickOrderResource`), which used to each redeclare the same `10_000` literal locally.
     */
    const val EXPORT_MAX_ROWS = 10_000

    /**
     * Renders [headers] plus [rows] (a null cell becomes an empty field) as CSV bytes, capping
     * the rendered rows at [cap] and appending an honest `# truncated at <cap> rows` marker line
     * whenever [totalElements] exceeds [cap].
     *
     * Centralizes the cap-vs-marker decision that used to be triplicated -- once per export
     * resource, each hand-rolling its own `if (total > EXPORT_MAX_ROWS) listOf("# truncated ...")
     * else emptyList()` ternary passed in as a `trailingLines` parameter.
     *
     * [rows] does not need to be pre-capped by the caller -- rows beyond [cap] are dropped here
     * -- but a caller whose row-building is itself expensive per row (e.g. a per-pick-order
     * lookup) may still pre-cap for efficiency before calling in; that makes this a no-op safety
     * net rather than the source of truth. [totalElements] defaults to `rows.size`, which is only
     * correct when the caller has NOT pre-capped -- pass the true, un-capped total explicitly
     * when it has (e.g. a paginated query's `totalElements`, or the full in-memory list size
     * before a manual `.take(cap)`).
     */
    fun write(
        headers: List<String>,
        rows: List<List<String?>>,
        cap: Int = EXPORT_MAX_ROWS,
        totalElements: Long = rows.size.toLong(),
    ): ByteArray {
        val capped = if (rows.size > cap) rows.subList(0, cap) else rows
        val sb = StringBuilder(BOM)
        sb.append(headers.joinToString(",") { it.asCsvField() }).append(CRLF)
        for (row in capped) {
            sb.append(row.joinToString(",") { (it ?: "").asCsvField() }).append(CRLF)
        }
        if (totalElements > cap) {
            sb.append("# truncated at $cap rows").append(CRLF)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * A field starting with a [FORMULA_TRIGGER_CHARS] character is prefixed with a single
     * apostrophe (the OWASP CSV Injection mitigation --
     * https://owasp.org/www-community/attacks/CSV_Injection) UNLESS it is a [PLAIN_DECIMAL]
     * amount, BEFORE RFC4180 quoting is applied. Excel renders a leading apostrophe as "force
     * this cell to text" and hides the apostrophe itself, so a customer named
     * `=HYPERLINK("http://evil","x")` or a note `=cmd|'/c calc'!A1` opens as inert text instead
     * of executing. This is the single choke point for every field this writer emits (headers
     * included, though header names are never attacker-controlled).
     */
    private fun String.asCsvField(): String {
        val escaped =
            if (isNotEmpty() && this[0] in FORMULA_TRIGGER_CHARS && !PLAIN_DECIMAL.matches(this)) "'$this" else this
        return if (escaped.any { ch -> ch in NEEDS_QUOTING }) "\"${escaped.replace("\"", "\"\"")}\"" else escaped
    }
}

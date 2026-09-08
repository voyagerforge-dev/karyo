package com.karyo.documents

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Plain-JUnit test for [CsvWriter] -- no Quarkus, no HTTP, pure string/byte assertions.
 * Home chosen to mirror [com.karyo.common.PatchableTest] (a Quarkus-free utility test living
 * in `karyo-app`'s test source set even though the class under test is in `libs/karyo-documents`),
 * rather than `DocumentRendererTest`'s `@QuarkusTest` (that class needs a live Qute `Engine` bean;
 * `CsvWriter` needs nothing injected).
 */
class CsvWriterTest {

    private fun bodyAfterBom(bytes: ByteArray): String {
        assertThat(bytes.copyOfRange(0, 3)).isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }

    @Test
    fun `plain fields need no quoting`() {
        val bytes = CsvWriter.write(headers = listOf("a", "b"), rows = listOf(listOf("1", "2")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a,b\r\n1,2\r\n")
    }

    @Test
    fun `output is prefixed with the UTF-8 BOM`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = emptyList())
        assertThat(bytes.copyOfRange(0, 3)).isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    }

    @Test
    fun `rows end with CRLF`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("1"), listOf("2")))
        val body = bodyAfterBom(bytes)
        assertThat(body).isEqualTo("a\r\n1\r\n2\r\n")
        assertThat(body).doesNotContain("\n\n") // no bare LF sneaking in alongside CRLF
    }

    @Test
    fun `a field containing a comma is quoted`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("Acme, Inc.")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n\"Acme, Inc.\"\r\n")
    }

    @Test
    fun `a field containing a double quote is quoted and the quote is doubled`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("""She said "hi"""")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n\"She said \"\"hi\"\"\"\r\n")
    }

    @Test
    fun `a field containing a newline is quoted`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("line1\nline2")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n\"line1\nline2\"\r\n")
    }

    @Test
    fun `a field containing a CR is quoted`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("line1\rline2")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n\"line1\rline2\"\r\n")
    }

    @Test
    fun `a null cell becomes an empty field`() {
        val bytes = CsvWriter.write(headers = listOf("a", "b"), rows = listOf(listOf("x", null)))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a,b\r\nx,\r\n")
    }

    @Test
    fun `no truncation marker when totalElements is within the default cap`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("1")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n1\r\n")
    }

    // ── F2: centralized truncation cap (was triplicated per export resource) ──

    @Test
    fun `cap=2, 3 rows in -- 2 rows are rendered plus a truncated marker line`() {
        val bytes = CsvWriter.write(
            headers = listOf("a"),
            rows = listOf(listOf("1"), listOf("2"), listOf("3")),
            cap = 2,
        )
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n1\r\n2\r\n# truncated at 2 rows\r\n")
    }

    @Test
    fun `cap=2, 2 rows in -- no truncation marker`() {
        val bytes = CsvWriter.write(
            headers = listOf("a"),
            rows = listOf(listOf("1"), listOf("2")),
            cap = 2,
        )
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n1\r\n2\r\n")
    }

    @Test
    fun `cap=2 with an explicit totalElements beyond the pre-capped rows list still marks truncated`() {
        // Mirrors DeliveryOrderResource/StockUnitResource: rows are already DB-limited to <= cap,
        // but totalElements (the full matching-row count) is passed explicitly and is what
        // drives the marker -- not rows.size.
        val bytes = CsvWriter.write(
            headers = listOf("a"),
            rows = listOf(listOf("1"), listOf("2")),
            cap = 2,
            totalElements = 5,
        )
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n1\r\n2\r\n# truncated at 2 rows\r\n")
    }

    // ── F1: CSV/formula-injection escaping (OWASP CSV Injection) ──
    // A field whose first character would be read by Excel as "this cell is a formula" gets a
    // leading apostrophe prefix (before RFC4180 quoting), UNLESS the field is a plain decimal
    // amount. One test per trigger char, per the security review.

    @Test
    fun `a field starting with equals is prefixed with an apostrophe`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("=SUM(A1)")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n'=SUM(A1)\r\n")
    }

    @Test
    fun `a field starting with plus is prefixed with an apostrophe`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("+1+1")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n'+1+1\r\n")
    }

    @Test
    fun `a field starting with minus and non-numeric content is prefixed with an apostrophe`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("-cmd|'/c calc'!A1")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n'-cmd|'/c calc'!A1\r\n")
    }

    @Test
    fun `a field starting with at and no comma is prefixed with an apostrophe, unquoted`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("@HYPERLINK")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n'@HYPERLINK\r\n")
    }

    @Test
    fun `a field starting with a tab is prefixed with an apostrophe`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("\tSUM(A1)")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n'\tSUM(A1)\r\n")
    }

    @Test
    fun `a field starting with a CR is prefixed with an apostrophe and still quoted (CR also needs quoting)`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("\rSUM(A1)")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n\"'\rSUM(A1)\"\r\n")
    }

    @Test
    fun `a plain negative decimal amount is exempt from formula-injection escaping`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("-5.00")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n-5.00\r\n")
    }

    @Test
    fun `a plain negative integer amount is exempt from formula-injection escaping`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("-5")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n-5\r\n")
    }

    @Test
    fun `a leading-plus numeric-looking field is still escaped -- deliberately conservative`() {
        // "+123" does NOT match the plain-decimal regex (only a leading "-" is exempted), so it
        // IS escaped even though it looks numeric. Documented tradeoff, not a bug: no numeric
        // field this writer emits is ever "+"-prefixed on the wire.
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("+123")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n'+123\r\n")
    }

    @Test
    fun `formula-injection escaping interacts correctly with RFC4180 quoting -- escaped then quoted`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("=a,b")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\n\"'=a,b\"\r\n")
    }

    @Test
    fun `a mid-string trigger character is not escaped -- only a LEADING trigger char matters`() {
        val bytes = CsvWriter.write(headers = listOf("a"), rows = listOf(listOf("Acme=Corp")))
        assertThat(bodyAfterBom(bytes)).isEqualTo("a\r\nAcme=Corp\r\n")
    }
}

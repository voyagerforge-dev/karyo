package com.karyo.work

import com.karyo.work.dto.WorkRef
import com.karyo.work.vo.WorkType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkRefTest {
    @Test
    fun `token round-trips through parse`() {
        val ref = WorkRef(WorkType.PICK, 123)
        assertThat(ref.token()).isEqualTo("PICK:123")
        assertThat(WorkRef.parse("PICK:123")).isEqualTo(ref)
    }

    @Test
    fun `parse rejects malformed token`() {
        assertThatThrownBy { WorkRef.parse("PICK") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

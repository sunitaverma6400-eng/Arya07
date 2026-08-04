package com.arya.ai.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateTimeContextTest {

    @Test
    fun `current date line reflects the real system year, not a hardcoded one`() {
        val line = DateTimeContext.currentDateTimeLine()
        val actualYear = Calendar.getInstance().get(Calendar.YEAR).toString()
        assertTrue(
            "Expected the real current year ($actualYear) to appear in: $line",
            line.contains(actualYear)
        )
    }

    @Test
    fun `current date line tells the model not to trust its own training-data date`() {
        val line = DateTimeContext.currentDateTimeLine()
        assertTrue(line.contains("training data", ignoreCase = true))
    }
}

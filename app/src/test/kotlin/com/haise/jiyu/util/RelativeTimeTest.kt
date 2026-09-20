package com.haise.jiyu.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    @Test
    fun `buckets switch at the documented boundaries`() {
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.NOW, 0), relativeTimeBucket(0))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.MINUTES, 1), relativeTimeBucket(1))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.MINUTES, 59), relativeTimeBucket(59))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.HOURS, 1), relativeTimeBucket(60))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.HOURS, 23), relativeTimeBucket(1439))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.DAYS, 1), relativeTimeBucket(1440))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.DAYS, 29), relativeTimeBucket(43199))
        assertEquals(RelativeTimeBucket(RelativeTimeUnit.DATE, 0), relativeTimeBucket(43200))
    }

    @Test
    fun `a timestamp in the future is treated as now`() {
        assertEquals(RelativeTimeUnit.NOW, relativeTimeBucket(-5).unit)
    }
}

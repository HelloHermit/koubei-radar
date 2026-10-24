package com.koubeiradar.data.amap

import com.koubeiradar.data.shanghaiTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpeningHoursTest {
    private fun isOpen(hours: String, hour: Int, minute: Int = 0): Boolean? =
        OpeningHours.isOpenAt(hours, shanghaiTime(hour, minute))

    @Test
    fun `a single range is half open`() {
        assertEquals(false, isOpen("08:30-21:30", 8, 29))
        assertEquals(true, isOpen("08:30-21:30", 8, 30))
        assertEquals(true, isOpen("08:30-21:30", 12))
        assertEquals(true, isOpen("08:30-21:30", 21, 29))
        assertEquals(false, isOpen("08:30-21:30", 21, 30))
        assertEquals(false, isOpen("08:30-21:30", 0))
    }

    @Test
    fun `several ranges separated by space comma semicolon or their full width forms`() {
        for (separator in listOf(" ", ",", ";", "，", "；", "、", " , ", "  ")) {
            val hours = "09:00-14:00${separator}17:00-21:00"
            assertEquals(true, isOpen(hours, 12), "'$hours' at 12:00")
            assertEquals(false, isOpen(hours, 15), "'$hours' at 15:00")
            assertEquals(true, isOpen(hours, 17), "'$hours' at 17:00")
            assertEquals(false, isOpen(hours, 21), "'$hours' at 21:00")
        }
    }

    @Test
    fun `around the clock`() {
        assertEquals(true, isOpen("00:00-24:00", 0))
        assertEquals(true, isOpen("00:00-24:00", 3, 15))
        assertEquals(true, isOpen("00:00-24:00", 23, 59))
    }

    @Test
    fun `a range crossing midnight`() {
        assertEquals(false, isOpen("18:00-02:00", 17, 59))
        assertEquals(true, isOpen("18:00-02:00", 18))
        assertEquals(true, isOpen("18:00-02:00", 23, 30))
        assertEquals(true, isOpen("18:00-02:00", 0))
        assertEquals(true, isOpen("18:00-02:00", 1, 59))
        assertEquals(false, isOpen("18:00-02:00", 2))
        assertEquals(false, isOpen("18:00-02:00", 12))
    }

    @Test
    fun `a range ending at midnight`() {
        assertEquals(true, isOpen("10:00-24:00", 23, 59))
        assertEquals(false, isOpen("10:00-24:00", 9, 59))
    }

    @Test
    fun `tolerates cosmetic variations`() {
        assertEquals(true, isOpen("9:00-21:00", 9))
        assertEquals(true, isOpen("08:30 - 21:30", 12))
        assertEquals(true, isOpen("10:00~22:00", 12))
        assertEquals(true, isOpen("10:00至22:00", 12))
        assertEquals(true, isOpen("10：00-22：00", 12))
        assertEquals(true, isOpen("  10:00-22:00  ", 12))
    }

    @Test
    fun `anything that is not a list of ranges is unknown`() {
        val unparseable = listOf(
            "", "  ", "休息", "24小时营业", "周一至周日 10:00-22:00", "10:00-14:00 abc", "abc 10:00-14:00",
            "25:00-26:00", "10:00-22:61", "10:00-10:00", "24:00-02:00", "10:00-", "-22:00", "10:00", "1000-2200",
        )
        for (hours in unparseable) {
            assertNull(isOpen(hours, 12), "'$hours'")
        }
    }

    @Test
    fun `one bad range makes the whole value unknown`() {
        assertNull(isOpen("09:00-14:00 17:00-25:00", 12))
    }

    @Test
    fun `uses Shanghai time whatever the machine's zone`() {
        // 20:00 UTC is 04:00 the next morning in Shanghai.
        val twentyHundredUtc = ZonedDateTime.parse("2026-03-04T20:00:00Z").toInstant().toEpochMilli()

        assertEquals(false, OpeningHours.isOpenAt("18:00-02:00", twentyHundredUtc))
        assertEquals(true, OpeningHours.isOpenAt("03:00-05:00", twentyHundredUtc))
    }
}

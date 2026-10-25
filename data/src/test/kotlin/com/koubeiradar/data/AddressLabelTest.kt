package com.koubeiradar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressLabelTest {
    @Test
    fun `area and hint are joined, either alone stands, blanks count as absent`() {
        assertEquals("朝阳区望京街道 · 望京SOHO附近", shortAddressLabel("朝阳区望京街道", "望京SOHO", null))
        assertEquals("朝阳区望京街道", shortAddressLabel(" 朝阳区望京街道 ", "  ", "ignored"))
        assertEquals("望京SOHO附近", shortAddressLabel(null, "望京SOHO", "ignored"))
    }

    @Test
    fun `the formatted address is used without its prefixes, but never emptied by them`() {
        assertEquals("朝阳区望京街道", shortAddressLabel("", null, "北京市朝阳区望京街道", "北京市", "北京市", null))
        assertEquals("广东省", shortAddressLabel(null, null, "广东省", "广东省"))
    }

    @Test
    fun `nothing left is null`() {
        assertNull(shortAddressLabel(null, null, null))
        assertNull(shortAddressLabel(" ", "", "  ", "北京市"))
    }

    @Test
    fun `long labels are cut to the limit with an ellipsis`() {
        val label = shortAddressLabel("甲".repeat(20), "乙".repeat(20), null)!!
        assertEquals(MAX_ADDRESS_LABEL_LENGTH, label.length)
        assertEquals('…', label.last())
        assertEquals("甲".repeat(MAX_ADDRESS_LABEL_LENGTH), shortAddressLabel("甲".repeat(MAX_ADDRESS_LABEL_LENGTH), null, null))
    }
}

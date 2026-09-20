package com.dsh.console

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun money_underOne_yuan_usesFourDecimals() {
        assertEquals("¥0.0012", Formatters.money(0.0012))
        assertEquals("¥0.9999", Formatters.money(0.9999))
    }

    @Test
    fun money_atLeastOne_yuan_usesTwoDecimals() {
        assertEquals("¥1.00", Formatters.money(1.0))
        assertEquals("¥132.10", Formatters.money(132.1))
        assertEquals("¥1234.50", Formatters.money(1234.5))
    }

    @Test
    fun money_zero_isTwoDecimals_notFour() {
        // 回归：0 元曾经显示成 ¥0.0000
        assertEquals("¥0.00", Formatters.money(0.0))
    }

    @Test
    fun tokens_usesAdaptiveSuffix() {
        assertEquals("0", Formatters.tokens(0))
        assertEquals("999", Formatters.tokens(999))
        assertEquals("1.0K", Formatters.tokens(1_000))
        assertEquals("1.5K", Formatters.tokens(1_500))
        assertEquals("1.00M", Formatters.tokens(1_000_000))
        assertEquals("1.50M", Formatters.tokens(1_500_000))
        assertEquals("2.00B", Formatters.tokens(2_000_000_000))
    }

    @Test
    fun fmtLeft_hoursAndMinutes() {
        assertEquals("0m", Formatters.fmtLeft(0))
        assertEquals("45m", Formatters.fmtLeft(45))
        assertEquals("1h00m", Formatters.fmtLeft(60))
        assertEquals("1h30m", Formatters.fmtLeft(90))
        assertEquals("10h05m", Formatters.fmtLeft(605))
    }
}

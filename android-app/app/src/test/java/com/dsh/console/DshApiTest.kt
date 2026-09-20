package com.dsh.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh-ctl.sh 输出的 JSON 解析回归测试。
 * 这些解析是最容易因为脚本侧改字段而静默失效的地方。
 */
class DshApiTest {

    private val statusJson = """
        {"service":"UP","watchdog":"UP","port":"UP","portCode":"401",
         "dshVersion":"0.1.5-rc.1","install":"IDLE","ctlVersion":"8",
         "url":"http://127.0.0.1:3080/?token=abc","pid":"20463","procs":"1",
         "runtime":"01:58:20","model":"OK","modelName":"deepseek-flash",
         "olState":"UP","olPortCode":"200","olPort":"5244","olUrl":"http://127.0.0.1:5244",
         "olPid":"9760","olRuntime":"00:12:00","olVersion":"v4.2.6","olBoot":"ON","olInstalled":"1",
         "ariaState":"UP","ariaPort":"6800","ariaPid":"1","ariaTasks":"3","ariaSpeed":"12",
         "ariaVersion":"1.37.0"}
    """.trimIndent()

    @Test
    fun parseStatus_readsCoreFields() {
        val st = DshApi.parseStatus(statusJson)
        assertNotNull(st)
        st!!
        assertTrue(st.service)
        assertTrue(st.watchdog)
        assertEquals("401", st.portCode)
        assertEquals("0.1.5-rc.1", st.dshVersion)
        assertEquals("01:58:20", st.runtime)
    }

    @Test
    fun parseStatus_readsOpenListAndAria2() {
        val st = DshApi.parseStatus(statusJson)!!
        assertTrue(st.olService)
        assertEquals("5244", st.olPort)
        assertEquals("v4.2.6", st.olVersion)
        assertTrue(st.olBoot)
        assertTrue(st.ariaState)
        assertEquals("6800", st.ariaPort)
        assertEquals("1.37.0", st.ariaVersion)
    }

    @Test
    fun parseStatus_toleratesLogPrefixBeforeJson() {
        // Termux 输出常带前置行，解析器必须从第一个 '{' 开始
        val st = DshApi.parseStatus("some banner\nwarning: x\n$statusJson\ntrailing")
        assertNotNull(st)
        assertTrue(st!!.service)
    }

    @Test
    fun parseStatus_rejectsNonStatusJson() {
        assertNull(DshApi.parseStatus("""{"foo":1}"""))
        assertNull(DshApi.parseStatus("no json here"))
    }

    @Test
    fun parseCost_rejectsStatusJson() {
        // 状态 JSON 没有 periods/source，不能被误判成费用
        assertNull(DshApi.parseCost(statusJson))
        assertNull(DshApi.parseCost("garbage"))
    }

    @Test
    fun parseCost_readsPeriodsAndBudget() {
        val costJson = """
            {"source":"official","at":"09-20 22:00","currency":"CNY",
             "balance":132.10,"granted":0,"totalCost":498.24,
             "periods":{
               "today":{"cost":11.35,"req":1220,"hit":123,"miss":45,"resp":67},
               "yesterday":{"cost":41.01,"req":1,"hit":0,"miss":0,"resp":0},
               "month":{"cost":228.23,"req":1,"hit":0,"miss":0,"resp":0},
               "d30":{"cost":350.94,"req":1,"hit":0,"miss":0,"resp":0},
               "d7":{"cost":100.0,"req":1,"hit":0,"miss":0,"resp":0}},
             "peak":{"isPeak":false,"weekend":false,"minutesLeft":39,
                     "peakCost":2.0,"offCost":1.0},
             "budget":{"daily":20.0,"month":500.0,"dailyPct":56.75,"monthPct":45.6},
             "daily":{"total":1000.0,"avg":71.4,
                      "days":[{"cost":1.0},{"cost":2.0},{"cost":3.0}]},
             "hourly":[0.0,1.0,2.0],
             "hourlyPeak":{"hour":1,"cost":2.0},
             "models":{"deepseek-flash":10.0},
             "keys":{"k1":5.0}}
        """.trimIndent()
        val c = DshApi.parseCost(costJson)
        assertNotNull(c)
        c!!
        assertEquals("official", c.source)
        assertEquals(132.10, c.balance, 1e-6)
        assertEquals(11.35, c.today.cost, 1e-6)
        assertEquals(1220, c.today.req)
        assertEquals(235L, c.today.tokens)          // hit+miss+resp
        assertEquals(39, c.peakMinutesLeft)
        assertEquals(20.0, c.budgetDaily, 1e-6)
        assertEquals(56.75, c.dailyPct, 1e-6)
        assertEquals(3, c.daily.size)
        assertEquals(3, c.hourly.size)
        assertEquals(1, c.hourlyPeakHour)
        assertEquals(10.0, c.models["deepseek-flash"]!!, 1e-6)
    }

    @Test
    fun parseCost_cacheRate_handlesZeroDenominator() {
        val p = CostPeriod(cost = 1.0, req = 1, hit = 0, miss = 0, resp = 0)
        assertEquals(0.0, p.cacheRate, 1e-6)
        val q = CostPeriod(cost = 1.0, req = 1, hit = 75, miss = 25, resp = 0)
        assertEquals(75.0, q.cacheRate, 1e-6)
    }

    @Test
    fun parseUrl_extractsAndTrims() {
        assertEquals("http://127.0.0.1:3080",
            DshApi.parseUrl("banner\nURL=http://127.0.0.1:3080  \nother"))
        assertNull(DshApi.parseUrl("URL=\n"))
        assertNull(DshApi.parseUrl("no url here"))
    }
}

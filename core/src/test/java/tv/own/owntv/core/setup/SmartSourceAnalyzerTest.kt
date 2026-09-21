package tv.own.owntv.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSourceAnalyzerTest {

    @Test
    fun testUserUrl1() {
        val input = "http://eumaxim.shop:8080/get.php?username=1905mehmetjti&password=31122025memo&type=m3u"
        val res = SmartSourceAnalyzer.analyze(input)
        assertNotNull(res)
        assertTrue(res!!.isXtream)
        assertEquals("http://eumaxim.shop:8080", res.serverUrl)
        assertEquals("1905mehmetjti", res.username)
        assertEquals("31122025memo", res.password)
        assertEquals("m3u", res.outputFormat)
    }

    @Test
    fun testUserUrl2() {
        val input = "http://eumaxim.shop:8080/get.php?username=12361huso061061&password=123061057huso61&output=ts"
        val res = SmartSourceAnalyzer.analyze(input)
        assertNotNull(res)
        assertTrue(res!!.isXtream)
        assertEquals("http://eumaxim.shop:8080", res.serverUrl)
        assertEquals("12361huso061061", res.username)
        assertEquals("123061057huso61", res.password)
        assertEquals("ts", res.outputFormat)
    }

    @Test
    fun testPathBasedUrl() {
        val input = "http://eumaxim.shop:8080/live/user123/pass456/12345.ts"
        val res = SmartSourceAnalyzer.analyze(input)
        assertNotNull(res)
        assertTrue(res!!.isXtream)
        assertEquals("http://eumaxim.shop:8080", res.serverUrl)
        assertEquals("user123", res.username)
        assertEquals("pass456", res.password)
    }

    @Test
    fun testMultilineText() {
        val input = """
            Sunucu: http://vipiptv.net:8000
            Kullanıcı Adı: testuser
            Şifre: secret123
        """.trimIndent()
        val res = SmartSourceAnalyzer.analyze(input)
        assertNotNull(res)
        assertTrue(res!!.isXtream)
        assertEquals("http://vipiptv.net:8000", res.serverUrl)
        assertEquals("testuser", res.username)
        assertEquals("secret123", res.password)
    }
}

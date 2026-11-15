package app.rustmfa.android.enroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnrollLinkTest {
    private val token = "A".repeat(40)

    private fun link(server: String, tok: String = token) =
        "rustmfa://enroll?server=" + java.net.URLEncoder.encode(server, "UTF-8") + "&token=" + tok

    @Test
    fun aGoodLinkParses() {
        val l = assertNotNull(EnrollLink.parse(link("https://rd.example.org"), allowHttp = false))
        assertEquals("https://rd.example.org/", l.server.toString())
        assertEquals(token, l.token)
    }

    @Test
    fun aPortAndAPathPrefixSurvive() {
        val l = assertNotNull(EnrollLink.parse(link("https://rd.example.org:8443/dav"), allowHttp = false))
        assertEquals("https://rd.example.org:8443/dav/", l.server.toString())
    }

    @Test
    fun httpIsRefusedUnlessAllowed() {
        assertNull(EnrollLink.parse(link("http://10.0.2.2:8080"), allowHttp = false))
        assertEquals("http://10.0.2.2:8080/", EnrollLink.parse(link("http://10.0.2.2:8080"), allowHttp = true)?.server.toString())
    }

    @Test
    fun otherSchemesAreRefused() {
        for (s in listOf("javascript:alert(1)", "file:///etc/passwd", "ftp://x.example", "content://x", "intent://x", "data:text/html,x")) {
            assertNull(EnrollLink.parse(link(s), allowHttp = true), s)
        }
    }

    @Test
    fun theLinkSchemeAndHostMustBeRustmfaEnroll() {
        for (t in listOf(
            "https://enroll?server=https%3A%2F%2Fa.example&token=$token",
            "rustmfa://other?server=https%3A%2F%2Fa.example&token=$token",
            "rustmfa:enroll?server=https%3A%2F%2Fa.example&token=$token",
            "rustmfa://enroll/x?server=https%3A%2F%2Fa.example&token=$token",
            "", "garbage",
        )) assertNull(EnrollLink.parse(t, allowHttp = true), t)
    }

    @Test
    fun missingParametersAreRefused() {
        assertNull(EnrollLink.parse("rustmfa://enroll?token=$token", allowHttp = false))
        assertNull(EnrollLink.parse("rustmfa://enroll?server=https%3A%2F%2Fa.example", allowHttp = false))
        assertNull(EnrollLink.parse("rustmfa://enroll?server=&token=$token", allowHttp = false))
        assertNull(EnrollLink.parse("rustmfa://enroll?server=https%3A%2F%2Fa.example&token=", allowHttp = false))
        assertNull(EnrollLink.parse("rustmfa://enroll", allowHttp = false))
    }

    @Test
    fun badTokensAreRefused() {
        for (t in listOf("a%0Ab", "a%00b", "a%20b", "a b", "a%09b", "x".repeat(129), "t%C3%B6ken")) {
            assertNull(EnrollLink.parse(link("https://a.example", t), allowHttp = false), t)
        }
        assertNotNull(EnrollLink.parse(link("https://a.example", "x".repeat(128)), allowHttp = false))
    }

    @Test
    fun credentialsQueryAndFragmentInTheServerAreRefused() {
        for (s in listOf("https://user:pass@a.example", "https://user@a.example", "https://a.example/?x=1", "https://a.example/#f", "https://", "https://a b.example")) {
            assertNull(EnrollLink.parse(link(s), allowHttp = false), s)
        }
    }

    @Test
    fun aHugeInputIsRefusedBeforeParsing() {
        assertNull(EnrollLink.parse(link("https://a.example", "x".repeat(10_000)), allowHttp = false))
        assertNull(EnrollLink.parse("rustmfa://enroll?" + "a=b&".repeat(3_000) + "server=https%3A%2F%2Fa.example&token=$token", allowHttp = false))
    }

    @Test
    fun duplicateParametersAreRefused() {
        assertNull(EnrollLink.parse(link("https://a.example") + "&token=other", allowHttp = false))
        assertNull(EnrollLink.parse(link("https://a.example") + "&server=https%3A%2F%2Fb.example", allowHttp = false))
    }

    @Test
    fun aFragmentOnTheLinkIsRefused() {
        assertNull(EnrollLink.parse(link("https://a.example") + "#x", allowHttp = false))
    }
}

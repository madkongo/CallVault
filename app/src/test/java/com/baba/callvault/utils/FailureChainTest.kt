package com.baba.callvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.regex.PatternSyntaxException

class FailureChainTest {

    @Test
    fun `a single failure reads as its class and message`() {
        assertEquals("IllegalStateException: no adb service", FailureChain.describe(IllegalStateException("no adb service")))
    }

    @Test
    fun `a failure with no message is just its class`() {
        assertEquals("NullPointerException", FailureChain.describe(NullPointerException()))
    }

    @Test
    fun `a static-init failure names the exception that caused it`() {
        val root = PatternSyntaxException("Syntax error in regexp pattern", "(?:}|x)", 3)
        val text = FailureChain.describe(ExceptionInInitializerError(root))

        assertTrue(text, text.startsWith("ExceptionInInitializerError, caused by PatternSyntaxException: Syntax error in regexp pattern"))
        assertTrue("one line: $text", !text.contains('\n'))
    }

    @Test
    fun `a reflective call names what the target threw`() {
        val text = FailureChain.describe(InvocationTargetException(SecurityException("needs MANAGE_DEBUGGING")))
        assertEquals("InvocationTargetException, caused by SecurityException: needs MANAGE_DEBUGGING", text)
    }

    @Test
    fun `a long chain and a long message are capped`() {
        var e: Throwable = RuntimeException("x".repeat(1_000))
        repeat(10) { e = RuntimeException("level $it", e) }
        val text = FailureChain.describe(e)

        assertEquals(FailureChain.MAX_LINKS, text.split(", caused by ").size)
        assertTrue(text.length < 1_000)
    }

    @Test
    fun `a cause that points back at itself does not loop`() {
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertEquals("IllegalStateException: b, caused by RuntimeException: a", FailureChain.describe(b))
    }
}

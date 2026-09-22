package com.ciallo.hyperbackground

import com.ciallo.hyperbackground.util.callMethod
import com.ciallo.hyperbackground.util.findField
import org.junit.Assert.*
import org.junit.Test

class XposedHooksTest {
    private open class Parent {
        private val inherited = 42
        private fun choose(value: Int) = "int:$value"
        private fun choose(value: String) = "string:$value"
        private fun nullable(value: String?) = value ?: "null"
        private fun enable(value: Boolean) = value
    }
    private class Child : Parent()

    @Test fun inheritedPrivateFieldsAreCached() {
        val field = findField(Child::class.java, "inherited")
        assertEquals(42, field.get(Child()))
        assertSame(field, findField(Child::class.java, "inherited"))
    }

    @Test fun overloadsAndNullableArgumentsResolveAcrossTheHierarchy() {
        val child = Child()
        repeat(10) {
            assertEquals("int:7", child.callMethod("choose", 7))
            assertEquals("string:hello", child.callMethod("choose", "hello"))
            assertEquals("null", child.callMethod("nullable", null))
        }
    }

    @Test(expected = NoSuchFieldException::class)
    fun missingFieldsKeepTheirFailureContract() {
        findField(Child::class.java, "missing")
    }

    @Test fun booleanArgumentsMatchPrimitiveParameters() {
        val child = Child()
        assertEquals(true, child.callMethod("enable", true))
        assertEquals(false, child.callMethod("enable", false))
    }

    @Test(expected = IllegalStateException::class)
    fun nullDoesNotMatchAPrimitiveParameter() {
        Child().callMethod("enable", null)
    }
}

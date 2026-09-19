package com.ciallo.hyperbackground

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class HookRuntimeTest {
    private class FakeChain {
        var reads = 0
        var proceeds = 0
        var passed: Array<*>? = null
        val chain = Proxy.newProxyInstance(
            XposedInterface.Chain::class.java.classLoader,
            arrayOf(XposedInterface.Chain::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getThisObject" -> this
                "getArgs" -> { reads++; listOf<Any?>(1, "original") }
                "proceed" -> {
                    proceeds++
                    passed = args?.firstOrNull() as? Array<*>
                    null
                }
                else -> error("Unexpected method: ${method.name}")
            }
        } as XposedInterface.Chain
    }

    @Test fun callbacksWithoutArgumentsDoNotCopyArray() {
        val fake = FakeChain()
        val param = HookRuntime.LegacyHookParam(fake.chain)
        assertSame(fake, param.thisObject)
        assertNull(param.proceed())
        assertEquals(0, fake.reads)
        assertEquals(1, fake.proceeds)
    }

    @Test fun changedArgumentsArePassedOnceEvenWhenOriginalReturnsNull() {
        val fake = FakeChain()
        val param = HookRuntime.LegacyHookParam(fake.chain)
        param.args[0] = 9
        assertSame(param.args, param.args)
        assertNull(param.proceed())
        assertEquals(1, fake.reads)
        assertEquals(1, fake.proceeds)
        assertArrayEquals(arrayOf<Any?>(9, "original"), fake.passed)
    }
}

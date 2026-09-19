package com.ciallo.hyperbackground

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class InvalidatingCacheTest {
    @Test fun repeatedReadsAndSlotsStayIndependent() {
        val cache = InvalidatingCache<String, Int>()
        var reads = 0
        repeat(1_000) { assertEquals(1, cache.getOrLoad("home") { ++reads }) }
        assertEquals(2, cache.getOrLoad("logo") { ++reads })
        assertEquals(2, reads)
        cache.invalidate()
        assertEquals(3, cache.getOrLoad("home") { ++reads })
    }

    @Test fun expiredFailureCanRecover() {
        val cache = InvalidatingCache<String, Int>()
        assertEquals(-1, cache.getOrLoad("home") { -1 })
        assertEquals(5, cache.getOrLoad("home", { it >= 0 }) { 5 })
        assertEquals(5, cache.getOrLoad("home") { error("Unexpected reload") })
    }

    @Test fun inFlightOldReadCannotOverwriteNewGeneration() {
        val cache = InvalidatingCache<String, String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = executor.submit<String> {
                cache.getOrLoad("home") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "old"
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.invalidate()
            assertEquals("new", cache.getOrLoad("home") { "new" })
            release.countDown()
            assertEquals("old", old.get(5, TimeUnit.SECONDS))
            assertEquals("new", cache.getOrLoad("home") { error("Unexpected reload") })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

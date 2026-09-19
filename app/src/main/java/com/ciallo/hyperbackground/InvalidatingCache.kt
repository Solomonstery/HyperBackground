package com.ciallo.hyperbackground

import java.util.concurrent.ConcurrentHashMap

/** A generation swap prevents an in-flight old read from repopulating an invalidated cache. */
internal class InvalidatingCache<K : Any, V : Any> {
    @Volatile private var entries = ConcurrentHashMap<K, V>()

    fun getOrLoad(key: K, isValid: (V) -> Boolean = { true }, load: () -> V): V {
        val generation = entries
        generation[key]?.takeIf(isValid)?.let { return it }
        return load().also { generation[key] = it }
    }

    fun invalidate() {
        entries = ConcurrentHashMap()
    }
}

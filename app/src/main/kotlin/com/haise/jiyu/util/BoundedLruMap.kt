package com.haise.jiyu.util

import java.util.Collections

/**
 * Vláknově bezpečná mapa s omezenou velikostí - při překročení [maxSize] vypadne nejdéle
 * nepoužitá položka. Čistý JDK (ne `android.util.LruCache`, který je v JVM unit testech jen
 * prázdný stub).
 */
fun <K, V> boundedLruMap(maxSize: Int): MutableMap<K, V> = Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    },
)

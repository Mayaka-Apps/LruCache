/*
 * Copyright 2023 MayakaApps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mayakapps.kache

import androidx.collection.mutableScatterMapOf
import com.mayakapps.kache.InMemoryKache.Configuration
import com.mayakapps.kache.collection.MutableChain
import com.mayakapps.kache.collection.MutableChainedScatterMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A typealias that represents a function for calculating the size of a cache entry represented by the provided `key`
 * and `value`.
 *
 * For example, for [String][String], you can use:
 * ```
 * { _, text -> text.length }
 * ```
 *
 * If the entries have the same size or their size can't be determined, you can just return 1.
 */
public typealias SizeCalculator<K, V> = (key: K, value: V) -> Long

/**
 * A typealias that represents a listener that is triggered when a cache entry is removed.
 *
 * This is triggered when the entry represented by the `key` and `oldValue` is removed for any reason. If the removal
 * was a result of reaching the max size of the cache, `evicted` is true, otherwise its value is false. If the entry
 * was removed as a result of replacing it by one of the put operations, the new value is passed as `newValue`,
 * otherwise, `newValue` is null.
 */
public typealias EntryRemovedListener<K, V> = (evicted: Boolean, key: K, oldValue: V, newValue: V?) -> Unit

/**
 * An in-memory coroutine-safe versatile cache that stores objects by keys.
 *
 * It can be built using the following syntax:
 * ```
 * val cache = InMemoryKache<String, String>(maxSize = 100) {
 *     strategy = KacheStrategy.LRU
 *     // ...
 * }
 * ```
 *
 * @see Configuration
 */
public class InMemoryKache<K : Any, V : Any> internal constructor(
    maxSize: Long,
    strategy: KacheStrategy,
    private val creationScope: CoroutineScope,
    private val sizeCalculator: SizeCalculator<K, V>,
    private val onEntryRemoved: EntryRemovedListener<K, V>,
) : ObjectKache<K, V> {

    private val creationMap = mutableScatterMapOf<K, Deferred<V?>>()
    private val creationMutex = Mutex()

    private val map: MutableChainedScatterMap<K, V> = strategy.createMap()
    private val mapMutex = Mutex()

    override var maxSize: Long = maxSize
        private set

    override var size: Long = 0L
        private set

    private val reversed = strategy == KacheStrategy.MRU || strategy == KacheStrategy.FILO

    private val keySet = map.getKeySet(reversed = reversed)

    override suspend fun getKeys(): Set<K> = mapMutex.withLock { keySet.toSet() }

    override suspend fun getUnderCreationKeys(): Set<K> = mapMutex.withLock { creationMap.keySet.toSet() }

    override suspend fun getAllKeys(): KacheKeys<K> =
        mapMutex.withLock { KacheKeys(keySet.toSet(), creationMap.keySet.toSet()) }

    override suspend fun getOrDefault(key: K, defaultValue: V): V =
        getFromCreation(key) ?: getIfAvailableOrDefault(key, defaultValue)

    override suspend fun get(key: K): V? =
        getFromCreation(key) ?: getIfAvailable(key)

    override fun getIfAvailableOrDefault(key: K, defaultValue: V): V =
        getIfAvailable(key) ?: defaultValue

    override fun getIfAvailable(key: K): V? =
        map[key]


    override suspend fun getOrPut(key: K, creationFunction: suspend (key: K) -> V?): V? {
        get(key)?.let { return it }

        creationMutex.withLock {
            if (creationMap[key] == null && map[key] == null) {
                @Suppress("DeferredResultUnused")
                internalPutAsync(key, creationFunction)
            }
        }

        return get(key)
    }

    override suspend fun put(key: K, creationFunction: suspend (key: K) -> V?): V? =
        getFromCreation(key, putAsync(key, creationFunction))

    override suspend fun putAsync(key: K, creationFunction: suspend (key: K) -> V?): Deferred<V?> =
        creationMutex.withLock { internalPutAsync(key, creationFunction) }

    private suspend fun internalPutAsync(
        key: K,
        mappingFunction: suspend (key: K) -> V?,
    ): Deferred<V?> {
        val deferred = creationScope.async {
            val value = try {
                mappingFunction(key)
            } catch (cancellation: CancellationException) {
                null
            }

            if (value != null) {
                // All operations inside the lock to prevent cancellation before trimming or
                // invoking listener
                mapMutex.withLock {
                    val oldValue = map.put(key, value)

                    size += safeSizeOf(key, value) - (oldValue?.let { safeSizeOf(key, it) } ?: 0)
                    nonLockedTrimToSize(maxSize)

                    oldValue?.let { onEntryRemoved(false, key, it, value) }
                }
            }

            value
        }

        deferred.invokeOnCompletion {
            @Suppress("DeferredResultUnused")
            creationMap.remove(key)
        }

        removeCreation(key, CODE_CREATION)
        creationMap[key] = deferred
        return deferred
    }

    override suspend fun put(key: K, value: V): V? {
        val oldValue = mapMutex.withLock {
            val oldValue = map.put(key, value)

            size += safeSizeOf(key, value) - (oldValue?.let { safeSizeOf(key, it) } ?: 0)
            removeCreation(key, CODE_VALUE)

            oldValue
        }

        oldValue?.let { onEntryRemoved(false, key, it, value) }

        trimToSize(maxSize)

        return oldValue
    }

    override suspend fun putAll(from: Map<out K, V>) {
        val removedEntries = mutableMapOf<K, V>()
        mapMutex.withLock {
            for ((key, value) in from) {
                val oldValue = map.put(key, value)

                size += safeSizeOf(key, value) - (oldValue?.let { safeSizeOf(key, it) } ?: 0)
                removeCreation(key, CODE_VALUE)

                if (oldValue != null) removedEntries[key] = oldValue
            }
        }

        for ((key, oldValue) in removedEntries) {
            onEntryRemoved(false, key, oldValue, from[key])
        }

        trimToSize(maxSize)
    }

    override suspend fun remove(key: K): V? {
        removeCreation(key)

        return mapMutex.withLock {
            val oldValue = map.remove(key)
            if (oldValue != null) size -= safeSizeOf(key, oldValue)
            oldValue
        }?.let { oldValue ->
            onEntryRemoved(false, key, oldValue, null)
            oldValue
        }
    }

    override suspend fun clear() {
        removeAllCreations()

        mapMutex.withLock {
            map.removeAllWithCallback(reversed = reversed) { key, value ->
                size -= safeSizeOf(key, value)
                onEntryRemoved(false, key, value, null)
                false // Continue removing
            }

            check(size == 0L) {
                "sizeCalculator is reporting inconsistent results!"
            }
        }
    }

    override suspend fun evictAll() {
        removeAllCreations()

        mapMutex.withLock {
            map.removeAllWithCallback(reversed = reversed) { key, value ->
                size -= safeSizeOf(key, value)
                onEntryRemoved(true, key, value, null)
                false // Continue removing
            }

            check(size == 0L) {
                "sizeCalculator is reporting inconsistent results!"
            }
        }
    }

    override suspend fun removeAllUnderCreation() {
        mapMutex.withLock {
            removeAllCreations()
        }
    }

    override suspend fun resize(maxSize: Long) {
        require(maxSize > 0) { "maxSize <= 0" }
        this.maxSize = maxSize
        trimToSize(maxSize)
    }

    override suspend fun trimToSize(size: Long) {
        mapMutex.withLock {
            nonLockedTrimToSize(size)
        }
    }

    private fun nonLockedTrimToSize(size: Long) {
        if (this@InMemoryKache.size <= size) return

        map.removeAllWithCallback(reversed = reversed) { key, value ->
            this@InMemoryKache.size -= safeSizeOf(key, value)
            onEntryRemoved(true, key, value, null)
            this@InMemoryKache.size <= size
        }

        check(this.size >= 0 || (map.isEmpty() && this.size != 0L)) {
            "sizeCalculator is reporting inconsistent results!"
        }
    }

    private fun safeSizeOf(key: K, value: V): Long {
        val size = sizeCalculator(key, value)
        check(size >= 0) { "Negative size: $key = $value" }
        return size
    }

    private suspend fun getFromCreation(key: K): V? =
        creationMap[key]?.let { deferred -> getFromCreation(key, deferred) }

    private suspend fun getFromCreation(key: K, creation: Deferred<V?>): V? {
        return try {
            creation.await()
        } catch (ex: CancellationException) {
            val cause = ex.cause
            if (cause is DeferredReplacedException) {
                when (cause.replacedWith) {
                    CODE_CREATION -> getFromCreation(key)
                    CODE_VALUE -> getIfAvailable(key)
                    else -> null
                }
            } else null
        }
    }

    private fun removeAllCreations() {
        creationMap.forEachKey { key ->
            removeCreation(key)
        }
    }

    private fun removeCreation(key: K, replacedWith: Int? = null) {
        val deferred = creationMap.remove(key)
        deferred?.cancel(
            message = CANCELLATION_MESSAGE,
            cause = replacedWith?.let { DeferredReplacedException(it) },
        )
    }

    /**
     * Configuration for [InMemoryKache]. It is used as a receiver of [InMemoryKache] builder
     */
    public class Configuration<K, V>(
        /**
         * The max size of this cache. For more information. See [InMemoryKache.maxSize].
         */
        public var maxSize: Long,

        /**
         * The strategy used for evicting elements. See [KacheStrategy]
         */
        public var strategy: KacheStrategy = KacheStrategy.LRU,

        /**
         * The coroutine scope used for executing `creationFunction` of put requests.
         */
        public var creationScope: CoroutineScope = CoroutineScope(Dispatchers.Default),

        /**
         * function used for calculating the size of the elements. See [SizeCalculator]
         */
        public var sizeCalculator: SizeCalculator<K, V> = { _, _ -> 1 },

        /**
         * listener called when an entry is removed for any reason. See [EntryRemovedListener]
         */
        public var onEntryRemoved: EntryRemovedListener<K, V> = { _, _, _, _ -> },
    )
}

/**
 * Creates a new instance of [InMemoryKache] with a configuration that is initialized by [maxSize] and
 * [configuration] lambda.
 *
 * @see InMemoryKache.maxSize
 * @see InMemoryKache.Configuration
 */
public fun <K : Any, V : Any> InMemoryKache(
    maxSize: Long,
    configuration: Configuration<K, V>.() -> Unit = {}
): InMemoryKache<K, V> {
    require(maxSize > 0) { "maxSize must be positive value" }

    val config = Configuration<K, V>(maxSize).apply(configuration)
    return InMemoryKache(
        config.maxSize,
        config.strategy,
        config.creationScope,
        config.sizeCalculator,
        config.onEntryRemoved,
    )
}

private const val CODE_CREATION = 1
private const val CODE_VALUE = 2

private class DeferredReplacedException(val replacedWith: Int) : CancellationException(CANCELLATION_MESSAGE)

private const val CANCELLATION_MESSAGE = "The cached element was removed before creation"

private fun <K : Any, V : Any> KacheStrategy.createMap(): MutableChainedScatterMap<K, V> {
    val accessOrder = this == KacheStrategy.LRU || this == KacheStrategy.MRU

    return MutableChainedScatterMap(
        accessChain = if (accessOrder) MutableChain(0) else null,
        insertionChain = if (accessOrder) null else MutableChain(0),
        accessOrder = accessOrder,
    )
}

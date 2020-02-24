/*
 * Copyright (c) 2020 Andika Wasisto
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.wasisto.camrng

import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.processors.MulticastProcessor

/**
 * This class is the superclass for all camera-based random number generators.
 */
abstract class CamRng {

    protected abstract val booleanProcessor: MulticastProcessor<Boolean>

    private val byteFlowable by lazy { createFlowableForType(Byte::class.java) }

    private val shortFlowable by lazy { createFlowableForType(Short::class.java) }

    private val charFlowable by lazy { createFlowableForType(Char::class.java) }

    private val intFlowable by lazy { createFlowableForType(Int::class.java) }

    private val longFlowable by lazy { createFlowableForType(Long::class.java) }

    private val floatFlowable by lazy { createFlowableForType(Float::class.java) }

    private val doubleFlowable by lazy { createFlowableForType(Double::class.java) }

    /**
     * Returns a `Flowable` that emits random `Boolean` values.
     *
     * @return a `Flowable` that emits random `Boolean` values
     */
    fun getBooleans(): Flowable<Boolean> {
        return booleanProcessor
    }

    /**
     * Returns a `Flowable` that emits random `Byte` values.
     *
     * @return a `Flowable` that emits random `Byte` values
     */
    fun getBytes(): Flowable<Byte> {
        return byteFlowable
    }

    /**
     * Returns a `Flowable` that emits random `Short` values.
     *
     * @return a `Flowable` that emits random `Short` values
     */
    fun getShorts(): Flowable<Short> {
        return shortFlowable
    }

    /**
     * Returns a `Flowable` that emits random `Char` values.
     *
     * @return a `Flowable` that emits random `Char` values
     */
    fun getChars(): Flowable<Char> {
        return charFlowable
    }

    /**
     * Returns a `Flowable` that emits random `Int` values.
     *
     * @return a `Flowable` that emits random `Int` values
     */
    fun getInts(): Flowable<Int> {
        return intFlowable
    }

    /**
     * Returns a `Flowable` that emits random `Long` values.
     *
     * @return a `Flowable` that emits random `Long` values
     */
    fun getLongs(): Flowable<Long> {
        return longFlowable
    }

    /**
     * Returns a `Flowable` that emits random `Float` values.
     *
     * @return a `Flowable` that emits random `Float` values
     */
    fun getFloats(): Flowable<Float> {
        return floatFlowable
    }

    /**
     * Returns a `Flowable` that emits random `Double` values.
     *
     * @return a `Flowable` that emits random `Double` values
     */
    fun getDoubles(): Flowable<Double> {
        return doubleFlowable
    }

    /**
     * Returns a `Single` that emits a random `Boolean` value.
     *
     * @return a `Single` that emits a random `Boolean` value
     */
    fun getBoolean(): Single<Boolean> {
        return Single.fromCallable { getBooleans().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Byte` value.
     *
     * @return a `Single` that emits a random `Byte` value
     */
    fun getByte(): Single<Byte> {
        return Single.fromCallable { getBytes().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Short` value.
     *
     * @return a `Single` that emits a random `Short` value
     */
    fun getShort(): Single<Short> {
        return Single.fromCallable { getShorts().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Char` value.
     *
     * @return a `Single` that emits a random `Char` value
     */
    fun getChar(): Single<Char> {
        return Single.fromCallable { getChars().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Int` value.
     *
     * @return a `Single` that emits a random `Int` value
     */
    fun getInt(): Single<Int> {
        return Single.fromCallable { getInts().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Long` value.
     *
     * @return a `Single` that emits a random `Long` value
     */
    fun getLong(): Single<Long> {
        return Single.fromCallable { getLongs().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Float` value between 0.0 (inclusive) and 1.0 (exclusive).
     *
     * @return a `Single` that emits a random `Float` value
     */
    fun getFloat(): Single<Float> {
        return Single.fromCallable { getFloats().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Double` value between 0.0 (inclusive) and 1.0 (exclusive).
     *
     * @return a `Single` that emits a random `Double` value
     */
    fun getDouble(): Single<Double> {
        return Single.fromCallable { getDoubles().blockingNext().iterator().next() }
    }

    /**
     * Returns a `Single` that emits a random `Int` value between 0 (inclusive) and the specified bound (exclusive).
     *
     * @param bound the upper bound (exclusive). Must be positive.
     *
     * @return a `Single` that emits a random `Int` value between 0 (inclusive) and the specified bound (exclusive)
     */
    fun getInt(bound: Int): Single<Int> {
        require(bound > 0) { "bound must be positive" }

        return Single.fromCallable {
            val intIterator = getInts().blockingNext().iterator()

            if (bound and -bound == bound) {
                return@fromCallable ((bound * intIterator.next().toLong()) shr 31).toInt()
            }

            var bits: Int
            var x: Int
            do {
                bits = intIterator.next().ushr(1)
                x = bits % bound
            } while (bits - x + (bound - 1) < 0)
            return@fromCallable x
        }
    }

    /**
     * Returns a `Flowable` that emits random `Int` values between 0 (inclusive) and the specified bound (exclusive).
     *
     * @param bound the upper bound (exclusive). Must be positive.
     *
     * @return a `Flowable` that emits random `Int` values between 0 (inclusive) and the specified bound (exclusive)
     */
    fun getInts(bound: Int): Flowable<Int> {
        require(bound > 0) { "bound must be positive" }

        if (bound and -bound == bound) {
            return getInts().map { ((bound * it.toLong()) shr 31).toInt() }
        }

        var bits: Int
        var x: Int? = null
        return getInts()
            .filter {
                bits = it.ushr(1)
                x = bits % bound
                bits - x!! + (bound - 1) >= 0
            }
            .map {
                return@map x
            }
    }

    /**
     * Returns a `Single` that emits a random `Long` value between 0 (inclusive) and the specified bound (exclusive).
     *
     * @param bound the upper bound (exclusive). Must be positive.
     *
     * @return a `Single` that emits a random `Long` value between 0 (inclusive) and the specified bound (exclusive)
     */
    fun getLong(bound: Long): Single<Long> {
        require(bound > 0) { "bound must be positive" }

        return Single.fromCallable {
            val longIterator = getLongs().blockingNext().iterator()

            var bits: Long
            var x: Long
            do {
                bits = longIterator.next().ushr(1)
                x = bits % bound
            } while (bits - x + (bound - 1) < 0)
            return@fromCallable x
        }
    }

    /**
     * Returns a `Flowable` that emits random `Long` values between 0 (inclusive) and the specified bound (exclusive).
     *
     * @param bound the upper bound (exclusive). Must be positive.
     *
     * @return a `Flowable` that emits random `Long` values between 0 (inclusive) and the specified bound (exclusive)
     */
    fun getLongs(bound: Long): Flowable<Long> {
        require(bound > 0) { "bound must be positive" }

        var bits: Long
        var x: Long? = null
        return getLongs()
            .filter {
                bits = it.ushr(1)
                x = bits % bound
                bits - x!! + (bound - 1) >= 0
            }
            .map {
                return@map x
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createFlowableForType(type: Class<T>): Flowable<T> {
        val bitCount = when (type) {
            Byte::class.java -> Byte.SIZE_BITS
            Short::class.java -> Short.SIZE_BITS
            Char::class.java -> Char.SIZE_BITS
            Int::class.java -> Int.SIZE_BITS
            Long::class.java -> Long.SIZE_BITS
            Float::class.java -> 24
            Double::class.java -> 53
            else -> throw UnsupportedOperationException()
        }

        return getBooleans()
            .buffer(bitCount)
            .map { booleans ->
                var x = 0L
                booleans.forEach { boolean ->
                    x = (x shl 1) or (if (boolean) 1 else 0)
                }
                when (type) {
                    Byte::class.java -> return@map x.toByte() as T
                    Short::class.java -> return@map x.toShort() as T
                    Char::class.java -> return@map x.toChar() as T
                    Int::class.java -> return@map x.toInt() as T
                    Long::class.java -> return@map x as T
                    Float::class.java -> return@map (x / (1L shl bitCount).toFloat()) as T
                    Double::class.java -> return@map (x / (1L shl bitCount).toDouble()) as T
                    else -> throw UnsupportedOperationException()
                }
            }
    }
}
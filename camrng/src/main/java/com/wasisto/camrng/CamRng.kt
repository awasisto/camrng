/*
 * Copyright (c) 2019 Andika Wasisto
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

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import java.io.Closeable
import java.util.concurrent.CountDownLatch

/**
 * This class is the superclass for all camera-based RNGs.
 */
abstract class CamRng: Closeable {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _liveByte by lazy { createLiveDataForType(Byte::class.java) }

    private val _liveInt by lazy { createLiveDataForType(Int::class.java) }

    private val _liveLong by lazy { createLiveDataForType(Long::class.java) }

    private val _liveFloat by lazy { createLiveDataForType(Float::class.java) }

    private val _liveDouble by lazy { createLiveDataForType(Double::class.java) }

    /**
     * Returns a `LiveData` holding continuously updated random `Byte` value.
     *
     * @return a `LiveData` holding continuously updated random `Byte` value
     */
    abstract fun getLiveBoolean(): LiveData<Boolean>

    /**
     * Returns a `LiveData` holding continuously updated random `Byte` value.
     *
     * @return a `LiveData` holding continuously updated random `Byte` value
     */
    fun getLiveByte() = _liveByte

    /**
     * Returns a `LiveData` holding continuously updated random `Int` value.
     *
     * @return a `LiveData` holding continuously updated random `Int` value
     */
    fun getLiveInt() = _liveInt

    /**
     * Returns a `LiveData` holding continuously updated random `Long` value.
     *
     * @return a `LiveData` holding continuously updated random `Long` value
     */
    fun getLiveLong() = _liveLong

    /**
     * Returns a `LiveData` holding continuously updated random `Float` value.
     *
     * @return a `LiveData` holding continuously updated random `Float` value
     */
    fun getLiveFloat() = _liveFloat

    /**
     * Returns a `LiveData` holding continuously updated random `Double` value.
     *
     * @return a `LiveData` holding continuously updated random `Double` value
     */
    fun getLiveDouble() = _liveDouble

    /**
     * Returns a random `Boolean` value.
     *
     * @return a random `Boolean` value
     */
    fun getBoolean() = getSingleData(getLiveBoolean())

    /**
     * Returns a random `Byte` value.
     *
     * @return a random `Byte` value
     */
    fun getByte() = getSingleData(_liveByte)

    /**
     * Returns a random `Int` value.
     *
     * @return a random `Int` value
     */
    fun getInt() = getSingleData(_liveInt)

    /**
     * Returns a random `Long` value.
     *
     * @return a random `Long` value
     */
    fun getLong() = getSingleData(_liveLong)

    /**
     * Returns a random `Float` value between 0.0 (inclusive) and 1.0 (exclusive).
     *
     * @return a random `Float` value
     */
    fun getFloat() = getSingleData(_liveFloat)

    /**
     * Returns a random `Double` value between 0.0 (inclusive) and 1.0 (exclusive).
     *
     * @return a random `Double` value
     */
    fun getDouble() = getSingleData(_liveDouble)

    /**
     * Generates random bytes and places them into the specified `ByteArray`.
     *
     * @param bytes the `ByteArray` to fill with random bytes
     */
    fun getBytes(bytes: ByteArray) {
        // TODO find a proper way to await a LiveData
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw Exception("Cannot invoke this method on the main thread")
        }

        var i = 0
        val latch = CountDownLatch(1)
        mainHandler.post {
            _liveByte.observeForever(object : Observer<Byte> {
                override fun onChanged(b: Byte) {
                    bytes[i++] = b
                    if (i == bytes.size) {
                        latch.countDown()
                        _liveByte.removeObserver(this)
                    }
                }
            })
        }
        latch.await()
    }

    /**
     * Returns a random `Int` value between 0 (inclusive) and the specified value (exclusive).
     *
     * @param bound the upper bound (exclusive). Must be positive.
     *
     * @return a random `Int` value between zero (inclusive) and [bound] (exclusive)
     */
    fun getInt(bound: Int): Int {
        require(bound > 0)
        if (bound and -bound == bound) {
            return (bound * getInt().ushr(1).toLong() shr 31).toInt()
        }
        var bits: Int
        var x: Int
        do {
            bits = getInt().ushr(1)
            x = bits % bound
        } while (bits - x + (bound - 1) < 0)
        return x
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createLiveDataForType(type: Class<T>): LiveData<T> {
        val bits = when (type) {
            Byte::class.java -> Byte.SIZE_BITS
            Int::class.java -> Int.SIZE_BITS
            Long::class.java -> Long.SIZE_BITS
            Float::class.java -> 24
            Double::class.java -> 53
            else -> throw UnsupportedOperationException()
        }

        val booleans = mutableListOf<Boolean>()

        return MediatorLiveData<T>().apply {
            addSource(getLiveBoolean()) {
                synchronized(this) {
                    booleans.add(it)
                    if (booleans.size == bits) {
                        var x = 0L
                        booleans.forEach { b ->
                            x = (x shl 1) or (if (b) 1 else 0)
                        }
                        when (type) {
                            Byte::class.java, Int::class.java, Long::class.java -> {
                                value = x as T
                            }
                            Float::class.java, Double::class.java -> {
                                value = (x.toDouble() / (1L shl bits)) as T
                            }
                        }
                        booleans.clear()
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getSingleData(liveData: LiveData<T>): T {
        // TODO find a proper way to await a LiveData
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw Exception("Cannot invoke this method on the main thread")
        }

        var data: T? = null
        val latch = CountDownLatch(2)
        mainHandler.post {
            liveData.observeForever(object : Observer<T> {
                override fun onChanged(t: T) {
                    data = t
                    latch.countDown()
                    if (latch.count == 0L) {
                        liveData.removeObserver(this)
                    }
                }
            })
        }
        latch.await()
        return data as T
    }
}
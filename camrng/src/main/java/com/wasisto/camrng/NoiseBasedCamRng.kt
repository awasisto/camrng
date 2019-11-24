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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * This class implements a quantum RNG based on camera shot noise or true RNG based on camera
 * Johnson noise.
 */
@SuppressLint("MissingPermission")
class NoiseBasedCamRng private constructor(context: Context) : CamRng() {

    enum class Channel {
        RED,
        GREEN,
        BLUE
    }

    companion object {

        private const val MAX_PIXELS_TO_USE = 100

        @Volatile
        private var instance: NoiseBasedCamRng? = null

        @Synchronized
        fun getInstance(context: Context): NoiseBasedCamRng {
            return instance ?: NoiseBasedCamRng(context.applicationContext).also {
                instance = it
            }
        }
    }

    var channel = Channel.RED

    var useMultiplePixels = true

    var movingAverageWindowLength = 100

    var vonNeumannUnbias = true

    var onError: ((Throwable) -> Unit)? = null

    private val _liveBoolean = MutableLiveData<Boolean>()

    private var cameraDevice: CameraDevice? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val backgroundHandler =
        Handler(
            HandlerThread("${javaClass.simpleName}Background").also {
                it.start()
            }.looper
        )

    private val movingAverageData = mutableListOf<MutableMap<Pair<Int,Int>, Int>>()

    private var previousBooleanValue: Boolean? = null

    init {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.find {
                cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
            }!!

            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

            val size = cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.JPEG).maxBy { it.width * it.height }!!

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(cameraDevice: CameraDevice) {
                        this@NoiseBasedCamRng.cameraDevice = cameraDevice

                        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                            setOnImageAvailableListener(
                                {
                                    val image = it.acquireNextImage()

                                    val imageWidth = image.width
                                    val imageHeight = image.height

                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)

                                    image.close()

                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                                    val pixels = mutableMapOf<Pair<Int, Int>,Int>()

                                    val centerX = imageWidth / 2
                                    val centerY = imageHeight / 2
                                    pixels[Pair(centerX, centerY)] = bitmap.getPixel(centerX, centerY)

                                    val xStep = (imageWidth - 1) / (sqrt(MAX_PIXELS_TO_USE.toDouble()).toInt() - 1)
                                    val yStep = (imageHeight - 1) / (sqrt(MAX_PIXELS_TO_USE.toDouble()).toInt() - 1)
                                    for (x in 0..imageWidth step xStep) {
                                        for (y in 0..imageHeight step yStep) {
                                            pixels[Pair(x, y)] = bitmap.getPixel(x, y)
                                        }
                                    }

                                    synchronized(this@NoiseBasedCamRng) {
                                        movingAverageData += pixels

                                        if (movingAverageData.size == movingAverageWindowLength) {
                                            if (useMultiplePixels) {
                                                for (x in 0..imageWidth step xStep) {
                                                    for (y in 0..imageHeight step yStep) {
                                                        calculateAndPostValue(x, y)
                                                    }
                                                }
                                            } else {
                                                calculateAndPostValue(centerX, centerY)
                                            }
                                            movingAverageData.removeAt(0)
                                        }
                                    }
                                },
                                backgroundHandler
                            )
                        }

                        cameraDevice.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                    cameraCaptureSession.setRepeatingRequest(
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
                                            addTarget(imageReader.surface)

                                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
                                            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                                            set(CaptureRequest.SENSOR_SENSITIVITY, cameraCharacteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.upper)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

                                            val sensorSize = cameraCharacteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
                                            val maxZoom = cameraCharacteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]
                                            if (sensorSize != null && maxZoom != null) {
                                                val zoomedWidth = sensorSize.width() / maxZoom
                                                val zoomedHeight = sensorSize.height() / maxZoom
                                                val left = (sensorSize.width() - zoomedWidth) / 2
                                                val top = (sensorSize.height() - zoomedHeight) / 2
                                                val right = left + zoomedWidth
                                                val bottom = top + zoomedHeight
                                                set(CaptureRequest.SCALER_CROP_REGION, Rect(floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt()))
                                            }
                                        }.build(),
                                        object : CameraCaptureSession.CaptureCallback() {},
                                        null
                                    )
                                }

                                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                                    cameraDevice.close()
                                    onError?.invoke(Exception("Failed to configure capture session"))
                                }
                            },
                            null
                        )
                    }

                    override fun onDisconnected(cameraDevice: CameraDevice) {
                        cameraDevice.close()
                    }

                    override fun onError(cameraDevice: CameraDevice, error: Int) {
                        cameraDevice.close()
                        onError?.invoke(Exception("Failed to open camera. Error code: $error"))
                    }
                },
                null
            )
        } catch (error: Throwable) {
            onError?.invoke(error)
        }
    }

    private fun calculateAndPostValue(x: Int, y: Int) {
        val pixelValues = mutableListOf<Int>()

        movingAverageData.forEach {
            pixelValues += when (channel) {
                Channel.RED -> it[Pair(x, y)]!! shr 16 and 0xff
                Channel.GREEN -> it[Pair(x, y)]!! shr 8 and 0xff
                Channel.BLUE -> it[Pair(x, y)]!! shr 0 and 0xff
            }
        }

        val pixelLastValue = pixelValues.last()

        pixelValues.sort()

        val median = (pixelValues[pixelValues.size / 2] + pixelValues[(pixelValues.size - 1) / 2]) / 2.0
        val booleanValue = when {
            pixelLastValue > median -> true
            pixelLastValue < median -> false
            else -> return
        }

        if (vonNeumannUnbias) {
            if (previousBooleanValue == null) {
                previousBooleanValue = booleanValue
            } else {
                if (previousBooleanValue != booleanValue) {
                    with(previousBooleanValue) {
                        mainHandler.post {
                            _liveBoolean.value = this
                        }
                    }
                }
                previousBooleanValue = null
            }
        } else {
            with (booleanValue) {
                mainHandler.post {
                    _liveBoolean.value = this
                }
            }
        }
    }

    override fun getLiveBoolean(): LiveData<Boolean> {
        return _liveBoolean
    }

    override fun close() {
        cameraDevice?.close()
    }
}
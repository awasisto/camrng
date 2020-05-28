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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import com.modp.random.BlumBlumShub
import io.reactivex.Flowable
import io.reactivex.processors.MulticastProcessor
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.random.Random

/**
 * This class implements a quantum random number generator (QRNG) based on camera noise.
 */
@SuppressLint("MissingPermission")
class NoiseBasedCamRng private constructor(val pixels: List<Pair<Int, Int>>) : CamRng() {

    enum class WhiteningMethod {
        VON_NEUMANN,
        XOR_WITH_A_CSPRNG,
        NONE
    }

    companion object {

        private const val DATA_WINDOW_SIZE = 100

        private const val MINIMUM_DISTANCE_BETWEEN_PIXELS = 100

        @Volatile
        private var instances = mutableListOf<NoiseBasedCamRng>()

        private var cameraDevice: CameraDevice? = null

        private var imageReader: ImageReader? = null

        private var cameraCaptureSession: CameraCaptureSession? = null

        private var captureRequestBuilder: CaptureRequest.Builder? = null

        private var captureCallback: CameraCaptureSession.CaptureCallback? = null

        private lateinit var cameraCharacteristics: CameraCharacteristics

        private lateinit var imageSize: Size

        private var exposureTimeRange: Range<Long>? = null

        private var isoSensitivityRange: Range<Int>? = null

        private var exposureCompensationRange: Range<Int>? = null

        private var currentExposureTime: Long? = null

        private var currentIsoSensitivity: Int? = null

        private var currentExposureCompensation: Int? = null

        private var aePixelsGreenValuesAverage = 0.0

        private val backgroundHandler =
            Handler(
                HandlerThread("NoiseBasedCamRngBackground").apply {
                    start()
                }.looper
            )

        private val threadPoolExecutor = Executors.newCachedThreadPool()

        private val pixelsGreenValues = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()

        /**
         * Returns a new instance of `NoiseBasedCamRng`.
         *
         * @param context the context for the camera.
         * @param numberOfPixels the number of pixels to use.
         *
         * @return a new instance of `NoiseBasedCamRng`
         */
        @Synchronized
        fun newInstance(context: Context, numberOfPixels: Int): NoiseBasedCamRng {
            if (cameraDevice == null) {
                var exception: Exception? = null

                val latch = CountDownLatch(1)

                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                val cameraId = cameraManager.cameraIdList.find { cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }!!

                cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

                imageSize = cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.JPEG).maxBy { it.width * it.height }!!

                val aePixels = mutableListOf<Pair<Int, Int>>()
                aePixels += Pair(100, 100)
                aePixels += Pair(100 + (imageSize.width - 200) * 1 / 4, 100 + (imageSize.height - 200) * 1 / 4)
                aePixels += Pair(100 + (imageSize.width - 200) * 2 / 4, 100 + (imageSize.height - 200) * 2 / 4)
                aePixels += Pair(100 + (imageSize.width - 200) * 3 / 4, 100 + (imageSize.height - 200) * 3 / 4)
                aePixels += Pair(imageSize.width - 100, imageSize.height - 100)

                isoSensitivityRange = cameraCharacteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
                exposureTimeRange = cameraCharacteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
                if (exposureTimeRange != null) {
                    exposureTimeRange = Range.create(exposureTimeRange!!.lower, min(exposureTimeRange!!.upper, 50000000L))
                } else {
                    exposureCompensationRange = cameraCharacteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
                    if (exposureCompensationRange!!.lower == exposureCompensationRange!!.upper) {
                        exposureCompensationRange = null
                    }
                }

                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(cameraDevice: CameraDevice) {
                            this@Companion.cameraDevice = cameraDevice

                            var lastTimeExposureAdjusted = System.currentTimeMillis()

                            imageReader = ImageReader.newInstance(imageSize.width, imageSize.height, ImageFormat.JPEG, 2).apply {
                                setOnImageAvailableListener(
                                    { imageReader ->
                                        latch.countDown()

                                        synchronized(NoiseBasedCamRng) {
                                            try {
                                                val image = imageReader.acquireNextImage()

                                                val buffer = image.planes[0].buffer
                                                val bytes = ByteArray(buffer.remaining())
                                                buffer.get(bytes)

                                                image.close()

                                                threadPoolExecutor.execute {
                                                    try {
                                                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                                                        synchronized(NoiseBasedCamRng) {
                                                            if (System.currentTimeMillis() - lastTimeExposureAdjusted > 3000) {
                                                                for (pixel in pixelsGreenValues.keys) {
                                                                    val rgb = bitmap.getPixel(pixel.first, pixel.second)
                                                                    pixelsGreenValues[pixel]!! += rgb shr 8 and 0xff
                                                                }

                                                                val aePixelsGreenValues = mutableListOf<Int>()
                                                                for (pixel in aePixels) {
                                                                    aePixelsGreenValues += bitmap.getPixel(pixel.first, pixel.second) shr 8 and 0xff
                                                                }
                                                                aePixelsGreenValuesAverage = aePixelsGreenValues.average()

                                                                bitmap.recycle()

                                                                if (adjustExposureIfNecessary()) {
                                                                    lastTimeExposureAdjusted = System.currentTimeMillis()
                                                                }

                                                                for (i in instances.indices) {
                                                                    instances[i].onDataUpdated()
                                                                }

                                                                if (pixelsGreenValues[pixelsGreenValues.keys.first()]!!.size >= DATA_WINDOW_SIZE) {
                                                                    for (pixel in pixelsGreenValues.keys) {
                                                                        pixelsGreenValues[pixel]!!.removeAt(0)
                                                                    }
                                                                }
                                                            } else {
                                                                bitmap.recycle()
                                                            }
                                                        }
                                                    } catch (t: Throwable) {
                                                        t.printStackTrace()
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                t.printStackTrace()
                                            }
                                        }
                                    },
                                    null
                                )
                            }

                            cameraDevice.createCaptureSession(
                                listOf(imageReader!!.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                        this@Companion.cameraCaptureSession = cameraCaptureSession

                                        val hardwareLevel = cameraCharacteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]

                                        val templateType = if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                                                               hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
                                            CameraDevice.TEMPLATE_MANUAL
                                        } else {
                                            CameraDevice.TEMPLATE_PREVIEW
                                        }

                                        captureRequestBuilder = cameraDevice.createCaptureRequest(templateType).apply {
                                            addTarget(imageReader!!.surface)

                                            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                                            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                                            set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
                                            set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
                                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                            set(CaptureRequest.SENSOR_TEST_PATTERN_MODE, CaptureRequest.SENSOR_TEST_PATTERN_MODE_OFF)
                                            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
                                            set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                                            set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, false)
                                            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF)

                                            if (isoSensitivityRange != null) {
                                                currentIsoSensitivity = isoSensitivityRange!!.upper
                                                set(CaptureRequest.SENSOR_SENSITIVITY, currentIsoSensitivity)
                                            }

                                            if (exposureTimeRange != null) {
                                                currentExposureTime = exposureTimeRange!!.upper
                                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
                                            } else if (exposureCompensationRange != null) {
                                                currentExposureCompensation = exposureCompensationRange!!.upper
                                                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureCompensation)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                        }

                                        captureCallback = object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                super.onCaptureCompleted(session, request, result)
                                                latch.countDown()
                                            }

                                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                super.onCaptureFailed(session, request, failure)
                                                exception = Exception("Capture failed. Failure reason code: ${failure.reason}")
                                                latch.countDown()
                                            }
                                        }

                                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder!!.build(), captureCallback, null)
                                    }

                                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                                        cameraDevice.close()
                                        exception = Exception("Failed to configure capture session")
                                        latch.countDown()
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
                            exception = Exception("Failed to open camera. Error code: $error")
                            latch.countDown()
                        }
                    },
                    backgroundHandler
                )

                latch.await()

                exception?.let {
                    throw it
                }
            }

            val pixels = mutableListOf<Pair<Int, Int>>()

            for (i in 0 until numberOfPixels) {
                var pixel: Pair<Int, Int>? = null

                for (j in 0 until 100) {
                    pixel = Pair(
                        Random.nextInt(1, imageSize.width / MINIMUM_DISTANCE_BETWEEN_PIXELS) * MINIMUM_DISTANCE_BETWEEN_PIXELS,
                        Random.nextInt(1, imageSize.height / MINIMUM_DISTANCE_BETWEEN_PIXELS) * MINIMUM_DISTANCE_BETWEEN_PIXELS
                    )

                    if (pixels.contains(pixel) || pixelsGreenValues.containsKey(pixel)) {
                        pixel = null
                    } else {
                        break
                    }
                }

                if (pixel != null) {
                    pixels.add(pixel)
                } else {
                    throw Exception("Unable to find enough unused pixels")
                }
            }

            for (pixel in pixels) {
                pixelsGreenValues[pixel] = mutableListOf()
            }

            return NoiseBasedCamRng(pixels).also { instance ->
                instances.add(instance)
            }
        }

        /**
         * Resets the generator and frees resources.
         */
        @Synchronized
        fun reset() {
            try {
                cameraDevice?.close()
            } catch (ignored: Throwable) {
            }
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            captureCallback = null
            instances.clear()
            pixelsGreenValues.clear()
        }

        private fun adjustExposureIfNecessary(): Boolean {
            var exposureAdjusted = false
            if (aePixelsGreenValuesAverage < 64) {
                if (isoSensitivityRange != null && currentIsoSensitivity!! < isoSensitivityRange!!.upper) {
                    currentIsoSensitivity = if (currentIsoSensitivity!! * 2 > isoSensitivityRange!!.upper) isoSensitivityRange!!.upper else currentIsoSensitivity!! * 2
                    captureRequestBuilder?.set(CaptureRequest.SENSOR_SENSITIVITY, currentIsoSensitivity)
                    exposureAdjusted = true
                } else if (exposureTimeRange!= null && currentExposureTime!! < exposureTimeRange!!.upper) {
                    currentExposureTime = if (currentExposureTime!! * 2 > exposureTimeRange!!.upper) exposureTimeRange!!.upper else currentExposureTime!! * 2
                    captureRequestBuilder?.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
                    exposureAdjusted = true
                } else if (exposureCompensationRange != null && currentExposureCompensation!! < exposureCompensationRange!!.upper) {
                    currentExposureCompensation = currentExposureCompensation!! + 1
                    captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureCompensation)
                    exposureAdjusted = true
                }
            } else if (aePixelsGreenValuesAverage > 192) {
                if (exposureTimeRange != null && currentExposureTime!! > exposureTimeRange!!.lower) {
                    currentExposureTime = if (currentExposureTime!! / 2 < exposureTimeRange!!.lower) exposureTimeRange!!.lower else currentExposureTime!! / 2
                    captureRequestBuilder?.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
                    exposureAdjusted = true
                } else if (exposureCompensationRange != null && currentExposureCompensation!! > exposureCompensationRange!!.lower) {
                    currentExposureCompensation = currentExposureCompensation!! - 1
                    captureRequestBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposureCompensation)
                    exposureAdjusted = true
                } else if (isoSensitivityRange != null && currentIsoSensitivity!! > isoSensitivityRange!!.lower) {
                    currentIsoSensitivity = if (currentIsoSensitivity!! / 2 < isoSensitivityRange!!.lower) isoSensitivityRange!!.lower else currentIsoSensitivity!! / 2
                    captureRequestBuilder?.set(CaptureRequest.SENSOR_SENSITIVITY, currentIsoSensitivity)
                    exposureAdjusted = true
                }
            }

            if (exposureAdjusted) {
                cameraCaptureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), captureCallback, backgroundHandler)
                for (values in pixelsGreenValues.values) {
                    values.clear()
                }
                return true
            }

            return false
        }
    }

    override val booleanProcessor =
        MulticastProcessor.create<Boolean>().apply {
            start()
        }

    /**
     * The whitening method. Default value is [WhiteningMethod.VON_NEUMANN].
     */
    var whiteningMethod = WhiteningMethod.VON_NEUMANN

    /**
     * An `BehaviorSubject` that emits booleans that indicates whether or not this RNG is
     * calibrating.
     */
    val calibrating = BehaviorSubject.createDefault(true)

    /**
     * A `Flowable` that emits the raw values of the pixels.
     */
    val rawNoise: Flowable<List<Int>> = MulticastProcessor.create<List<Int>>().apply {
        start()
    }

    private var previousPixelBooleanValue: Boolean? = null

    private val csprng = BlumBlumShub(512)

    private fun onDataUpdated() {
        val pixelsValues = mutableListOf<Int>()

        for (pixel in pixels) {
            val pixelValues = pixelsGreenValues[pixel]!!

            if (pixelValues.isNotEmpty()) {
                val sortedPixelValues = pixelValues.sorted()
                val pixelMedian = (sortedPixelValues[sortedPixelValues.size / 2] + sortedPixelValues[(sortedPixelValues.size - 1) / 2]) / 2.0

                pixelsValues += pixelValues.last()

                val pixelBooleanValue = when {
                    pixelValues.last() > pixelMedian -> true
                    pixelValues.last() < pixelMedian -> false
                    else -> null
                }

                if (pixelBooleanValue != null) {
                    when (whiteningMethod) {
                        WhiteningMethod.VON_NEUMANN -> {
                            if (previousPixelBooleanValue == null) {
                                previousPixelBooleanValue = pixelBooleanValue
                            } else {
                                if (previousPixelBooleanValue != pixelBooleanValue) {
                                    booleanProcessor.offer(previousPixelBooleanValue)
                                }
                                previousPixelBooleanValue = null
                            }
                        }
                        WhiteningMethod.XOR_WITH_A_CSPRNG -> {
                            booleanProcessor.offer(pixelBooleanValue xor (csprng.next(1) == 1))
                        }
                        WhiteningMethod.NONE -> {
                            booleanProcessor.offer(pixelBooleanValue)
                        }
                    }
                }
            }

            if (pixelValues.size >= DATA_WINDOW_SIZE && calibrating.value != false) {
                calibrating.onNext(false)
            } else if (pixelValues.size < DATA_WINDOW_SIZE && calibrating.value != true) {
                calibrating.onNext(true)
            }
        }

        if (pixelsValues.isNotEmpty()) {
            (rawNoise as MulticastProcessor).offer(pixelsValues)
        }
    }
}
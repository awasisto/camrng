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
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.modp.random.BlumBlumShub
import io.reactivex.Flowable
import io.reactivex.processors.MulticastProcessor
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.min
import kotlin.random.Random

/**
 * This class implements a quantum random number generator (QRNG) based on camera noise.
 *
 * @property pixels the used pixels (x, y).
 */
@SuppressLint("MissingPermission")
class NoiseBasedCamRng private constructor(val pixels: List<Pair<Int, Int>>) : CamRng() {

    enum class WhiteningMethod {
        VON_NEUMANN,
        INTERPIXEL_XOR,
        XOR_WITH_CSPRNG,
        NONE
    }

    class NotEnoughUnusedPixelsException : Exception()

    companion object {

        /**
         * The minimum distance between pixels to prevent interpixel correlation. Default value
         * is 100.
         */
        var minimumDistanceBetweenPixels = 100
            set(value) {
                require(value > 1) { "minimumDistanceBetweenPixels must be greater than one" }
                field = value
            }

        /**
         * Maximum attempts to find an unused pixel. Default value is 10.
         */
        var maximumPixelFindingAttempts = 10
            set(value) {
                require(value > 0) { "maximumPixelFindingAttempts must be positive" }
                field = value
            }

        /**
         * The direction the camera faces relative to device screen. Default value is
         * [LensFacing.UNSPECIFIED]
         */
        var lensFacing = LensFacing.UNSPECIFIED
            set(value) {
                if (cameraDevice != null) {
                    throw IllegalStateException("NoiseBasedCamRng needs to be reset before setting lensFacing")
                }
                field = value
            }

        /**
         * The camera ID.
         */
        var cameraId: String? = null
            private set

        /**
         * The camera characteristics.
         */
        var cameraCharacteristics: CameraCharacteristics? = null
            private set

        /**
         * The image size.
         */
        var imageSize: Size? = null
            private set

        @Volatile
        private var instances = mutableListOf<NoiseBasedCamRng>()

        private var cameraDevice: CameraDevice? = null

        private var imageReader: ImageReader? = null

        private var cameraCaptureSession: CameraCaptureSession? = null

        private var captureRequestBuilder: CaptureRequest.Builder? = null

        private var captureCallback: CameraCaptureSession.CaptureCallback? = null

        private var rawCaptureSupported: Boolean? = null

        private var colorFilterArrangement: Int? = null

        private var exposureTimeRange: Range<Long>? = null

        private var isoSensitivityRange: Range<Int>? = null

        private var exposureCompensationRange: Range<Int>? = null

        private var currentExposureTime: Long? = null

        private var currentIsoSensitivity: Int? = null

        private var currentExposureCompensation: Int? = null

        private val aePixels = mutableListOf<Pair<Int, Int>>()

        private var lastTimeExposureAdjusted = -1L

        private val backgroundHandler = Handler(
            HandlerThread("NoiseBasedCamRngBackground").apply {
                start()
            }.looper
        )

        private val pixelsValues = mutableMapOf<Pair<Int, Int>, MutableList<Double>>()

        private val surfaceTextures = mutableSetOf<SurfaceTexture>()

        /**
         * Returns a new instance of `NoiseBasedCamRng`. If `numberOfPixels` is less than or equal
         * to zero, the maximum number of pixels will be used.
         *
         * @param context the context for the camera.
         * @param numberOfPixels the number of pixels to use.
         *
         * @return a new instance of `NoiseBasedCamRng`
         */
        @Synchronized
        fun newInstance(context: Context, numberOfPixels: Int = 0): NoiseBasedCamRng {
            if (cameraDevice == null) {
                try {
                    var exception: CameraInitializationFailedException? = null

                    val latch = CountDownLatch(1)

                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                    val filteredCameraIds = cameraManager.cameraIdList.filter {
                        return@filter when (lensFacing) {
                            LensFacing.UNSPECIFIED -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] != null
                            LensFacing.BACK -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == 1
                            LensFacing.FRONT -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == 0
                            LensFacing.EXTERNAL -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == 2
                        }
                    }

                    if (filteredCameraIds.isEmpty()) {
                        val strLensFacing = when (lensFacing) {
                            LensFacing.UNSPECIFIED -> " "
                            LensFacing.BACK -> "back-facing "
                            LensFacing.FRONT -> "front-facing "
                            LensFacing.EXTERNAL -> "external "
                        }
                        throw CameraInitializationFailedException("No ${strLensFacing}camera found")
                    }

                    cameraId = filteredCameraIds.sortedWith(compareBy(
                        {
                            return@compareBy cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                        },
                        {
                            return@compareBy cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                        },
                        {
                            val maxImageSize = cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
                                .getOutputSizes(ImageFormat.JPEG)
                                .maxBy { size -> size.width * size.height }!!
                            return@compareBy maxImageSize.width * maxImageSize.height
                        }
                    )).last()

                    cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId!!)

                    rawCaptureSupported = cameraCharacteristics!![CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

                    colorFilterArrangement = cameraCharacteristics!![CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]

                    val imageFormat = if (rawCaptureSupported!!) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888

                    imageSize = cameraCharacteristics!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(imageFormat).maxBy { it.width * it.height }!!

                    aePixels += Pair(100, 100)
                    aePixels += Pair(100 + (imageSize!!.width - 200) * 1 / 4, 100 + (imageSize!!.height - 200) * 1 / 4)
                    aePixels += Pair(100 + (imageSize!!.width - 200) * 2 / 4, 100 + (imageSize!!.height - 200) * 2 / 4)
                    aePixels += Pair(100 + (imageSize!!.width - 200) * 3 / 4, 100 + (imageSize!!.height - 200) * 3 / 4)
                    aePixels += Pair(imageSize!!.width - 100, imageSize!!.height - 100)

                    isoSensitivityRange = cameraCharacteristics!![CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
                    exposureTimeRange = cameraCharacteristics!![CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
                    if (exposureTimeRange != null) {
                        exposureTimeRange = Range.create(exposureTimeRange!!.lower, min(exposureTimeRange!!.upper, 10000000L))
                    } else {
                        exposureCompensationRange = cameraCharacteristics!![CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
                        if (exposureCompensationRange!!.lower == exposureCompensationRange!!.upper) {
                            exposureCompensationRange = null
                        }
                    }

                    cameraManager.openCamera(
                        cameraId!!,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(cameraDevice: CameraDevice) {
                                try {
                                    this@Companion.cameraDevice = cameraDevice

                                    imageReader = ImageReader.newInstance(imageSize!!.width, imageSize!!.height, imageFormat, 2).apply {
                                        setOnImageAvailableListener(
                                            { imageReader ->
                                                try {
                                                    latch.countDown()

                                                    val image = imageReader.acquireLatestImage()

                                                    synchronized(NoiseBasedCamRng) {
                                                        when (image.format) {
                                                            ImageFormat.RAW_SENSOR -> {
                                                                processRaw(image.planes[0].buffer.asShortBuffer(), image.planes[0].rowStride / 2)
                                                                image.close()
                                                            }
                                                            ImageFormat.YUV_420_888 -> {
                                                                processYuvYPlane(image.planes[0].buffer, image.planes[0].rowStride)
                                                                image.close()
                                                            }
                                                        }
                                                    }
                                                } catch (t: Throwable) {
                                                    t.printStackTrace()
                                                }
                                            },
                                            null
                                        )
                                    }

                                    val surfaces = mutableListOf(imageReader!!.surface)
                                    val surfaceSize = cameraCharacteristics!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(SurfaceTexture::class.java).maxBy { it.width * it.height }!!
                                    for (surfaceTexture in surfaceTextures) {
                                        surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                                        surfaces.add(Surface(surfaceTexture))
                                    }

                                    cameraDevice.createCaptureSession(
                                        surfaces,
                                        object : CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                                try {
                                                    this@Companion.cameraCaptureSession = cameraCaptureSession

                                                    val manualSensorSupported = cameraCharacteristics!![CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                                                    val templateType = if (manualSensorSupported) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_PREVIEW

                                                    captureRequestBuilder = cameraDevice.createCaptureRequest(templateType).apply {
                                                        for (surface in surfaces) {
                                                            addTarget(surface)
                                                        }

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
                                                            latch.countDown()
                                                        }

                                                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                            exception = CameraInitializationFailedException("Capture failed. Failure reason code: ${failure.reason}")
                                                            latch.countDown()
                                                        }
                                                    }

                                                    cameraCaptureSession.setRepeatingRequest(captureRequestBuilder!!.build(), captureCallback, null)
                                                } catch (t: Throwable) {
                                                    exception = CameraInitializationFailedException(t)
                                                    latch.countDown()
                                                }
                                            }

                                            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                                                try {
                                                    cameraDevice.close()
                                                } catch (t: Throwable) {
                                                    t.printStackTrace()
                                                }
                                                exception = CameraInitializationFailedException("Failed to configure capture session")
                                                latch.countDown()
                                            }
                                        },
                                        null
                                    )
                                } catch (t: Throwable) {
                                    exception = CameraInitializationFailedException(t)
                                    latch.countDown()
                                }
                            }

                            override fun onDisconnected(cameraDevice: CameraDevice) {
                                try {
                                    cameraDevice.close()
                                } catch (t: Throwable) {
                                    t.printStackTrace()
                                }
                            }

                            override fun onError(cameraDevice: CameraDevice, error: Int) {
                                try {
                                    cameraDevice.close()
                                } catch (t: Throwable) {
                                    t.printStackTrace()
                                }
                                exception = CameraInitializationFailedException("Failed to open camera. Error code: $error")
                                latch.countDown()
                            }
                        },
                        backgroundHandler
                    )

                    latch.await()

                    exception?.let { throw it }
                } catch (t: Throwable) {
                    throw CameraInitializationFailedException(t)
                }
            }

            val pixels = mutableListOf<Pair<Int, Int>>()

            val minDistance = if (minimumDistanceBetweenPixels == 1 && rawCaptureSupported!!) 2 else minimumDistanceBetweenPixels

            var i = 0
            while (i < numberOfPixels || numberOfPixels <= 0) {
                var pixel: Pair<Int, Int>? = null

                for (j in 0 until maximumPixelFindingAttempts) {
                    pixel = Pair(
                        Random.nextInt(1, (imageSize!!.width - 1) / minDistance) * minDistance,
                        Random.nextInt(1, (imageSize!!.height - 1) / minDistance) * minDistance
                    )

                    if (pixels.contains(pixel) || pixelsValues.containsKey(pixel)) {
                        pixel = null
                    } else {
                        break
                    }
                }

                if (pixel != null) {
                    pixels.add(pixel)
                } else {
                    if (numberOfPixels <= 0) {
                        break
                    } else {
                        throw NotEnoughUnusedPixelsException()
                    }
                }

                i++
            }

            for (pixel in pixels) {
                pixelsValues[pixel] = mutableListOf()
            }

            return NoiseBasedCamRng(pixels).also { instance -> instances.add(instance) }
        }

        /**
         * Adds SurfaceTexture(s)
         */
        fun addSurfaceTextures(vararg surfaceTexture: SurfaceTexture) {
            if (cameraDevice != null) {
                throw IllegalStateException("NoiseBasedCamRng needs to be reset before adding SurfaceTexture(s)")
            }
            surfaceTextures.addAll(surfaceTexture)
        }

        /**
         * Removes SurfaceTexture(s)
         */
        fun removeSurfaceTextures(vararg surfaceTexture: SurfaceTexture) {
            if (cameraDevice != null) {
                throw IllegalStateException("NoiseBasedCamRng needs to be reset before removing SurfaceTexture(s)")
            }
            surfaceTextures.removeAll(surfaceTexture)
        }

        /**
         * Resets the generator and frees resources.
         */
        @Synchronized
        fun reset() {
            try { cameraDevice?.close() } catch (t: Throwable) { t.printStackTrace() }
            try { imageReader?.close() } catch (t: Throwable) { t.printStackTrace() }
            try { cameraCaptureSession?.close() } catch (t: Throwable) { t.printStackTrace() }
            cameraDevice = null
            imageReader = null
            cameraCaptureSession = null
            captureCallback = null
            instances.clear()
            pixelsValues.clear()
            lastTimeExposureAdjusted = -1L
        }

        private fun processRaw(bayerPatternShortBuffer: ShortBuffer, rowStridePx: Int) {
            if (lastTimeExposureAdjusted == -1L) {
                lastTimeExposureAdjusted = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastTimeExposureAdjusted > 3000) {
                for (pixel in pixelsValues.keys) {
                    pixelsValues[pixel]!! += getNearestGreenPixelValue(bayerPatternShortBuffer, rowStridePx, pixel.first, pixel.second) / 1023.0
                }

                val aePixelsValues = mutableListOf<Double>()

                for (pixel in aePixels) {
                    aePixelsValues += getNearestGreenPixelValue(bayerPatternShortBuffer, rowStridePx, pixel.first, pixel.second) / 1023.0
                }

                val exposureAdjusted = adjustExposureIfNecessary(aePixelsValues.average())
                if (exposureAdjusted) {
                    for (values in pixelsValues.values) {
                        values.clear()
                    }
                    lastTimeExposureAdjusted = System.currentTimeMillis()
                }

                for (i in instances.indices) {
                    instances[i].onDataUpdated()
                }

                if (pixelsValues.isNotEmpty() && pixelsValues[pixelsValues.keys.first()]!!.size >= 2) {
                    for (pixelValues in pixelsValues.values) {
                        pixelValues.clear()
                    }
                }
            }
        }

        private fun processYuvYPlane(yPlaneByteBuffer: ByteBuffer, rowStridePx: Int) {
            if (lastTimeExposureAdjusted == -1L) {
                lastTimeExposureAdjusted = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastTimeExposureAdjusted > 3000) {
                for (pixel in pixelsValues.keys) {
                    pixelsValues[pixel]!! += (yPlaneByteBuffer[pixel.second * rowStridePx + pixel.first].toInt() and 0xff) / 255.0
                }

                val aePixelsValues = mutableListOf<Double>()

                for (pixel in aePixels) {
                    aePixelsValues += (yPlaneByteBuffer[pixel.second * rowStridePx + pixel.first].toInt() and 0xff) / 255.0
                }

                val exposureAdjusted = adjustExposureIfNecessary(aePixelsValues.average())
                if (exposureAdjusted) {
                    for (values in pixelsValues.values) {
                        values.clear()
                    }
                    lastTimeExposureAdjusted = System.currentTimeMillis()
                }

                for (i in instances.indices) {
                    instances[i].onDataUpdated()
                }

                if (pixelsValues.isNotEmpty() && pixelsValues[pixelsValues.keys.first()]!!.size >= 2) {
                    for (pixelValues in pixelsValues.values) {
                        pixelValues.clear()
                    }
                }
            }
        }

        private fun adjustExposureIfNecessary(aePixelsValuesAverage: Double): Boolean {
            var exposureAdjusted = false

            if (aePixelsValuesAverage < 0.25) {
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
            } else if (aePixelsValuesAverage >  0.75) {
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
                return true
            }

            return false
        }

        private fun getNearestGreenPixelValue(bayerPatternShortBuffer: ShortBuffer, rowStridePx: Int, x: Int, y: Int): Short {
            when (colorFilterArrangement) {
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
                    return if (y % 2 == 0 && x % 2 == 1 || y % 2 == 1 && x % 2 == 0) {
                        bayerPatternShortBuffer[y * rowStridePx + x]
                    } else {
                        bayerPatternShortBuffer[y * rowStridePx + x + 1]
                    }
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
                    return if (y % 2 == 0 && x % 2 == 0 || y % 2 == 1 && x % 2 == 1) {
                        bayerPatternShortBuffer[y * rowStridePx + x]
                    } else {
                        bayerPatternShortBuffer[y * rowStridePx + x + 1]
                    }
                else -> {
                    return bayerPatternShortBuffer[y * rowStridePx + x]
                }
            }
        }
    }

    override val booleanProcessor = MulticastProcessor.create<Boolean>((1.5 * pixels.size).toInt()).apply { start() }

    /**
     * The whitening method. Default value is [WhiteningMethod.VON_NEUMANN].
     */
    var whiteningMethod = WhiteningMethod.VON_NEUMANN

    /**
     * A `Flowable` that emits the brightnesses of the pixels ordered according to
     * [NoiseBasedCamRng.pixels] with the range of 0.0 to 1.0.
     */
    val rawNoise: Flowable<List<Double>> = MulticastProcessor.create<List<Double>>().apply { start() }

    private val previousPixelsBooleanValues = mutableListOf<Boolean>()

    private val csprng = BlumBlumShub(512)

    private fun onDataUpdated() {
        val values = mutableListOf<Double>()

        if (pixelsValues[pixels.first()]!!.size > 0) {
            for (pixel in pixels) {
                values += pixelsValues[pixel]!!.last()

                if (pixelsValues[pixel]!!.size >= 2) {
                    val pixelValue1 = pixelsValues[pixel]!![0]
                    val pixelValue2 = pixelsValues[pixel]!![1]

                    var pixelBooleanValue = when {
                        pixelValue1 < pixelValue2 -> true
                        pixelValue1 > pixelValue2 -> false
                        else -> null
                    }

                    if (pixelBooleanValue != null) {
                        when (whiteningMethod) {
                            WhiteningMethod.VON_NEUMANN -> {
                                if (previousPixelsBooleanValues.size < 1) {
                                    previousPixelsBooleanValues += pixelBooleanValue
                                } else {
                                    if (previousPixelsBooleanValues[0] != pixelBooleanValue) {
                                        booleanProcessor.offer(previousPixelsBooleanValues[0])
                                    }
                                    previousPixelsBooleanValues.clear()
                                }
                            }
                            WhiteningMethod.INTERPIXEL_XOR -> {
                                if (previousPixelsBooleanValues.size < 2) {
                                    previousPixelsBooleanValues += pixelBooleanValue
                                } else {
                                    for (previousPixelBooleanValue in previousPixelsBooleanValues) {
                                        pixelBooleanValue = pixelBooleanValue!! xor previousPixelBooleanValue
                                    }
                                    booleanProcessor.offer(pixelBooleanValue)
                                    previousPixelsBooleanValues.clear()
                                }
                            }
                            WhiteningMethod.XOR_WITH_CSPRNG -> {
                                booleanProcessor.offer(pixelBooleanValue xor (csprng.next(1) == 1))
                            }
                            WhiteningMethod.NONE -> {
                                booleanProcessor.offer(pixelBooleanValue)
                            }
                        }
                    }
                }
            }

            (rawNoise as MulticastProcessor).offer(values)
        }
    }
}
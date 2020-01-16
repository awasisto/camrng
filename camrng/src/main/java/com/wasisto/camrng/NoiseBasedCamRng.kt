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
import android.util.Size
import com.modp.random.BlumBlumShub
import io.reactivex.processors.MulticastProcessor
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

/**
 * This class implements a quantum RNG based on camera shot noise or true RNG based on camera
 * Johnson noise.
 */
@SuppressLint("MissingPermission")
class NoiseBasedCamRng private constructor(private val pixelsToUse: List<Pair<Int, Int>>) : CamRng() {

    enum class Channel {
        RED,
        GREEN,
        BLUE
    }

    companion object {

        private const val MOVING_AVERAGE_WINDOW_SIZE = 300

        private const val MINIMUM_DISTANCE_BETWEEN_PIXELS = 100

        @Volatile
        private var instances = mutableListOf<NoiseBasedCamRng>()

        private var usedPixels = mutableListOf<Pair<Int, Int>>()

        private var imageSize: Size? = null

        private var cameraDevice: CameraDevice? = null

        private var imageReader: ImageReader? = null

        private val backgroundHandler =
            Handler(
                HandlerThread("NoiseBasedCamRngBackground").apply {
                    start()
                }.looper
            )

        private val movingAverageData = mutableListOf<MutableMap<Pair<Int,Int>, Int>>()

        private val csprng = BlumBlumShub(512)

        /**
         * Returns a new instance of `NoiseBasedCamRng`.
         *
         * @param context the context for the camera.
         * @param numberOfPixelsToUse the number of pixels to use.
         *
         * @return a new instance of `NoiseBasedCamRng`
         */
        @Synchronized
        fun newInstance(context: Context, numberOfPixelsToUse: Int): NoiseBasedCamRng {
            if (cameraDevice == null) {
                var exception: Exception? = null

                val latch = CountDownLatch(1)

                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                val cameraId = cameraManager.cameraIdList.find { cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }!!

                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

                imageSize = cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.JPEG).maxBy { it.width * it.height }!!

                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(cameraDevice: CameraDevice) {
                            this@Companion.cameraDevice = cameraDevice

                            imageReader = ImageReader.newInstance(imageSize!!.width, imageSize!!.height, ImageFormat.JPEG, 1).apply {
                                setOnImageAvailableListener(
                                    { imageReader ->
                                        val image = imageReader.acquireNextImage()

                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)

                                        image.close()

                                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                                        val datum = mutableMapOf<Pair<Int, Int>, Int>()

                                        for (i in usedPixels.indices) {
                                            datum[usedPixels[i]] = bitmap.getPixel(usedPixels[i].first, usedPixels[i].second)
                                        }

                                        movingAverageData += datum

                                        instances.forEach { instance ->
                                            instance.onDatumAdded()
                                        }

                                        if (movingAverageData.size >= MOVING_AVERAGE_WINDOW_SIZE) {
                                            movingAverageData.removeAt(0)
                                        }
                                    },
                                    null
                                )
                            }

                            cameraDevice.createCaptureSession(
                                listOf(imageReader!!.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                        val hardwareLevel = cameraCharacteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]

                                        val templateType = if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                                                               hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
                                            CameraDevice.TEMPLATE_MANUAL
                                        } else {
                                            CameraDevice.TEMPLATE_PREVIEW
                                        }

                                        cameraCaptureSession.setRepeatingRequest(
                                            cameraDevice.createCaptureRequest(templateType).apply {
                                                addTarget(imageReader!!.surface)

                                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                                set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
                                                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                                                set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
                                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

                                                val maxDigitalSensitivity = cameraCharacteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.upper
                                                val maxAnalogSensitivity = cameraCharacteristics[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY]
                                                if (maxDigitalSensitivity != null) {
                                                    set(CaptureRequest.SENSOR_SENSITIVITY, maxDigitalSensitivity)
                                                    if (maxAnalogSensitivity != null && maxAnalogSensitivity > maxDigitalSensitivity) {
                                                        set(CaptureRequest.SENSOR_SENSITIVITY, maxAnalogSensitivity)
                                                    }
                                                }
                                            }.build(),
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                    super.onCaptureCompleted(session, request, result)
                                                    latch.countDown()
                                                }

                                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                                    super.onCaptureFailed(session, request, failure)
                                                    exception = Exception("Capture failed. Failure reason code: ${failure.reason}")
                                                    latch.countDown()
                                                }
                                            },
                                            null
                                        )
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

            val pixelsToUse = mutableListOf<Pair<Int, Int>>()

            for (i in 0 until numberOfPixelsToUse) {
                var pixel: Pair<Int, Int>?

                do {
                    pixel = Pair(
                        Random.nextInt(1, imageSize!!.width / MINIMUM_DISTANCE_BETWEEN_PIXELS) * MINIMUM_DISTANCE_BETWEEN_PIXELS,
                        Random.nextInt(1, imageSize!!.height / MINIMUM_DISTANCE_BETWEEN_PIXELS) * MINIMUM_DISTANCE_BETWEEN_PIXELS
                    )
                } while (usedPixels.contains(pixel))

                pixelsToUse.add(pixel!!)
                usedPixels.add(pixel)
            }

            return NoiseBasedCamRng(pixelsToUse).also { instance ->
                instances.add(instance)
            }
        }

        /**
         * Resets RNG and frees resources.
         */
        @Synchronized
        fun reset() {
            cameraDevice?.close()
            cameraDevice = null
            instances.clear()
            usedPixels.clear()
            movingAverageData.clear()
        }

        /**
         * Returns true if the RNG is warmed up.
         *
         * @return true if the RNG is warmed up.
         */
        fun isWarmedUp(): Boolean {
            return movingAverageData.size >= MOVING_AVERAGE_WINDOW_SIZE - 1
        }
    }

    var channel = Channel.GREEN

    override val booleanProcessor =
        MulticastProcessor.create<Boolean>().apply {
            start()
        }

    private fun onDatumAdded() {
        for (i in pixelsToUse.indices) {
            val pixel = pixelsToUse[i]

            val pixelValues = mutableListOf<Int>()

            movingAverageData.forEach { datum ->
                datum[Pair(pixel.first, pixel.second)]?.let { pixelValue ->
                    pixelValues += when (channel) {
                        Channel.RED -> pixelValue shr 16 and 0xff
                        Channel.GREEN -> pixelValue shr 8 and 0xff
                        Channel.BLUE -> pixelValue shr 0 and 0xff
                    }
                }
            }

            if (pixelValues.size != 0) {
                val pixelLastValue = pixelValues.last()

                pixelValues.sort()

                val pixelMedian = (pixelValues[pixelValues.size / 2] + pixelValues[(pixelValues.size - 1) / 2]) / 2.0

                val booleanValue = when {
                    pixelLastValue > pixelMedian -> true
                    pixelLastValue < pixelMedian -> false
                    else -> null
                }

                if (booleanValue != null) {
                    val unbiasedBooleanValue = booleanValue xor (csprng.next(1) == 1)
                    booleanProcessor.offer(unbiasedBooleanValue)
                }
            }
        }
    }
}
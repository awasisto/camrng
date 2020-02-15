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
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import io.reactivex.processors.MulticastProcessor
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/**
 * This class implements a simple true random number generator (TRNG) by hashing camera images.
 */
@SuppressLint("MissingPermission")
class ImageBasedCamRng private constructor(context: Context) : CamRng() {

    companion object {

        @Volatile
        private var instance: ImageBasedCamRng? = null

        /**
         * Returns a new instance of `ImageBasedCamRng`.
         *
         * @param context the context for the camera.
         *
         * @return a new instance of `ImageBasedCamRng`
         */
        @Synchronized
        fun getInstance(context: Context): ImageBasedCamRng {
            return instance ?: ImageBasedCamRng(context.applicationContext).also {
                instance = it
            }
        }

        /**
         * Resets the generator and frees resources.
         */
        @Synchronized
        fun reset() {
            instance?.cameraDevice?.close()
            instance = null
        }
    }

    override val booleanProcessor =
        MulticastProcessor.create<Boolean>().apply {
            start()
        }

    private lateinit var cameraDevice: CameraDevice

    private lateinit var imageReader: ImageReader

    private val backgroundHandler =
        Handler(
            HandlerThread("ImageBasedCamRngBackground").apply {
                start()
            }.looper
        )

    private val messageDigest = MessageDigest.getInstance("SHA-256")

    init {
        var exception: Exception? = null

        val latch = CountDownLatch(1)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = cameraManager.cameraIdList.find {
            cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
        }!!

        val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

        val imageSize = cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.JPEG).maxBy { it.width * it.height }!!

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(cameraDevice: CameraDevice) {
                    this@ImageBasedCamRng.cameraDevice = cameraDevice

                    imageReader = ImageReader.newInstance(imageSize.width, imageSize.height, ImageFormat.JPEG, 1).apply {
                        setOnImageAvailableListener(
                            { imageReader ->
                                val image = imageReader.acquireNextImage()

                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                image.close()

                                messageDigest.digest(bytes).forEach { byte ->
                                    var mask = 128
                                    while (mask != 0) {
                                        booleanProcessor.offer(byte.toInt() and mask != 0)
                                        mask = mask shr 1
                                    }
                                }
                            },
                            null
                        )
                    }

                    cameraDevice.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                cameraCaptureSession.setRepeatingRequest(
                                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(imageReader.surface)
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
}
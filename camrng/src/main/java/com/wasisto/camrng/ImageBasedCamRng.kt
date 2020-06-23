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
import android.util.Size
import android.view.Surface
import io.reactivex.processors.MulticastProcessor
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/**
 * This class implements a simple true random number generator by hashing camera images.
 */
@SuppressLint("MissingPermission")
class ImageBasedCamRng private constructor(context: Context) : CamRng() {

    companion object {

        @Volatile
        private var instance: ImageBasedCamRng? = null

        private val surfaceTextures = mutableSetOf<SurfaceTexture>()

        /**
         * The direction the camera faces relative to device screen. Default value is
         * [LensFacing.BACK]
         */
        var lensFacing = LensFacing.BACK
            set(value) {
                if (instance != null) {
                    throw IllegalStateException("ImageBasedCamRng needs to be reset before setting lensFacing")
                }
                field = value
            }

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
         * Adds SurfaceTexture(s)
         */
        fun addSurfaceTextures(vararg surfaceTexture: SurfaceTexture) {
            if (instance != null) {
                throw IllegalStateException("ImageBasedCamRng needs to be reset before adding SurfaceTexture(s)")
            }
            surfaceTextures.addAll(surfaceTexture)
        }

        /**
         * Removes SurfaceTexture(s)
         */
        fun removeSurfaceTextures(vararg surfaceTexture: SurfaceTexture) {
            if (instance != null) {
                throw IllegalStateException("ImageBasedCamRng needs to be reset before removing SurfaceTexture(s)")
            }
            surfaceTextures.removeAll(surfaceTexture)
        }

        /**
         * Resets the generator and frees resources.
         */
        @Synchronized
        fun reset() {
            try { instance?.cameraDevice?.close() } catch (ignored: Throwable) { }
            try { instance?.imageReader?.close() } catch (ignored: Throwable) { }
            try { instance?.cameraCaptureSession?.close() } catch (ignored: Throwable) { }
            instance?.cameraDevice = null
            instance?.imageReader = null
            instance?.cameraCaptureSession = null
            instance?.captureCallback = null
            instance = null
        }
    }

    override val booleanProcessor = MulticastProcessor.create<Boolean>().apply {
        start()
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

    private var cameraDevice: CameraDevice? = null

    private var imageReader: ImageReader? = null

    private var cameraCaptureSession: CameraCaptureSession? = null

    private var captureCallback: CameraCaptureSession.CaptureCallback? = null

    private val backgroundHandler = Handler(
        HandlerThread("ImageBasedCamRngBackground").apply {
            start()
        }.looper
    )

    private val messageDigest = MessageDigest.getInstance("SHA-512")

    init {
        var exception: CameraInitializationFailedException? = null

        val latch = CountDownLatch(1)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val filteredCameraIds = cameraManager.cameraIdList.filter {
            return@filter when (NoiseBasedCamRng.lensFacing) {
                LensFacing.UNSPECIFIED -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] != null
                LensFacing.BACK -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == 1
                LensFacing.FRONT -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == 0
                LensFacing.EXTERNAL -> cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == 2
            }
        }

        if (filteredCameraIds.isEmpty()) {
            val strLensFacing = when (NoiseBasedCamRng.lensFacing) {
                LensFacing.UNSPECIFIED -> " "
                LensFacing.BACK -> "back-facing"
                LensFacing.FRONT -> "front-facing"
                LensFacing.EXTERNAL -> "external"
            }
            throw CameraInitializationFailedException("No $strLensFacing camera found")
        }

        cameraId = filteredCameraIds.sortedWith(compareBy {
            val maxResolution = cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
                .getOutputSizes(ImageFormat.JPEG)
                .maxBy { resolution -> resolution.width * resolution.height }!!
            return@compareBy maxResolution.width * maxResolution.height
        }).last()

        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId!!)

        imageSize = cameraCharacteristics!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.JPEG).minBy { it.width * it.height }!!

        cameraManager.openCamera(
            cameraId!!,
            object : CameraDevice.StateCallback() {
                override fun onOpened(cameraDevice: CameraDevice) {
                    this@ImageBasedCamRng.cameraDevice = cameraDevice

                    var startEmittingAt = -1L

                    imageReader = ImageReader.newInstance(imageSize!!.width, imageSize!!.height, ImageFormat.JPEG, 2).apply {
                        setOnImageAvailableListener(
                            { imageReader ->
                                latch.countDown()

                                synchronized(ImageBasedCamRng) {
                                    try {
                                        val image = imageReader.acquireLatestImage()

                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)

                                        image.close()

                                        if (startEmittingAt == -1L) {
                                            startEmittingAt = System.currentTimeMillis() + 3000
                                        } else if (System.currentTimeMillis() > startEmittingAt) {
                                            for (byte in messageDigest.digest(bytes)) {
                                                var mask = 0b10000000
                                                while (mask != 0) {
                                                    booleanProcessor.offer(byte.toInt() and mask != 0)
                                                    mask = mask shr 1
                                                }
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

                    val surfaces = mutableListOf(imageReader!!.surface)
                    val surfaceSize = cameraCharacteristics!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(SurfaceTexture::class.java).minBy { it.width * it.height }!!
                    for (surfaceTexture in surfaceTextures) {
                        surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                        surfaces.add(Surface(surfaceTexture))
                    }

                    cameraDevice.createCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                this@ImageBasedCamRng.cameraCaptureSession = cameraCaptureSession

                                captureCallback = object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        latch.countDown()
                                    }

                                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                        exception = CameraInitializationFailedException("Capture failed. Failure reason code: ${failure.reason}")
                                        latch.countDown()
                                    }
                                }

                                cameraCaptureSession.setRepeatingRequest(
                                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        for (surface in surfaces) {
                                            addTarget(surface)
                                        }
                                    }.build(),
                                    captureCallback,
                                    null
                                )
                            }

                            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                                cameraDevice.close()
                                exception = CameraInitializationFailedException("Failed to configure capture session")
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
                    exception = CameraInitializationFailedException("Failed to open camera. Error code: $error")
                    latch.countDown()
                }
            },
            backgroundHandler
        )

        latch.await()

        exception?.let { throw it }
    }
}
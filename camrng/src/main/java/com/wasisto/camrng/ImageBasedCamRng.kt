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
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.security.MessageDigest

/**
 * This class implements a true RNG based on hashed images from device camera.
 */
@SuppressLint("MissingPermission")
class ImageBasedCamRng private constructor(context: Context) : CamRng() {

    companion object {

        @Volatile
        private var instance: ImageBasedCamRng? = null

        @Synchronized
        fun getInstance(context: Context): ImageBasedCamRng {
            return instance ?: ImageBasedCamRng(context.applicationContext).also {
                instance = it
            }
        }
    }

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

    private var messageDigest: MessageDigest? = null

    init {
        try {
            messageDigest = MessageDigest.getInstance("SHA-256")

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
                        this@ImageBasedCamRng.cameraDevice = cameraDevice

                        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                            setOnImageAvailableListener(
                                {
                                    val image = it.acquireNextImage()

                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)

                                    image.close()

                                    messageDigest!!.digest(bytes).forEach { b ->
                                        var mask = 128
                                        while (mask != 0) {
                                            with (b.toInt() and mask != 0) {
                                                mainHandler.post {
                                                    _liveBoolean.value = this
                                                }
                                            }
                                            mask = mask shr 1
                                        }
                                    }
                                },
                                backgroundHandler)
                        }

                        cameraDevice.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                    cameraCaptureSession.setRepeatingRequest(
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(imageReader.surface)
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

    override fun getLiveBoolean(): LiveData<Boolean> {
        return _liveBoolean
    }

    override fun close() {
        cameraDevice?.close()
    }
}
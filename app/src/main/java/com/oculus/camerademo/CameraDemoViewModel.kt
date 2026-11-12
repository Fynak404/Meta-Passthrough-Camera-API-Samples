/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oculus.camerademo

import android.app.Application
import android.content.Context.CAMERA_SERVICE
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

data class StereoFramePair(
    val leftY: ByteArray,
    val rightY: ByteArray,
    val width: Int,
    val height: Int,
    val baselineCm: Float,
    val focalLengthPx: Float,
)

class XrCameraDemoViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    const val IMAGE_BUFFER_SIZE = 3
  }

  private val permissionManager = PermissionManager()

  private val cameraManager: CameraManager by
      lazy(LazyThreadSafetyMode.NONE) {
        application.applicationContext.getSystemService(CAMERA_SERVICE) as CameraManager
      }

  private val cameraConfigs = ArrayList<CameraConfig>()
  private var rightConfig: CameraConfig? = null
  private var leftConfig: CameraConfig? = null

  private var _uiState = MutableLiveData(CameraUiState())
  val uiState: LiveData<CameraUiState> = _uiState

  private var _permissionRequestState = MutableLiveData(
      PermissionRequestState(
          nativeCameraPermissionGranted =
              permissionManager.checkPermissions(
                  application,
                  PermissionManager.ANDROID_CAMERA_PERMISSION,
              ),
          vendorCameraPermissionGranted =
              permissionManager.checkPermissions(
                  application,
                  PermissionManager.HZOS_CAMERA_PERMISSION,
              ),
      ),
  )
  val permissionRequestState: LiveData<PermissionRequestState> = _permissionRequestState

  private var rightImageReader: ImageReader? = null
  private var leftImageReader: ImageReader? = null
  private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
  private val imageReaderHandler = Handler(imageReaderThread.looper)

  private var rightCamera: CameraDevice? = null
  private var leftCamera: CameraDevice? = null
  private var rightSession: CameraCaptureSession? = null
  private var leftSession: CameraCaptureSession? = null

  private val cameraThread = HandlerThread("cameraThread").apply { start() }
  private val cameraHandler = Handler(cameraThread.looper)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val cameraSessionExecutor = Executors.newCachedThreadPool()

  private val _eventLiveData = MutableLiveData<CameraEvent>(CameraEvent.Empty)
  val cameraEvents: LiveData<CameraEvent> = _eventLiveData

  // Latest synchronized Y-plane buffers for stereo depth
  private val stereoLock = Any()
  private var latestLeftY: ByteArray? = null
  private var latestRightY: ByteArray? = null
  private var stereoWidth = 0
  private var stereoHeight = 0
  private var stereoBaselineCm = 6.4f
  private var stereoFocalPx = 720f

  val isCameraActive: Boolean
    get() = rightConfig != null

  var cameraWasActive: Boolean = false

  fun init() {
    cameraConfigs.clear()
    logv("Init")
    cameraManager.cameraIdList.forEach { cameraId ->
      val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
      val pixelSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
      val position = Position.fromInt(cameraCharacteristics.get(KEY_POSITION))
      val lensRotation = cameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION)
      val lensTranslation = cameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
      val cameraSource = cameraCharacteristics.get(KEY_SOURCE)

      cameraConfigs.add(
          CameraConfig(
              id = cameraId,
              width = pixelSize?.width ?: 0,
              height = pixelSize?.height ?: 0,
              lensRotation = lensRotation ?: floatArrayOf(),
              lensTranslation = lensTranslation ?: floatArrayOf(),
              position = position,
              isPassthrough = cameraSource == CAMERA_SOURCE_PASSTHROUGH,
          ),
      )
    }

    // Calculate real physical stereo baseline (in cm) between Left and Right Quest 3 cameras
    val lCfg = cameraConfigs.find { it.position == Position.Left }
    val rCfg = cameraConfigs.find { it.position == Position.Right }
    if (lCfg != null && rCfg != null && lCfg.lensTranslation.size >= 3 && rCfg.lensTranslation.size >= 3) {
      val dx = rCfg.lensTranslation[0] - lCfg.lensTranslation[0]
      val dy = rCfg.lensTranslation[1] - lCfg.lensTranslation[1]
      val dz = rCfg.lensTranslation[2] - lCfg.lensTranslation[2]
      val distMeters = sqrt(dx * dx + dy * dy + dz * dz)
      if (distMeters > 0.02f) {
        stereoBaselineCm = distMeters * 100f
      }
    }

    // Read real camera focal length in pixels if available
    if (rCfg != null && rCfg.width > 0) {
      try {
        val chars = cameraManager.getCameraCharacteristics(rCfg.id)
        val intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (intrinsics != null && intrinsics.isNotEmpty() && intrinsics[0] > 50f) {
          stereoFocalPx = intrinsics[0]
        } else {
          stereoFocalPx = rCfg.width * 0.44f
        }
      } catch (_: Exception) {
        stereoFocalPx = rCfg.width * 0.44f
      }
    }

    logConfigs(cameraConfigs)
  }

  fun getLatestStereoPair(): StereoFramePair? {
    synchronized(stereoLock) {
      val l = latestLeftY ?: return null
      val r = latestRightY ?: return null
      if (stereoWidth <= 0 || stereoHeight <= 0) return null
      return StereoFramePair(
          leftY = l.copyOf(),
          rightY = r.copyOf(),
          width = stereoWidth,
          height = stereoHeight,
          baselineCm = stereoBaselineCm,
          focalLengthPx = stereoFocalPx,
      )
    }
  }

  fun startCamera(previewSurface: Surface) {
    if (rightConfig == null) {
      openDualStereoCameras(previewSurface)
    }
  }

  fun stopCamera() {
    rightConfig = null
    leftConfig = null

    try { rightSession?.stopRepeating() } catch (_: Exception) {}
    try { leftSession?.stopRepeating() } catch (_: Exception) {}
    rightSession = null
    leftSession = null

    try { rightCamera?.close() } catch (_: Exception) {}
    try { leftCamera?.close() } catch (_: Exception) {}
    rightCamera = null
    leftCamera = null

    try { rightImageReader?.close() } catch (_: Exception) {}
    try { leftImageReader?.close() } catch (_: Exception) {}
    rightImageReader = null
    leftImageReader = null
  }

  fun shutdown() {
    stopCamera()
  }

  fun onResume(surface: Surface) {
    if (cameraWasActive) {
      startCamera(surface)
      cameraWasActive = false
    }
  }

  fun onPause() {
    cameraWasActive = isCameraActive
    stopCamera()
  }

  fun resetState() {
    stopCamera()
  }

  fun onHandleCameraEvent() {
    _eventLiveData.value = CameraEvent.Empty
  }

  fun onPermissionGranted(requestResult: Map<String, Boolean>) {
    val androidPermissionGranted =
        requestResult.getOrDefault(
            PermissionManager.ANDROID_CAMERA_PERMISSION,
            _permissionRequestState.value?.nativeCameraPermissionGranted ?: false,
        )
    val vendorPermissionGranted =
        requestResult.getOrDefault(
            PermissionManager.HZOS_CAMERA_PERMISSION,
            _permissionRequestState.value?.vendorCameraPermissionGranted ?: false,
        )

    val androidPermissionStateChanged =
        (androidPermissionGranted != _permissionRequestState.value?.nativeCameraPermissionGranted)
    val vendorPermissionStateChanged =
        (vendorPermissionGranted != permissionRequestState.value?.vendorCameraPermissionGranted)

    if (androidPermissionStateChanged || vendorPermissionStateChanged) {
      _permissionRequestState.value =
          _permissionRequestState.value?.copy(
              nativeCameraPermissionGranted = androidPermissionGranted,
              vendorCameraPermissionGranted = vendorPermissionGranted,
          )
    }
  }

  private fun extractYPlane(image: Image, width: Int, height: Int): ByteArray {
    val yPlane = image.planes[0]
    val buffer = yPlane.buffer
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val out = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
      buffer.rewind()
      buffer.get(out, 0, out.size)
    } else {
      val rowBytes = ByteArray(rowStride)
      buffer.rewind()
      for (y in 0 until height) {
        val remaining = buffer.remaining()
        if (remaining <= 0) break
        val toRead = minOf(rowStride, remaining)
        buffer.get(rowBytes, 0, toRead)
        val rowOffset = y * width
        for (x in 0 until width) {
          val srcIdx = x * pixelStride
          if (srcIdx < toRead) {
            out[rowOffset + x] = rowBytes[srcIdx]
          }
        }
      }
    }
    return out
  }

  private fun getBrightness(buffer: ByteBuffer, width: Int, height: Int): Int {
    return 128 // Simple placeholder for brightness calculation if needed
  }

  private fun openDualStereoCameras(previewSurface: Surface) {
    val permissionRequestState = _permissionRequestState.value ?: return
    if (!hasNecessaryPermissions(permissionRequestState)) {
      postEvent("Missing required permissions")
      return
    }
    if (cameraConfigs.isEmpty()) {
      logv("No Camera Configs found. Unable to open cameras.")
      return
    }
    val rCfg = cameraConfigs.find { it.position == Position.Right }
    val lCfg = cameraConfigs.find { it.position == Position.Left }
    if (rCfg == null) {
      loge("Right Camera not found.")
      return
    }
    rightConfig = rCfg
    leftConfig = lCfg
    stereoWidth = rCfg.width
    stereoHeight = rCfg.height

    // 1. Setup Right Camera (Preview + Stereo Right Eye)
    rightImageReader =
        ImageReader.newInstance(
            rCfg.width,
            rCfg.height,
            ImageFormat.YUV_420_888,
            IMAGE_BUFFER_SIZE,
        ).apply {
          onLatestImage(imageReaderHandler) { image ->
            val yBytes = extractYPlane(image, rCfg.width, rCfg.height)
            synchronized(stereoLock) {
              latestRightY = yBytes
            }
            val brightness = getBrightness(image.planes[0].buffer, rCfg.width, rCfg.height)
            mainHandler.post {
              _uiState.value = _uiState.value?.copy(cameraBrightness = brightness.toFloat())
            }
          }
        }

    cameraManager.openCamera(
        rCfg.id,
        object : CameraDevice.StateCallback() {
          override fun onOpened(camera: CameraDevice) {
            rightCamera = camera
            val readerSurface = rightImageReader?.surface ?: return
            try {
              camera.createCaptureSession(
                  SessionConfiguration(
                      SessionConfiguration.SESSION_REGULAR,
                      mutableListOf(
                          OutputConfiguration(readerSurface),
                          OutputConfiguration(previewSurface),
                      ),
                      cameraSessionExecutor,
                      object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                          rightSession = session
                          val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(readerSurface)
                            addTarget(previewSurface)
                          }
                          session.setRepeatingRequest(req.build(), null, cameraHandler)
                          postEvent("Dual 3D Stereo Vision Active!")
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                          loge("Failed to configure Right camera session")
                        }
                      },
                  ),
              )
            } catch (err: Exception) {
              loge(err.message)
            }
          }
          override fun onDisconnected(camera: CameraDevice) {
            resetState()
          }
          override fun onClosed(camera: CameraDevice) {
            postEvent("Camera stopped")
            super.onClosed(camera)
          }
          override fun onError(camera: CameraDevice, error: Int) {
            loge("Right camera error: $error")
          }
        },
        cameraHandler,
    )

    // 2. Setup Left Camera (Stereo Left Eye for True 3D Depth Triangulation)
    if (lCfg != null && lCfg.width == rCfg.width && lCfg.height == rCfg.height) {
      leftImageReader =
          ImageReader.newInstance(
              lCfg.width,
              lCfg.height,
              ImageFormat.YUV_420_888,
              IMAGE_BUFFER_SIZE,
          ).apply {
            onLatestImage(imageReaderHandler) { image ->
              val yBytes = extractYPlane(image, lCfg.width, lCfg.height)
              synchronized(stereoLock) {
                latestLeftY = yBytes
              }
            }
          }

      cameraManager.openCamera(
          lCfg.id,
          object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
              leftCamera = camera
              val readerSurface = leftImageReader?.surface ?: return
              try {
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        mutableListOf(OutputConfiguration(readerSurface)),
                        cameraSessionExecutor,
                        object : CameraCaptureSession.StateCallback() {
                          override fun onConfigured(session: CameraCaptureSession) {
                            leftSession = session
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                              addTarget(readerSurface)
                            }
                            session.setRepeatingRequest(req.build(), null, cameraHandler)
                          }
                          override fun onConfigureFailed(session: CameraCaptureSession) {
                            loge("Failed to configure Left stereo camera session")
                          }
                        },
                    ),
                )
              } catch (err: Exception) {
                loge(err.message)
              }
            }
            override fun onDisconnected(camera: CameraDevice) {
              leftCamera = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
              loge("Left camera error: $error")
            }
          },
          cameraHandler,
      )
    }
  }

  private fun hasNecessaryPermissions(state: PermissionRequestState) =
      state.nativeCameraPermissionGranted && state.vendorCameraPermissionGranted

  private fun arrayToString(arr: FloatArray?): String = arr?.joinToString(",") ?: "[]"

  private fun logConfigs(configs: List<CameraConfig>) {
    for (config in configs) {
      logv("***** Camera ID ****** ${config.id}")
      logv(" ***** Width ****** ${config.width}")
      logv(" ***** Height ****** ${config.height}")
      logv(" ***** Lens Translation ****** ${arrayToString(config.lensTranslation)}")
      logv(" ***** Lens Rotation ****** ${arrayToString(config.lensRotation)}")
      logv(" ***** Handedness ****** ${config.position}")
      logv(" ***** is passthrough camera ****** ${config.isPassthrough}")
    }
  }

  private fun postEvent(message: String) {
    mainHandler.post { _eventLiveData.value = CameraEvent.NotificationEvent(message) }
  }
}

private fun ImageReader.onLatestImage(handler: Handler? = null, listener: (Image) -> Unit) {
  setOnImageAvailableListener(
      { reader ->
        val image = reader.acquireLatestImage()
        if (image != null) {
          listener(image)
          image.close()
        }
      },
      handler,
  )
}

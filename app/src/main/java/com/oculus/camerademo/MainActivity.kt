// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.oculus.camerademo

import android.Manifest
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.oculus.camerademo.ui.theme.CameraDemoTheme
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

data class DetectedItem(
    val label: String,
    val score: Float,
    val box: RectF,
    val distanceCm: Int = 0,
)

class MainActivity : ComponentActivity() {
  private val viewModel by lazy {
    ViewModelProvider.AndroidViewModelFactory(application).create(XrCameraDemoViewModel::class.java)
  }

  private val requestPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          requestResults ->
        viewModel.onPermissionGranted(requestResults)
      }

  private val permissionManager = PermissionManager()

  private var previewSurface: Surface? = null
  private var objectDetector: ObjectDetector? = null
  private val aiExecutor = Executors.newSingleThreadExecutor()
  private val networkExecutor = Executors.newSingleThreadExecutor()
  private val copyHandler = Handler(Looper.getMainLooper())
  private var isDetecting = false
  private var isCameraRunning = false
  private var lastImageWidth = 640
  private var lastImageHeight = 480
  private val detectedItemsState = mutableStateOf<List<DetectedItem>>(emptyList())
  private val aiStatusState = mutableStateOf("Robot Vision Ready")

  // --- ROBOT VISION & ESP32 SETTINGS ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1" // Default ESP32 Hotspot IP
  private val espPort = 4210
  private val cameraFocalLengthPx = 540f // Calibrated for Quest 3 640x480 crop

  // Real-world average heights in cm for COCO objects
  private val realObjectHeightsCm = mapOf(
      "person" to 170f,
      "chair" to 85f,
      "couch" to 85f,
      "dining table" to 75f,
      "bed" to 65f,
      "tv" to 60f,
      "laptop" to 25f,
      "cell phone" to 15f,
      "bottle" to 22f,
      "cup" to 12f,
      "backpack" to 45f,
      "book" to 24f,
      "potted plant" to 40f
  )

  private var lastLockedLabel = ""
  private var consecutiveFrameCount = 0

  private val frameGrabber =
      object : Runnable {
        override fun run() {
          if (isCameraRunning) {
            grabFrameAndDetect()
            copyHandler.postDelayed(this, 150)
          }
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      udpSocket = DatagramSocket()
    } catch (_: Exception) {}
    setupObjectDetector()

    viewModel.uiState.observe(this) { uiState ->
      setContent {
        CameraDemoTheme {
          Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            CameraDemoUi(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                cameraState = uiState,
                detectedItems = detectedItemsState.value,
                aiStatus = aiStatusState.value,
                onStartCamera = { onStartCamera() },
                onStopCamera = { onStopCamera() },
                onExit = { onExit() },
            )
          }
        }
      }
    }

    viewModel.cameraEvents.observe(this) { event ->
      if (event is CameraEvent.NotificationEvent) {
        Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
        viewModel.onHandleCameraEvent()
      }
    }
  }

  private fun setupObjectDetector() {
    try {
      val baseOptions = BaseOptions.builder().setModelAssetPath("model.tflite").build()
      val options =
          ObjectDetector.ObjectDetectorOptions.builder()
              .setBaseOptions(baseOptions)
              .setRunningMode(RunningMode.IMAGE)
              .setMaxResults(5)
              .setScoreThreshold(0.55f)
              .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
      aiStatusState.value = "Robot Vision Loaded"
    } catch (e: Exception) {
      aiStatusState.value = "AI Error: ${e.message}"
    }
  }

  private fun grabFrameAndDetect() {
    val surface = previewSurface ?: return
    if (!surface.isValid || isDetecting || objectDetector == null) return

    isDetecting = true
    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(
          surface,
          bitmap,
          { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
              runAiDetection(bitmap)
            } else {
              bitmap.recycle()
              isDetecting = false
            }
          },
          copyHandler,
      )
    } catch (_: Exception) {
      bitmap.recycle()
      isDetecting = false
    }
  }

  private fun estimateDistanceCm(label: String, box: RectF): Int {
    val boxHeightPx = box.height().coerceAtLeast(1f)
    val realHeight = realObjectHeightsCm[label] ?: 50f
    return ((realHeight * cameraFocalLengthPx) / boxHeightPx).toInt()
  }

  private fun sendUdpToEsp32(payload: String) {
    val socket = udpSocket ?: return
    networkExecutor.execute {
      try {
        val bytes = payload.toByteArray()
        val address = InetAddress.getByName(espIpAddress)
        val packet = DatagramPacket(bytes, bytes.size, address, espPort)
        socket.send(packet)
      } catch (_: Exception) {
        // Silently ignore when ESP32 is not connected yet
      }
    }
  }

  private fun runAiDetection(bitmap: Bitmap) {
    val detector = objectDetector ?: run {
      isDetecting = false
      return
    }
    lastImageWidth = bitmap.width
    lastImageHeight = bitmap.height

    aiExecutor.execute {
      try {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)
        val items =
            result.detections().mapNotNull { detection ->
              val category = detection.categories().firstOrNull() ?: return@mapNotNull null
              val name = category.categoryName()
              val box = detection.boundingBox()
              DetectedItem(
                  label = name,
                  score = category.score(),
                  box = box,
                  distanceCm = estimateDistanceCm(name, box),
              )
            }

        val primaryTarget = items.minByOrNull { it.distanceCm }

        if (primaryTarget != null) {
          if (primaryTarget.label == lastLockedLabel) {
            consecutiveFrameCount++
          } else {
            lastLockedLabel = primaryTarget.label
            consecutiveFrameCount = 1
          }

          val centerX = primaryTarget.box.centerX()
          val screenCenter = lastImageWidth / 2f
          val offsetPercent = (((centerX - screenCenter) / screenCenter) * 100).toInt()

          val action = when {
            primaryTarget.distanceCm < 50 -> "STOP"
            offsetPercent < -20 -> "LEFT"
            offsetPercent > 20 -> "RIGHT"
            else -> "FORWARD"
          }

          if (consecutiveFrameCount >= 2) {
            sendUdpToEsp32("${primaryTarget.label},${primaryTarget.distanceCm},$action,$offsetPercent")
          }

          runOnUiThread {
            detectedItemsState.value = items
            aiStatusState.value =
                "ROBOT: ${primaryTarget.label} | ${primaryTarget.distanceCm}cm | $action ($offsetPercent%)"
          }
        } else {
          consecutiveFrameCount = 0
          sendUdpToEsp32("none,999,CLEAR,0")
          runOnUiThread {
            detectedItemsState.value = emptyList()
            aiStatusState.value = "ROBOT: Clear Path (Scanning...)"
          }
        }
      } catch (e: Exception) {
        runOnUiThread { aiStatusState.value = "Detect err: ${e.message}" }
      } finally {
        bitmap.recycle()
        isDetecting = false
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.permissionRequestState.observe(this) { permissionState ->
      val permissionsToRequest = mutableListOf<String>()
      if (!permissionState.nativeCameraPermissionGranted) {
        permissionsToRequest.add(PermissionManager.ANDROID_CAMERA_PERMISSION)
      }
      if (!permissionState.vendorCameraPermissionGranted) {
        permissionsToRequest.add(PermissionManager.HZOS_CAMERA_PERMISSION)
      }

      if (permissionsToRequest.isNotEmpty()) {
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
      } else {
        viewModel.init()
      }
    }

    previewSurface?.let { viewModel.onResume(it) }
  }

  override fun onPause() {
    super.onPause()
    isCameraRunning = false
    copyHandler.removeCallbacks(frameGrabber)
    viewModel.onPause()
  }

  override fun onStop() {
    super.onStop()
    isCameraRunning = false
    copyHandler.removeCallbacks(frameGrabber)
    viewModel.shutdown()
  }

  override fun onDestroy() {
    super.onDestroy()
    objectDetector?.close()
    aiExecutor.shutdown()
    networkExecutor.shutdown()
    udpSocket?.close()
  }

  private fun onStartCamera() {
    checkPermissions()
    val surface = previewSurface
    if (surface == null) {
      Toast.makeText(this, "Something went wrong - surface is not ready", Toast.LENGTH_SHORT).show()
      return
    }
    viewModel.startCamera(surface)
    isCameraRunning = true
    copyHandler.removeCallbacks(frameGrabber)
    copyHandler.postDelayed(frameGrabber, 500)
  }

  private fun onStopCamera() {
    isCameraRunning = false
    copyHandler.removeCallbacks(frameGrabber)
    viewModel.stopCamera()
    detectedItemsState.value = emptyList()
    aiStatusState.value = "AI Stopped"
  }

  private fun onExit() {
    isCameraRunning = false
    copyHandler.removeCallbacks(frameGrabber)
    viewModel.shutdown()
    finish()
  }

  private fun checkPermissions() {
    if (
        !permissionManager.checkPermissions(
            this,
            Manifest.permission.CAMERA,
            PermissionManager.HZOS_CAMERA_PERMISSION,
        )
    ) {
      Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
      return
    }
  }

  private fun updatePreviewTexture(previewSurfaceTexture: SurfaceTexture, width: Int, height: Int) {
    previewSurface = Surface(previewSurfaceTexture)
  }

  @Composable
  fun CameraDemoUi(
      modifier: Modifier = Modifier,
      cameraState: CameraUiState,
      detectedItems: List<DetectedItem>,
      aiStatus: String,
      onStartCamera: () -> Unit,
      onStopCamera: () -> Unit,
      onExit: () -> Unit,
  ) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
      Row(horizontalArrangement = Arrangement.SpaceBetween) {
        Button(onClick = onStartCamera) {
          Text("Start camera")
        }

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text("AI Status")
          Text(aiStatus)
        }
      }
      Row(horizontalArrangement = Arrangement.SpaceEvenly) {
        Button(onClick = onStopCamera) {
          Text("Stop camera")
        }
        Button(onClick = onExit) {
          Text("Exit")
        }
      }
      Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
              TextureView(this@MainActivity).apply {
                surfaceTextureListener =
                    object : SurfaceTextureListener {
                      override fun onSurfaceTextureAvailable(
                          surface: SurfaceTexture,
                          width: Int,
                          height: Int,
                      ) {
                        updatePreviewTexture(surface, width, height)
                      }

                      override fun onSurfaceTextureSizeChanged(
                          surface: SurfaceTexture,
                          width: Int,
                          height: Int,
                      ) {
                        updatePreviewTexture(surface, width, height)
                      }

                      override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        return true
                      }

                      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
              }
            },
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
          val scaleX = size.width / lastImageWidth.toFloat().coerceAtLeast(1f)
          val scaleY = size.height / lastImageHeight.toFloat().coerceAtLeast(1f)

          val textPaint =
              android.graphics.Paint().apply {
                color = android.graphics.Color.GREEN
                textSize = 36f
                isFakeBoldText = true
              }

          for (item in detectedItems) {
            val left = item.box.left * scaleX
            val top = item.box.top * scaleY
            val right = item.box.right * scaleX
            val bottom = item.box.bottom * scaleY

            drawRect(
                color = Color.Green,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4.dp.toPx()),
            )

            val labelText = "${item.label} (${item.distanceCm}cm) ${(item.score * 100).toInt()}%"
            val textY = (top - 8f).coerceAtLeast(36f)
            drawContext.canvas.nativeCanvas.drawText(labelText, left, textY, textPaint)
          }
        }
      }
    }
  }
}

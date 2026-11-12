// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.oculus.camerademo

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlin.math.abs

data class DetectedItem(
    val label: String,
    val score: Float,
    val box: RectF,
    val distanceCm: Int = 0,
)

data class NavPointsStatus(
    val topMidFree: Boolean = true,
    val midFree: Boolean = true,
    val botMidFree: Boolean = true,
    val leftFree: Boolean = true,
    val rightFree: Boolean = true,
    val floorOk: Boolean = true,
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
  private val navPointsState = mutableStateOf(NavPointsStatus())

  // --- ESP32 & CAMERA KALIBRATIE ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210
  private val cameraFocalLengthPx = 540f

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

  private val distanceHistory = ArrayDeque<Int>()
  private var lockedTurnDirection = "NONE"

  private val frameGrabber =
      object : Runnable {
        override fun run() {
          if (isCameraRunning) {
            grabFrameAndDetect()
            copyHandler.postDelayed(this, 120)
          }
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Verberg statusbalken voor 100% edge-to-edge fullscreen
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    try {
      udpSocket = DatagramSocket()
    } catch (_: Exception) {}
    setupObjectDetector()

    viewModel.uiState.observe(this) { uiState ->
      setContent {
        CameraDemoTheme {
          FullscreenRobotVisionUi(
              modifier = Modifier.fillMaxSize(),
              cameraState = uiState,
              detectedItems = detectedItemsState.value,
              navPoints = navPointsState.value,
              aiStatus = aiStatusState.value,
              onSurfaceReady = { surface -> previewSurface = surface },
              onSurfaceDestroyed = { previewSurface = null },
              onStartCamera = { onStartCamera() },
              onStopCamera = { onStopCamera() },
              onExit = { onExit() },
          )
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
              .setMaxResults(8)
              .setScoreThreshold(0.50f)
              .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
      aiStatusState.value = "Vision & 5-Point Nav Loaded"
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

  private fun estimateRealisticDistanceCm(label: String, box: RectF): Int {
    val boxHeightPx = box.height()
    if (boxHeightPx < 18f) return 350
    val realHeight = realObjectHeightsCm[label] ?: 55f
    val rawCm = ((realHeight * cameraFocalLengthPx) / boxHeightPx).toInt()
    return rawCm.coerceIn(15, 350)
  }

  private fun isFloorClearAndUniform(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    val startX = (w * 0.30f).toInt()
    val endX = (w * 0.70f).toInt()
    val startY = (h * 0.82f).toInt()
    val endY = (h * 0.96f).toInt()

    var edgeCount = 0
    var sampleCount = 0

    for (y in startY until endY step 4) {
      for (x in startX until endX step 4) {
        val c1 = bitmap.getPixel(x, y)
        val c2 = bitmap.getPixel(x + 4, y)
        val c3 = bitmap.getPixel(x, y + 4)

        val luma1 = (AndroidColor.red(c1) + AndroidColor.green(c1) + AndroidColor.blue(c1)) / 3
        val luma2 = (AndroidColor.red(c2) + AndroidColor.green(c2) + AndroidColor.blue(c2)) / 3
        val luma3 = (AndroidColor.red(c3) + AndroidColor.green(c3) + AndroidColor.blue(c3)) / 3

        if (abs(luma1 - luma2) > 38 || abs(luma1 - luma3) > 38) {
          edgeCount++
        }
        sampleCount++
      }
    }

    if (sampleCount == 0) return true
    return (edgeCount.toFloat() / sampleCount.toFloat()) < 0.14f
  }

  private fun sendUdpToEsp32(payload: String) {
    val socket = udpSocket ?: return
    networkExecutor.execute {
      try {
        val bytes = payload.toByteArray()
        val address = InetAddress.getByName(espIpAddress)
        val packet = DatagramPacket(bytes, bytes.size, address, espPort)
        socket.send(packet)
      } catch (_: Exception) {}
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
                  distanceCm = estimateRealisticDistanceCm(name, box),
              )
            }

        val w = lastImageWidth.toFloat()
        val h = lastImageHeight.toFloat()
        val ptTopMid = Pair(w * 0.50f, h * 0.25f)
        val ptMid = Pair(w * 0.50f, h * 0.52f)
        val ptBotMid = Pair(w * 0.50f, h * 0.78f)
        val ptLeft = Pair(w * 0.20f, h * 0.55f)
        val ptRight = Pair(w * 0.80f, h * 0.55f)

        fun isPointBlocked(px: Float, py: Float): Boolean {
          return items.any { item ->
            item.distanceCm < 95 &&
                px >= (item.box.left - 25f) &&
                px <= (item.box.right + 25f) &&
                py >= (item.box.top - 25f) &&
                py <= (item.box.bottom + 25f)
          }
        }

        val floorOk = isFloorClearAndUniform(bitmap)
        val topMidFree = !isPointBlocked(ptTopMid.first, ptTopMid.second)
        val midFree = !isPointBlocked(ptMid.first, ptMid.second)
        val botMidFree = !isPointBlocked(ptBotMid.first, ptBotMid.second) && floorOk
        val leftFree = !isPointBlocked(ptLeft.first, ptLeft.second)
        val rightFree = !isPointBlocked(ptRight.first, ptRight.second)

        val centerPathClear = topMidFree && midFree && botMidFree
        val command: String
        if (centerPathClear) {
          lockedTurnDirection = "NONE"
          command = "FORWARD"
        } else {
          if (lockedTurnDirection == "NONE") {
            lockedTurnDirection = when {
              leftFree && !rightFree -> "TURN_LEFT"
              rightFree && !leftFree -> "TURN_RIGHT"
              leftFree && rightFree -> "TURN_LEFT"
              else -> "SPIN_SEARCH"
            }
          }
          command = lockedTurnDirection
        }

        val closest = items.minByOrNull { it.distanceCm }
        val smoothedCm = if (closest != null) {
          distanceHistory.addLast(closest.distanceCm)
          if (distanceHistory.size > 4) distanceHistory.removeFirst()
          distanceHistory.sorted()[distanceHistory.size / 2]
        } else {
          distanceHistory.clear()
          350
        }

        val whatIsIt = closest?.label ?: (if (!floorOk) "floor_obstacle" else "clear")
        val pBits = "${if (topMidFree) 1 else 0}${if (midFree) 1 else 0}${if (botMidFree) 1 else 0}${if (leftFree) 1 else 0}${if (rightFree) 1 else 0}"
        sendUdpToEsp32("CMD:$command|OBJ:$whatIsIt|CM:$smoothedCm|PTS:$pBits")

        val objectText = if (closest != null) "${closest.label} (~${smoothedCm}cm)" else (if (!floorOk) "VLOER OBSTAKEL!" else "Vrij")
        runOnUiThread {
          detectedItemsState.value = items
          navPointsState.value = NavPointsStatus(topMidFree, midFree, botMidFree, leftFree, rightFree, floorOk)
          aiStatusState.value = "[$command]  |  Ziet: $objectText"
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
    viewModel.stopCamera() // <-- GOED
    detectedItemsState.value = emptyList()
    aiStatusState.value = "AI Stopped"
  }

  private fun onExit() {
    onStopCamera()
    finish()
  }

  private fun checkPermissions() {
    if (
        !permissionManager.checkPermissions(
            this,
            android.Manifest.permission.CAMERA,
            PermissionManager.HZOS_CAMERA_PERMISSION,
        )
    ) {
      Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
    }
  }
}

@Composable
fun FullscreenRobotVisionUi(
    modifier: Modifier = Modifier,
    cameraState: Any,
    detectedItems: List<DetectedItem>,
    navPoints: NavPointsStatus,
    aiStatus: String,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onStartCamera: () -> Unit,
    onStopCamera: () -> Unit,
    onExit: () -> Unit,
) {
  Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
    // 1. 100% FULLSCREEN CAMERA FEED
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
          TextureView(context).apply {
            surfaceTextureListener =
                object : SurfaceTextureListener {
                  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                    onSurfaceReady(Surface(surface))
                  }
                  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
                  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    onSurfaceDestroyed()
                    return true
                  }
                  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
          }
        },
    )

    // 2. FULLSCREEN AR / ROBOT HUD OVERLAY (Boxen + 5 Meetpunten + Vloerzone)
    Canvas(modifier = Modifier.fillMaxSize()) {
      val scaleX = size.width / 640f
      val scaleY = size.height / 480f
      val textPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 38f
        isAntiAlias = true
        setShadowLayer(6f, 2f, 2f, AndroidColor.BLACK)
      }

      // Teken AI Object Boxen + Afstand in cm
      for (item in detectedItems) {
        val left = item.box.left * scaleX
        val top = item.box.top * scaleY
        val right = item.box.right * scaleX
        val bottom = item.box.bottom * scaleY
        val boxColor = if (item.distanceCm < 95) Color(0xFFFF3B30) else Color(0xFF00E5FF)

        drawRect(
            color = boxColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 5f),
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${item.label} (${item.distanceCm}cm)",
            left + 8f,
            (top - 10f).coerceAtLeast(40f),
            textPaint,
        )
      }

      // Teken Vloer-Scan Zone onderin
      val floorColor = if (navPoints.floorOk) Color(0x5500FF66) else Color(0x88FF2222)
      drawRect(
          color = floorColor,
          topLeft = Offset(size.width * 0.30f, size.height * 0.82f),
          size = Size(size.width * 0.40f, size.height * 0.14f),
          style = Stroke(width = 4f),
      )

      // Helper om de 5 navigatie-vizieren te tekenen (Groen = Vrij, Rood = Geblokkeerd)
      fun drawNavDot(xRatio: Float, yRatio: Float, isFree: Boolean, tag: String) {
        val cx = size.width * xRatio
        val cy = size.height * yRatio
        val dotColor = if (isFree) Color(0xFF00FF66) else Color(0xFFFF2222)
        drawCircle(color = dotColor, radius = 22f, center = Offset(cx, cy))
        drawCircle(color = Color.White, radius = 26f, center = Offset(cx, cy), style = Stroke(3f))
        drawContext.canvas.nativeCanvas.drawText(tag, cx - 12f, cy + 52f, textPaint)
      }

      drawNavDot(0.50f, 0.25f, navPoints.topMidFree, "T")
      drawNavDot(0.50f, 0.52f, navPoints.midFree, "M")
      drawNavDot(0.50f, 0.78f, navPoints.botMidFree, "B")
      drawNavDot(0.20f, 0.55f, navPoints.leftFree, "L")
      drawNavDot(0.80f, 0.55f, navPoints.rightFree, "R")
    }

    // 3. ZWEVENDE ROBOT STATUS BALK BOVENIN
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 16.dp)
            .background(Color(0xAA000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
      Text(
          text = aiStatus,
          color = Color(0xFF00FF66),
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
      )
    }

    // 4. COMPACTE KNOPPEN ONDERIN (Zwevend over het fullscreen beeld)
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
      Button(
          onClick = onStartCamera,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC007AFF)),
      ) {
        Text("Start Vision")
      }
      Button(
          onClick = onStopCamera,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC444444)),
      ) {
        Text("Stop")
      }
      Button(
          onClick = onExit,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xCCFF3B30)),
      ) {
        Text("Exit")
      }
    }
  }
}

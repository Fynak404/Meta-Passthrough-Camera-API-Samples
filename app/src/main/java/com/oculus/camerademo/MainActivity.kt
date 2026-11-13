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
import kotlin.math.roundToInt

data class DetectedItem(
    val label: String,
    val score: Float,
    val box: RectF,
    val distanceCm: Int = 0,
    val heightZone: String = "TORSO",
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
  private val aiStatusState = mutableStateOf("3D 1.80m Robot Vision Ready")

  // --- ESP32 & UDP SETTINGS ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210

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

  private var lockedTurnDirection = "NONE"

  private val frameGrabber =
      object : Runnable {
        override fun run() {
          if (isCameraRunning) {
            grabFrameAndDetect()
            copyHandler.postDelayed(this, 100)
          }
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
          Robot3DBlockUi(
              modifier = Modifier.fillMaxSize(),
              cameraState = uiState,
              detectedItems = detectedItemsState.value,
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
              .setScoreThreshold(0.40f)
              .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
      aiStatusState.value = "3D Bounding Block Engine Ready"
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
              runDetectionAndCommand(bitmap)
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

  private fun measureStereoDepthCm(stereo: StereoFramePair, xRatio: Float, yRatio: Float): Int? {
    val w = stereo.width
    val h = stereo.height
    val rx = (w * xRatio).toInt()
    val ry = (h * yRatio).toInt()
    val halfPatch = 6
    val maxDisp = ((stereo.baselineCm * stereo.focalLengthPx) / 20f).toInt().coerceIn(25, w / 3)

    if (ry < halfPatch + 2 || ry >= h - halfPatch - 2) return null
    if (rx < halfPatch + 2 || rx + maxDisp >= w - halfPatch - 2) return null

    var rSum = 0
    var count = 0
    for (dy in -halfPatch..halfPatch step 2) {
      val row = (ry + dy) * w
      for (dx in -halfPatch..halfPatch step 2) {
        rSum += (stereo.rightY[row + rx + dx].toInt() and 0xFF)
        count++
      }
    }
    val rMean = rSum / count
    var rVar = 0
    for (dy in -halfPatch..halfPatch step 2) {
      val row = (ry + dy) * w
      for (dx in -halfPatch..halfPatch step 2) {
        rVar += abs((stereo.rightY[row + rx + dx].toInt() and 0xFF) - rMean)
      }
    }
    if (rVar / count < 4) return null

    var bestDisp = -1
    var bestSad = Int.MAX_VALUE
    for (d in 3..maxDisp) {
      val lx = rx + d
      var lSum = 0
      for (dy in -halfPatch..halfPatch step 2) {
        val row = (ry + dy) * w
        for (dx in -halfPatch..halfPatch step 2) {
          lSum += (stereo.leftY[row + lx + dx].toInt() and 0xFF)
        }
      }
      val lMean = lSum / count
      var sad = 0
      for (dy in -halfPatch..halfPatch step 2) {
        val rRow = (ry + dy) * w
        val lRow = (ry + dy) * w
        for (dx in -halfPatch..halfPatch step 2) {
          val rVal = (stereo.rightY[rRow + rx + dx].toInt() and 0xFF) - rMean
          val lVal = (stereo.leftY[lRow + lx + dx].toInt() and 0xFF) - lMean
          sad += abs(rVal - lVal)
        }
      }
      if (sad < bestSad) {
        bestSad = sad
        bestDisp = d
      }
    }
    if (bestDisp <= 0) return null
    return ((stereo.baselineCm * stereo.focalLengthPx) / bestDisp.toFloat()).roundToInt().coerceIn(20, 250)
  }

  private fun isFloorFlat(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    val startX = (w * 0.30f).toInt()
    val endX = (w * 0.70f).toInt()
    val startY = (h * 0.82f).toInt()
    val endY = (h * 0.96f).toInt()
    var grad = 0
    var count = 0
    for (y in startY until endY step 4) {
      for (x in startX until endX step 4) {
        val c1 = bitmap.getPixel(x, y)
        val c2 = bitmap.getPixel(x + 4, y)
        val l1 = (AndroidColor.red(c1) + AndroidColor.green(c1) + AndroidColor.blue(c1)) / 3
        val l2 = (AndroidColor.red(c2) + AndroidColor.green(c2) + AndroidColor.blue(c2)) / 3
        grad += abs(l1 - l2)
        count++
      }
    }
    return if (count == 0) true else (grad.toFloat() / count) < 18f
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

  private fun runDetectionAndCommand(bitmap: Bitmap) {
    val detector = objectDetector ?: run {
      isDetecting = false
      return
    }
    lastImageWidth = bitmap.width
    lastImageHeight = bitmap.height

    aiExecutor.execute {
      try {
        val stereo = viewModel.getLatestStereoPair()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)

        val items =
            result.detections().mapNotNull { detection ->
              val category = detection.categories().firstOrNull() ?: return@mapNotNull null
              val name = category.categoryName()
              val box = detection.boundingBox()
              val cx = (box.centerX() / lastImageWidth).coerceIn(0.05f, 0.95f)
              val cy = (box.centerY() / lastImageHeight).coerceIn(0.05f, 0.95f)

              val stereoDist = if (stereo != null) measureStereoDepthCm(stereo, cx, cy) else null
              val fallbackDist = if (box.height() > 12f) {
                (((realObjectHeightsCm[name] ?: 60f) * 540f) / box.height()).toInt().coerceIn(20, 250)
              } else 250

              val zone = when {
                cy < 0.35f -> "HOOFD (1.80m)"
                cy < 0.70f -> "TORSO"
                else -> "WIELEN/VLOER"
              }
              DetectedItem(name, category.score(), box, stereoDist ?: fallbackDist, zone)
            }

        val floorOk = isFloorFlat(bitmap)
        val closest = items.minByOrNull { it.distanceCm }
        
        // Beslissing voor de robot (binnen 65cm = remmen of draaien)
        val blocked = (closest != null && closest.distanceCm < 65) || !floorOk
        val command: String
        if (!blocked) {
          lockedTurnDirection = "NONE"
          command = "FORWARD"
        } else {
          if (lockedTurnDirection == "NONE") {
            // Kies op basis van positie of object of we links/rechts moeten uitwijken
            lockedTurnDirection = if (closest != null && closest.box.centerX() < lastImageWidth / 2f) "TURN_RIGHT" else "TURN_LEFT"
          }
          command = lockedTurnDirection
        }

        val minCm = closest?.distanceCm ?: (if (!floorOk) 30 else 250)
        val objLabel = closest?.label ?: (if (!floorOk) "uneven_floor" else "clear")

        sendUdpToEsp32("CMD:$command|OBJ:$objLabel|CM:$minCm")

        runOnUiThread {
          detectedItemsState.value = items
          aiStatusState.value = "ROBOT [$command] | Dichtstbij: ${closest?.label ?: "Geen"} (${minCm}cm)"
        }
      } catch (e: Exception) {
        runOnUiThread { aiStatusState.value = "Err: ${e.message}" }
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
      Toast.makeText(this, "Surface niet klaar", Toast.LENGTH_SHORT).show()
      return
    }
    viewModel.startCamera(surface)
    isCameraRunning = true
    copyHandler.removeCallbacks(frameGrabber)
    copyHandler.postDelayed(frameGrabber, 300)
  }

  private fun onStopCamera() {
    isCameraRunning = false
    copyHandler.removeCallbacks(frameGrabber)
    viewModel.stopCamera()
    detectedItemsState.value = emptyList()
    aiStatusState.value = "Gestopt"
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
      Toast.makeText(this, "Mist camerabevoegdheden", Toast.LENGTH_SHORT).show()
    }
  }
}

@Composable
fun Robot3DBlockUi(
    modifier: Modifier = Modifier,
    cameraState: Any,
    detectedItems: List<DetectedItem>,
    aiStatus: String,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onStartCamera: () -> Unit,
    onStopCamera: () -> Unit,
    onExit: () -> Unit,
) {
  Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
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

    // ECHTE 3D BLOKKEN CANVAS OVERLAY (Tekent strakke 3D omhullende blokken om objecten)
    Canvas(modifier = Modifier.fillMaxSize()) {
      val scaleX = size.width / 640f
      val scaleY = size.height / 480f
      val textPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 34f
        isAntiAlias = true
        setShadowLayer(6f, 2f, 2f, AndroidColor.BLACK)
      }

      for (item in detectedItems) {
        val left = item.box.left * scaleX
        val top = item.box.top * scaleY
        val right = item.box.right * scaleX
        val bottom = item.box.bottom * scaleY

        val boxColor = if (item.distanceCm < 65) Color(0xFFFF3B30) else Color(0xFF00E5FF)

        // Hoofdblok
        drawRect(
            color = boxColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 6f),
        )

        // 3D Diepte-lijntjes zodat het een solide blok vormt
        val depthOffset = 18f
        drawLine(color = boxColor.copy(alpha = 0.7f), start = Offset(right, top), end = Offset(right + depthOffset, top - depthOffset), strokeWidth = 3f)
        drawLine(color = boxColor.copy(alpha = 0.7f), start = Offset(right, bottom), end = Offset(right + depthOffset, bottom - depthOffset), strokeWidth = 3f)
        drawLine(color = boxColor.copy(alpha = 0.7f), start = Offset(right + depthOffset, top - depthOffset), end = Offset(right + depthOffset, bottom - depthOffset), strokeWidth = 3f)

        drawContext.canvas.nativeCanvas.drawText(
            "${item.label.uppercase()} [${item.distanceCm}cm] (${item.heightZone})",
            left + 8f,
            (top - 12f).coerceAtLeast(40f),
            textPaint,
        )
      }
    }

    // Status Balk Bovenin
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 24.dp)
            .background(Color(0xAA000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
      Text(
          text = aiStatus,
          color = Color(0xFF00FF66),
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
      )
    }

    // Besturingsknoppen Onderin
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
      Button(
          onClick = onStartCamera,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC007AFF)),
      ) {
        Text("Start 3D Vision")
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

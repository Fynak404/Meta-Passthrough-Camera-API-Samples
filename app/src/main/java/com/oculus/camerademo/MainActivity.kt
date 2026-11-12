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
)

data class SensorPoint(
    val tag: String,
    val xRatio: Float,
    val yRatio: Float,
    val distanceCm: Int,
    val isFree: Boolean,
)

data class NavPointsStatus(
    val topMid: SensorPoint = SensorPoint("T", 0.50f, 0.25f, 200, true),
    val mid: SensorPoint = SensorPoint("M", 0.50f, 0.50f, 200, true),
    val botMid: SensorPoint = SensorPoint("B", 0.50f, 0.74f, 200, true),
    val left: SensorPoint = SensorPoint("L", 0.20f, 0.52f, 200, true),
    val right: SensorPoint = SensorPoint("R", 0.80f, 0.52f, 200, true),
    val floorOk: Boolean = true,
    val floorReason: String = "CLEAN",
    val isStereoActive: Boolean = false,
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

  // --- ESP32 & NAVIGATION THRESHOLDS ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210
  private val safeThresholdCm = 60 // Rood onder 60cm

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

  private val pointHistory = mutableMapOf<String, ArrayDeque<Int>>()
  private val pointEma = mutableMapOf<String, Float>()
  private var lockedTurnDirection = "NONE"

  private val frameGrabber =
      object : Runnable {
        override fun run() {
          if (isCameraRunning) {
            grabFrameAndDetect()
            copyHandler.postDelayed(this, 100) // Snelle 10 FPS loop
          }
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // FORCEER TRUE IMMERSIVE FULLSCREEN (Geen randen, geen statusbalken)
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
              .setScoreThreshold(0.45f)
              .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
      aiStatusState.value = "Immersive 3D Vision Loaded"
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

  // Anti-Jitter Filter: Mediaan + EMA (Voorkomt uitschieters naar 40cm of max)
  private fun stabilizeCm(tag: String, rawCm: Int): Int {
    val clamped = rawCm.coerceIn(20, 300)
    val hist = pointHistory.getOrPut(tag) { ArrayDeque() }
    hist.addLast(clamped)
    if (hist.size > 6) hist.removeFirst()

    val median = hist.sorted()[hist.size / 2].toFloat()
    val prevEma = pointEma[tag] ?: median
    val smoothed = (prevEma * 0.60f) + (median * 0.40f)
    pointEma[tag] = smoothed
    return smoothed.roundToInt()
  }

  // Robuuste 3D Stereo Match met strenge variantie- en contrastkeuring
  private fun computeStereoPointDistanceCm(
      stereo: StereoFramePair,
      xRatio: Float,
      yRatio: Float,
  ): Int? {
    val w = stereo.width
    val h = stereo.height
    val rx = (w * xRatio).toInt()
    val ry = (h * yRatio).toInt()

    val halfPatch = 8
    val maxDisp = ((stereo.baselineCm * stereo.focalLengthPx) / 20f).toInt().coerceIn(30, w / 3)
    val minDisp = 3

    if (ry < halfPatch + 4 || ry >= h - halfPatch - 4) return null
    if (rx < halfPatch + 4 || rx + maxDisp >= w - halfPatch - 4) return null

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
        val diff = (stereo.rightY[row + rx + dx].toInt() and 0xFF) - rMean
        rVar += abs(diff)
      }
    }
    // Als de patch te eg dan is er geen diepte te berekenen (voorkomt foute 40cm jumps)
    if (rVar / count < 4) return null

    var bestDisp = -1
    var bestSad = Int.MAX_VALUE
    var secondBestSad = Int.MAX_VALUE

    for (d in minDisp..maxDisp) {
      val lx = rx + d
      var bestRowSad = Int.MAX_VALUE
      for (vy in -2..2 step 2) {
        var lSum = 0
        for (dy in -halfPatch..halfPatch step 2) {
          val row = (ry + dy + vy) * w
          for (dx in -halfPatch..halfPatch step 2) {
            lSum += (stereo.leftY[row + lx + dx].toInt() and 0xFF)
          }
        }
        val lMean = lSum / count
        var sad = 0
        for (dy in -halfPatch..halfPatch step 2) {
          val rRow = (ry + dy) * w
          val lRow = (ry + dy + vy) * w
          for (dx in -halfPatch..halfPatch step 2) {
            val rVal = (stereo.rightY[rRow + rx + dx].toInt() and 0xFF) - rMean
            val lVal = (stereo.leftY[lRow + lx + dx].toInt() and 0xFF) - lMean
            sad += abs(rVal - lVal)
          }
        }
        if (sad < bestRowSad) bestRowSad = sad
      }
      if (bestRowSad < bestSad) {
        secondBestSad = bestSad
        bestSad = bestRowSad
        bestDisp = d
      } else if (bestRowSad < secondBestSad) {
        secondBestSad = bestRowSad
      }
    }

    if (bestDisp <= 0) return null
    if (secondBestSad < (bestSad * 1.12f).toInt()) return null // Uniqueness check

    val distanceCm = ((stereo.baselineCm * stereo.focalLengthPx) / bestDisp.toFloat()).roundToInt()
    return distanceCm.coerceIn(20, 350)
  }

  private fun measurePoint(
      tag: String,
      xRatio: Float,
      yRatio: Float,
      stereo: StereoFramePair?,
      items: List<DetectedItem>,
  ): SensorPoint {
    val stereoCm = if (stereo != null) computeStereoPointDistanceCm(stereo, xRatio, yRatio) else null
    val px = lastImageWidth * xRatio
    val py = lastImageHeight * yRatio
    val coveringObj = items.filter { item ->
      px >= (item.box.left - 15f) &&
          px <= (item.box.right + 15f) &&
          py >= (item.box.top - 15f) &&
          py <= (item.box.bottom + 15f)
    }.minByOrNull { item -> item.distanceCm }

    val rawCm = when {
      stereoCm != null && coveringObj != null -> minOf(stereoCm, coveringObj.distanceCm)
      stereoCm != null -> stereoCm
      coveringObj != null -> coveringObj.distanceCm
      else -> pointEma[tag]?.roundToInt() ?: 180
    }
    val smoothCm = stabilizeCm(tag, rawCm)
    return SensorPoint(
        tag = tag,
        xRatio = xRatio,
        yRatio = yRatio,
        distanceCm = smoothCm,
        isFree = smoothCm > safeThresholdCm,
    )
  }

  private fun analyzeFloorSafety(bitmap: Bitmap): Pair<Boolean, String> {
    val w = bitmap.width
    val h = bitmap.height
    val startX = (w * 0.28f).toInt()
    val endX = (w * 0.72f).toInt()
    val startY = (h * 0.82f).toInt()
    val endY = (h * 0.96f).toInt()
    val colorBuckets = mutableMapOf<Int, Int>()
    var totalSamples = 0
    var lumaSum = 0L
    val lumas = mutableListOf<Int>()

    for (y in startY until endY step 4) {
      for (x in startX until endX step 4) {
        val c = bitmap.getPixel(x, y)
        val r = AndroidColor.red(c)
        val g = AndroidColor.green(c)
        val b = AndroidColor.blue(c)
        val luma = (r + g + b) / 3
        lumaSum += luma
        lumas.add(luma)
        totalSamples++
        val bucketKey = ((r / 48) shl 8) or ((g / 48) shl 4) or (b / 48)
        colorBuckets[bucketKey] = (colorBuckets[bucketKey] ?: 0) + 1
      }
    }
    if (totalSamples == 0) return Pair(true, "CLEAN")
    val avgLuma = (lumaSum / totalSamples).toInt()
    val minClusterSize = (totalSamples * 0.08f).toInt()
    val significantColors = colorBuckets.values.count { it >= minClusterSize }
    val darkPixels = lumas.count { it < 45 && (avgLuma - it) > 50 }
    val darkRatio = darkPixels.toFloat() / totalSamples.toFloat()

    return when {
      darkRatio > 0.09f -> Pair(false, "VLOER OBSTAKEL")
      significantColors >= 3 -> Pair(false, "VLOER CLUTTER")
      else -> Pair(true, "CLEAN")
    }
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
        val stereoPair = viewModel.getLatestStereoPair()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)

        val items =
            result.detections().mapNotNull { detection ->
              val category = detection.categories().firstOrNull() ?: return@mapNotNull null
              val name = category.categoryName()
              val box = detection.boundingBox()
              val centerXRatio = (box.centerX() / lastImageWidth.toFloat()).coerceIn(0.05f, 0.95f)
              val centerYRatio = (box.centerY() / lastImageHeight.toFloat()).coerceIn(0.05f, 0.95f)
              val stereoObjCm = if (stereoPair != null) {
                computeStereoPointDistanceCm(stereoPair, centerXRatio, centerYRatio)
              } else null
              val fallbackCm = if (box.height() >= 18f) {
                (((realObjectHeightsCm[name] ?: 55f) * 540f) / box.height()).toInt().coerceIn(20, 300)
              } else 300
              DetectedItem(
                  label = name,
                  score = category.score(),
                  box = box,
                  distanceCm = stereoObjCm ?: fallbackCm,
              )
            }

        val floorCheck = analyzeFloorSafety(bitmap)
        val floorOk = floorCheck.first
        val floorReason = floorCheck.second

        val ptT = measurePoint("T", 0.50f, 0.25f, stereoPair, items)
        val ptM = measurePoint("M", 0.50f, 0.50f, stereoPair, items)
        val rawBot = measurePoint("B", 0.50f, 0.74f, stereoPair, items)
        val ptB = if (!floorOk && rawBot.distanceCm > 45) {
          rawBot.copy(distanceCm = 35, isFree = false)
        } else {
          rawBot
        }
        val ptL = measurePoint("L", 0.20f, 0.52f, stereoPair, items)
        val ptR = measurePoint("R", 0.80f, 0.52f, stereoPair, items)

        val navStatus = NavPointsStatus(
            topMid = ptT,
            mid = ptM,
            botMid = ptB,
            left = ptL,
            right = ptR,
            floorOk = floorOk,
            floorReason = floorReason,
            isStereoActive = stereoPair != null,
        )

        val centerPathClear = ptT.isFree && ptM.isFree && ptB.isFree && floorOk
        val command: String
        if (centerPathClear) {
          lockedTurnDirection = "NONE"
          command = "FORWARD"
        } else {
          if (lockedTurnDirection == "NONE") {
            lockedTurnDirection = when {
              ptL.isFree && !ptR.isFree -> "TURN_LEFT"
              ptR.isFree && !ptL.isFree -> "TURN_RIGHT"
              ptL.isFree && ptR.isFree -> if (ptL.distanceCm >= ptR.distanceCm) "TURN_LEFT" else "TURN_RIGHT"
              else -> "SPIN_SEARCH"
            }
          }
          command = lockedTurnDirection
        }

        val closestObj = items.minByOrNull { item -> item.distanceCm }
        val minCm = minOf(
            ptT.distanceCm,
            ptM.distanceCm,
            ptB.distanceCm,
            closestObj?.distanceCm ?: 300
        )

        val whatIsIt = closestObj?.label ?: (if (!floorOk) "floor_$floorReason" else "clear")
        val pBits = "${if (ptT.isFree) 1 else 0}${if (ptM.isFree) 1 else 0}${if (ptB.isFree) 1 else 0}${if (ptL.isFree) 1 else 0}${if (ptR.isFree) 1 else 0}"
        
        sendUdpToEsp32("CMD:$command|OBJ:$whatIsIt|CM:$minCm|PTS:$pBits")

        val stereoTag = if (stereoPair != null) "[3D STEREO]" else "[MONO AI]"
        val objectText = if (closestObj != null) "${closestObj.label} (~${closestObj.distanceCm}cm)" else (if (!floorOk) "VLOER: $floorReason" else "Vrij")

        runOnUiThread {
          detectedItemsState.value = items
          navPointsState.value = navStatus
          aiStatusState.value = "$stereoTag [$command] | Ziet: $objectText"
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
      Toast.makeText(this, "Surface is niet gereed", Toast.LENGTH_SHORT).show()
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
    aiStatusState.value = "Vision Gestopt"
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
      Toast.makeText(this, "Mist benodigde permissies", Toast.LENGTH_SHORT).show()
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
    // 1. 100% IMMERSIVE FULLSCREEN PASSTHROUGH VIEW
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

    // 2. AR HUD OVERLAY (Boxen + 5 Meetpunten + Vloerzone)
    Canvas(modifier = Modifier.fillMaxSize()) {
      val scaleX = size.width / 640f
      val scaleY = size.height / 480f
      val textPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 36f
        isAntiAlias = true
        setShadowLayer(6f, 2f, 2f, AndroidColor.BLACK)
      }

      for (item in detectedItems) {
        val left = item.box.left * scaleX
        val top = item.box.top * scaleY
        val right = item.box.right * scaleX
        val bottom = item.box.bottom * scaleY
        val boxColor = if (item.distanceCm < 60) Color(0xFFFF3B30) else Color(0xFF00E5FF)

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

      val floorColor = if (navPoints.floorOk) Color(0x5500FF66) else Color(0x88FF2222)
      drawRect(
          color = floorColor,
          topLeft = Offset(size.width * 0.28f, size.height * 0.82f),
          size = Size(size.width * 0.44f, size.height * 0.14f),
          style = Stroke(width = 4f),
      )

      fun drawNavSensor(pt: SensorPoint) {
        val cx = size.width * pt.xRatio
        val cy = size.height * pt.yRatio
        val dotColor = if (pt.isFree) Color(0xFF00FF66) else Color(0xFFFF2222)
        drawCircle(color = dotColor, radius = 22f, center = Offset(cx, cy))
        drawCircle(color = Color.White, radius = 26f, center = Offset(cx, cy), style = Stroke(3f))
        drawContext.canvas.nativeCanvas.drawText("${pt.tag}:${pt.distanceCm}cm", cx - 35f, cy + 52f, textPaint)
      }

      drawNavSensor(navPoints.topMid)
      drawNavSensor(navPoints.mid)
      drawNavSensor(navPoints.botMid)
      drawNavSensor(navPoints.left)
      drawNavSensor(navPoints.right)
    }

    // 3. ZWEVENDE STATUS BAR BOVENIN
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

    // 4. BESTURINGSKNOPPEN ONDERIN
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

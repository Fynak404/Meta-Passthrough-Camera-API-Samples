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

// 3D VOXEL CEL (X = Links/Rechts, Y = Hoogte 0..200cm, Z = Diepte vooruit)
data class Voxel3D(
    val x: Int,
    val y: Int,
    val z: Int,
    var density: Float = 0f, // 0.0 = Lege lucht, 1.0 = Massief 3D obstakel
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
  private val aiStatusState = mutableStateOf("3D Voxel Engine Ready")

  // --- ESP32 & 3D VOXEL MAP SETTINGS ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210

  // 3D Voxel Ruimte: 15 breed (X) x 10 hoog (Y: 0cm tot 200cm) x 15 diep (Z: 0cm tot 150cm)
  private val sizeX = 15
  private val sizeY = 10 // Elke laag = 20 cm hoogte (Laag 0 = Vloer/Wielen, Laag 8-9 = Quest 3 Hoofd op 180cm)
  private val sizeZ = 15
  private val voxelSpace = Array(sizeX) { x ->
    Array(sizeY) { y ->
      Array(sizeZ) { z -> Voxel3D(x, y, z, 0f) }
    }
  }

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
          Voxel3DRobotUi(
              modifier = Modifier.fillMaxSize(),
              cameraState = uiState,
              detectedItems = detectedItemsState.value,
              voxels = voxelSpace,
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
      aiStatusState.value = "3D Voxel Spatial Mapper Ready"
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
              run3DSpatialMapping(bitmap)
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

  // Meet 3D diepte op een specifiek punt via stereo-dispariteit (Linker + Rechter Quest 3 lens)
  private fun measureStereoDepthCm(stereo: StereoFramePair, xRatio: Float, yRatio: Float): Int? {
    val w = stereo.width
    val h = stereo.height
    val rx = (w * xRatio).toInt()
    val ry = (h * yRatio).toInt()
    val halfPatch = 6
    val maxDisp = ((stereo.baselineCm * stereo.focalLengthPx) / 25f).toInt().coerceIn(25, w / 3)

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

  // Controleer vlakheid van de grond op laag Y=0
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
    return if (count == 0) true else (grad.toFloat() / count) < 17f
  }

  // Vul het 3D Voxel Raster met de gemeten dieptepunten + AI objecten
  private fun update3DVoxelMap(
      stereo: StereoFramePair?,
      items: List<DetectedItem>,
      floorOk: Boolean
  ) {
    // 1. Temporal Decay: vervaag oude voxels langzaam zodat de kaart live meebeweegt
    for (x in 0 until sizeX) {
      for (y in 0 until sizeY) {
        for (z in 0 until sizeZ) {
          voxelSpace[x][y][z].density = (voxelSpace[x][y][z].density * 0.80f).coerceAtLeast(0f)
        }
      }
    }

    // 2. Vloerlaag (Y=0) checken direct voor de wielen (Z=1..3)
    if (!floorOk) {
      for (x in 6..8) {
        for (z in 1..3) {
          voxelSpace[x][0][z].density = 1.0f
        }
      }
    }

    // 3. Stereo 3D Raster-scan over het gehele gezichtsveld (9x7 punten in 3D ruimte)
    if (stereo != null) {
      for (gx in 0 until 9) {
        val xRatio = 0.15f + (gx * 0.0875f) // 0.15 tot 0.85
        for (gy in 0 until 7) {
          val yRatio = 0.15f + (gy * 0.10f) // 0.15 (Hoog/Hoofd) tot 0.75 (Laag/Vloer)
          val distCm = measureStereoDepthCm(stereo, xRatio, yRatio) ?: continue

          if (distCm < 140) {
            val vx = ((xRatio * (sizeX - 1))).roundToInt().coerceIn(0, sizeX - 1)
            // Y-inversie: bovenin beeld (yRatio laag) = hoog in 3D (Y=8..9, hoofdhoogte 180cm)
            val vy = (((1f - yRatio) * (sizeY - 1))).roundToInt().coerceIn(0, sizeY - 1)
            val vz = (distCm / 10).coerceIn(1, sizeZ - 1)

            voxelSpace[vx][vy][vz].density = 1.0f
          }
        }
      }
    }

    // 4. AI Objecten als 3D-volumes in het voxel-raster plaatsen
    for (item in items) {
      val vx = ((item.box.centerX() / lastImageWidth) * (sizeX - 1)).roundToInt().coerceIn(0, sizeX - 1)
      val vz = (item.distanceCm / 10).coerceIn(1, sizeZ - 1)
      val topY = (((1f - (item.box.top / lastImageHeight)) * (sizeY - 1))).roundToInt().coerceIn(0, sizeY - 1)
      val botY = (((1f - (item.box.bottom / lastImageHeight)) * (sizeY - 1))).roundToInt().coerceIn(0, sizeY - 1)

      for (y in minOf(topY, botY)..maxOf(topY, botY)) {
        voxelSpace[vx][y][vz].density = 1.0f
      }
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

  private fun run3DSpatialMapping(bitmap: Bitmap) {
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
                cy < 0.35f -> "HOOFD (180cm)"
                cy < 0.70f -> "TORSO"
                else -> "WIELEN/VLOER"
              }
              DetectedItem(name, category.score(), box, stereoDist ?: fallbackDist, zone)
            }

        val floorOk = isFloorFlat(bitmap)
        update3DVoxelMap(stereo, items, floorOk)

        // 3D KOLLISIE CHECK OVER DE HELE HOOGTE VAN DE 1.80M ROBOT (Y = 0 tot Y = 9)
        var centerBlocked = !floorOk
        var leftBlocked = false
        var rightBlocked = false
        var blockedHeightZone = "VRIJ"

        for (y in 0 until sizeY) {
          for (z in 1..6) { // Kijk tot 60 cm vooruit in 3D
            // Middenkolom (X = 6, 7, 8)
            if (voxelSpace[6][y][z].density > 0.6f || voxelSpace[7][y][z].density > 0.6f || voxelSpace[8][y][z].density > 0.6f) {
              centerBlocked = true
              blockedHeightZone = if (y >= 7) "HOOFD-HOOGTE" else if (y >= 3) "TORSO-HOOGTE" else "VLOER/WIELEN"
            }
            // Linkerkolom (X = 2, 3, 4)
            if (voxelSpace[2][y][z].density > 0.6f || voxelSpace[3][y][z].density > 0.6f) {
              leftBlocked = true
            }
            // Rechterkolom (X = 10, 11, 12)
            if (voxelSpace[10][y][z].density > 0.6f || voxelSpace[11][y][z].density > 0.6f) {
              rightBlocked = true
            }
          }
        }

        val command: String
        if (!centerBlocked) {
          lockedTurnDirection = "NONE"
          command = "FORWARD"
        } else {
          if (lockedTurnDirection == "NONE") {
            lockedTurnDirection = when {
              !leftBlocked && rightBlocked -> "TURN_LEFT"
              !rightBlocked && leftBlocked -> "TURN_RIGHT"
              !leftBlocked && !rightBlocked -> "TURN_LEFT"
              else -> "SPIN_SEARCH"
            }
          }
          command = lockedTurnDirection
        }

        val closest = items.minByOrNull { it.distanceCm }
        val minCm = closest?.distanceCm ?: (if (!floorOk) 30 else 250)
        val objLabel = closest?.label ?: (if (!floorOk) "uneven_floor" else "clear_3d")

        sendUdpToEsp32("CMD:$command|OBJ:$objLabel|CM:$minCm|ZONE:$blockedHeightZone")

        runOnUiThread {
          detectedItemsState.value = items
          aiStatusState.value = "3D VOXEL [$command] | Hoogte: $blockedHeightZone | Grond: ${if (floorOk) "VLAK" else "ONVLAK"}"
        }
      } catch (e: Exception) {
        runOnUiThread { aiStatusState.value = "3D Map err: ${e.message}" }
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
    aiStatusState.value = "3D Mapping Gestopt"
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
fun Voxel3DRobotUi(
    modifier: Modifier = Modifier,
    cameraState: Any,
    detectedItems: List<DetectedItem>,
    voxels: Array<Array<Array<Voxel3D>>>,
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

    // 3D VOXEL PROJECTION & HUD OVERLAY
    Canvas(modifier = Modifier.fillMaxSize()) {
      val scaleX = size.width / 640f
      val scaleY = size.height / 480f
      val textPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 32f
        isAntiAlias = true
        setShadowLayer(6f, 2f, 2f, AndroidColor.BLACK)
      }

      // Teken 3D AI Object Boxen + Hoogtezone
      for (item in detectedItems) {
        val left = item.box.left * scaleX
        val top = item.box.top * scaleY
        val right = item.box.right * scaleX
        val bottom = item.box.bottom * scaleY
        drawRect(
            color = if (item.distanceCm < 70) Color(0xFFFF3B30) else Color(0xFF00E5FF),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 4f),
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${item.label} | ${item.distanceCm}cm [${item.heightZone}]",
            left + 6f,
            (top - 8f).coerceAtLeast(35f),
            textPaint,
        )
      }

      // Teken 3D Voxel Radar (Top-Down + Hoogte-indicatie) rechtsboven
      val radarX = size.width - 200f
      val radarY = 110f
      val cell = 11f
      for (x in 0 until 15) {
        for (z in 0 until 15) {
          var maxYBlocked = -1
          for (y in 0 until 10) {
            if (voxels[x][y][z].density > 0.6f) {
              maxYBlocked = y
            }
          }
          val color = when {
            x == 7 && z == 0 -> Color.Blue // Robot (1.80m)
            maxYBlocked >= 7 -> Color.Magenta // Hoofdhoogte obstakel (140-180cm)
            maxYBlocked >= 3 -> Color.Red // Torso obstakel (60-140cm)
            maxYBlocked >= 0 -> Color.Yellow // Laag obstakel / Vloer (0-60cm)
            else -> Color.Green.copy(alpha = 0.25f)
          }
          drawRect(
              color = color,
              topLeft = Offset(radarX + (x * cell), radarY + ((14 - z) * cell)),
              size = Size(cell - 1f, cell - 1f),
          )
        }
      }
    }

    // Top Status Banner
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

    // Bottom Controls
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
        Text("Start 3D Map")
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

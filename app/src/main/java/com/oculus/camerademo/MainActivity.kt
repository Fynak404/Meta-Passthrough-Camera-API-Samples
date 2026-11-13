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

data class GroundCell(
    val gridX: Int,
    val gridZ: Int,
    var isSafe: Boolean = true,
    var depthCm: Int = 100,
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
  private val aiStatusState = mutableStateOf("3D Ground Scanner Initializing...")

  // --- UDP ESP32 & GROUND MAPPING GRID ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210

  // 3D Ground Grid in front of the 1.80m robot (11 columns wide, 10 rows deep)
  private val gridCols = 11
  private val gridRows = 10
  private val groundGrid = Array(gridCols) { c ->
    Array(gridRows) { r -> GroundCell(c, r, true, 150) }
  }

  private var lockedTurnDirection = "NONE"

  private val frameGrabber =
      object : Runnable {
        override fun run() {
          if (isCameraRunning) {
            scanGroundAndEnvironment()
            copyHandler.postDelayed(this, 100) // 10 FPS continuous scanning loop
          }
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Fullscreen Immersive Mode
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
          GroundScannerUi(
              modifier = Modifier.fillMaxSize(),
              cameraState = uiState,
              grid = groundGrid,
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
              .setMaxResults(6)
              .setScoreThreshold(0.40f)
              .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
      aiStatusState.value = "3D Ground Scanner Ready"
    } catch (e: Exception) {
      aiStatusState.value = "AI Error: ${e.message}"
    }
  }

  private fun scanGroundAndEnvironment() {
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
              processTrue3DGroundScan(bitmap)
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

  // True 3D Stereo Disparity Calculation specifically for the Floor/Ground in front of the robot
  private fun computeGroundDisparityDepth(stereo: StereoFramePair, px: Int, py: Int): Int {
    val w = stereo.width
    val h = stereo.height
    val halfPatch = 6
    val maxDisp = 50

    if (py < halfPatch || py >= h - halfPatch || px < halfPatch || px + maxDisp >= w - halfPatch) return 150

    var rSum = 0
    var count = 0
    for (dy in -halfPatch..halfPatch step 2) {
      val row = (py + dy) * w
      for (dx in -halfPatch..halfPatch step 2) {
        rSum += (stereo.rightY[row + px + dx].toInt() and 0xFF)
        count++
      }
    }
    val rMean = rSum / count
    var bestDisp = -1
    var bestSad = Int.MAX_VALUE

    for (d in 2..maxDisp) {
      val lx = px + d
      var sad = 0
      for (dy in -halfPatch..halfPatch step 2) {
        val rRow = (py + dy) * w
        val lRow = (py + dy) * w
        for (dx in -halfPatch..halfPatch step 2) {
          val rVal = (stereo.rightY[rRow + px + dx].toInt() and 0xFF) - rMean
          val lVal = (stereo.leftY[lRow + lx + dx].toInt() and 0xFF) - rMean
          sad += abs(rVal - lVal)
        }
      }
      if (sad < bestSad) {
        bestSad = sad
        bestDisp = d
      }
    }

    if (bestDisp <= 0) return 150
    return ((stereo.baselineCm * stereo.focalLengthPx) / bestDisp.toFloat()).roundToInt().coerceIn(15, 300)
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

  private fun processTrue3DGroundScan(bitmap: Bitmap) {
    val detector = objectDetector ?: run {
      isDetecting = false
      return
    }
    lastImageWidth = bitmap.width
    lastImageHeight = bitmap.height

    aiExecutor.execute {
      try {
        val stereo = viewModel.getLatestStereoPair()

        // 1. SCAN THE GROUND MESH (Lower 40% of the camera view where the floor is)
        val w = lastImageWidth
        val h = lastImageHeight
        val groundStartY = (h * 0.60f).toInt()

        for (c in 0 until gridCols) {
          for (r in 0 until gridRows) {
            val sampleX = (w * (0.15f + c * 0.07f)).toInt().coerceIn(0, w - 1)
            val sampleY = (groundStartY + (r * ((h - groundStartY) / gridRows))).toInt().coerceIn(0, h - 1)

            val depth = if (stereo != null) {
              computeGroundDisparityDepth(stereo, sampleX, sampleY)
            } else 150

            // Check if ground is flat or has an obstacle/drop-off
            val c1 = bitmap.getPixel(sampleX, sampleY)
            val luma = (AndroidColor.red(c1) + AndroidColor.green(c1) + AndroidColor.blue(c1)) / 3
            val isSafe = depth > 50 && luma > 30

            groundGrid[c][r].isSafe = isSafe
            groundGrid[c][r].depthCm = depth
          }
        }

        // 2. CHECK IMMEDIATE PATH IN FRONT OF WHEELS (Row 0 and 1 in grid)
        var leftPathBlocked = false
        var rightPathBlocked = false
        var centerPathBlocked = false

        for (r in 0..2) {
          if (!groundGrid[3][r].isSafe || !groundGrid[4][r].isSafe) leftPathBlocked = true
          if (!groundGrid[5][r].isSafe) centerPathBlocked = true
          if (!groundGrid[6][r].isSafe || !groundGrid[7][r].isSafe) rightPathBlocked = true
        }

        val command: String
        if (!centerPathBlocked) {
          lockedTurnDirection = "NONE"
          command = "FORWARD"
        } else {
          if (lockedTurnDirection == "NONE") {
            lockedTurnDirection = if (!leftPathBlocked) "TURN_LEFT" else "TURN_RIGHT"
          }
          command = lockedTurnDirection
        }

        sendUdpToEsp32("CMD:$command|GROUND_SCAN:ACTIVE")

        runOnUiThread {
          aiStatusState.value = "3D GROUND MAPPING [CMD: $command] | Floor Status: ${if (centerPathBlocked) "BLOCKED" else "CLEAR"}"
        }
      } catch (e: Exception) {
        runOnUiThread { aiStatusState.value = "Scan Error: ${e.message}" }
      } finally {
        bitmap.recycle()
        isDetecting = false
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.permissionRequestState.observe(this) { permissionState ->
      val reqList = mutableListOf<String>()
      if (!permissionState.nativeCameraPermissionGranted) reqList.add(PermissionManager.ANDROID_CAMERA_PERMISSION)
      if (!permissionState.vendorCameraPermissionGranted) reqList.add(PermissionManager.HZOS_CAMERA_PERMISSION)
      if (reqList.isNotEmpty()) {
        requestPermissionLauncher.launch(reqList.toTypedArray())
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
    val surface = previewSurface ?: return
    viewModel.startCamera(surface)
    isCameraRunning = true
    copyHandler.removeCallbacks(frameGrabber)
    copyHandler.postDelayed(frameGrabber, 100)
  }

  private fun onStopCamera() {
    isCameraRunning = false
    copyHandler.removeCallbacks(frameGrabber)
    viewModel.stopCamera()
    aiStatusState.value = "Scanner Stopped"
  }

  private fun onExit() {
    onStopCamera()
    finish()
  }
}

@Composable
fun GroundScannerUi(
    modifier: Modifier = Modifier,
    cameraState: Any,
    grid: Array<Array<GroundCell>>,
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
        factory = { ctx ->
          TextureView(ctx).apply {
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

    // TRUE 3D GROUND MESH GRID OVERLAY ON THE FLOOR
    Canvas(modifier = Modifier.fillMaxSize()) {
      val startX = size.width * 0.2f
      val startY = size.height * 0.60f
      val cellWidth = (size.width * 0.6f) / 11f
      val cellHeight = (size.height * 0.38f) / 10f

      for (c in 0 until 11) {
        for (r in 0 until 10) {
          val cell = grid[c][r]
          val color = if (cell.isSafe) Color(0x6600FF66) else Color(0xAAFF3B30)

          drawRect(
              color = color,
              topLeft = Offset(startX + (c * cellWidth), startY + (r * cellHeight)),
              size = Size(cellWidth - 3f, cellHeight - 3f),
              style = Stroke(width = 2f)
          )
        }
      }
    }

    // Top Status Bar
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
      Button(onClick = onStartCamera, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC007AFF))) {
        Text("Start Ground Scanner")
      }
      Button(onClick = onStopCamera, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC444444))) {
        Text("Stop")
      }
      Button(onClick = onExit, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCCFF3B30))) {
        Text("Exit")
      }
    }
  }
}

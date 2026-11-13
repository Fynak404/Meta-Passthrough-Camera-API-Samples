// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.oculus.camerademo

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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

data class GroundMapNode(
    val x: Int,
    val z: Int,
    var isFlatAndSafe: Boolean = true,
    var surfaceDepthCm: Int = 100
)

class MainActivity : ComponentActivity() {
  private val viewModel by lazy {
    ViewModelProvider.AndroidViewModelFactory(application).create(XrCameraDemoViewModel::class.java)
  }

  private var previewSurface: Surface? = null
  private var objectDetector: ObjectDetector? = null
  private val aiExecutor = Executors.newSingleThreadExecutor()
  private val networkExecutor = Executors.newSingleThreadExecutor()
  private val copyHandler = Handler(Looper.getMainLooper())
  private var isScanning = false
  private var isCameraRunning = false
  
  private val systemStatusState = mutableStateOf("3D Environment Mapping Initialized")
  private val mapNodesState = mutableStateOf<List<GroundMapNode>>(emptyList())

  // UDP Configuration for ESP32 on Hoverboard Chassis
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210

  // 3D Floor Boundary Grid (12 columns wide, 10 rows deep mapped in front of the 1.80m robot)
  private val gridCols = 12
  private val gridRows = 10
  private val persistentGroundMap = Array(gridCols) { c ->
    Array(gridRows) { r -> GroundMapNode(c, r, true, 120) }
  }

  private var activeTurnCommand = "FORWARD"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    try {
      udpSocket = DatagramSocket()
    } catch (_: Exception) {}
    setupAiDetector()

    viewModel.uiState.observe(this) { uiState ->
      setContent {
        CameraDemoTheme {
          EnvironmentMappingUI(
              modifier = Modifier.fillMaxSize(),
              mapNodes = mapNodesState.value,
              statusText = systemStatusState.value,
              onSurfaceReady = { surface -> previewSurface = surface },
              onSurfaceDestroyed = { previewSurface = null },
              onStartMapping = { startEnvironmentScanning() },
              onStopMapping = { stopEnvironmentScanning() },
              onExit = { finish() }
          )
        }
      }
    }
  }

  private fun setupAiDetector() {
    try {
      val baseOptions = BaseOptions.builder().setModelAssetPath("model.tflite").build()
      val options = ObjectDetector.ObjectDetectorOptions.builder()
          .setBaseOptions(baseOptions)
          .setRunningMode(RunningMode.IMAGE)
          .setMaxResults(5)
          .setScoreThreshold(0.45f)
          .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
    } catch (e: Exception) {
      systemStatusState.value = "AI Setup Error: ${e.message}"
    }
  }

  private fun startEnvironmentScanning() {
    val surface = previewSurface
    if (surface == null) {
      Toast.makeText(this, "Camera Surface Not Ready", Toast.LENGTH_SHORT).show()
      return
    }
    viewModel.startCamera(surface)
    isCameraRunning = true
    loopScanning()
  }

  private fun loopScanning() {
    if (!isCameraRunning) return
    val surface = previewSurface ?: return
    if (!surface.isValid || isScanning) {
      copyHandler.postDelayed({ loopScanning() }, 100)
      return
    }

    isScanning = true
    val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    try {
      PixelCopy.request(surface, bitmap, { result ->
        if (result == PixelCopy.SUCCESS) {
          processSpatialEnvironment(bitmap)
        } else {
          bitmap.recycle()
          isScanning = false
        }
      }, copyHandler)
    } catch (_: Exception) {
      bitmap.recycle()
      isScanning = false
    }
  }

  private fun processSpatialEnvironment(bitmap: Bitmap) {
    aiExecutor.execute {
      try {
        val stereo = viewModel.getLatestStereoPair()
        val width = bitmap.width
        val height = bitmap.height
        
        // Focus on the ground plane (lower half of the visual feed)
        val groundStartY = (height * 0.55f).toInt()
        val nodeList = mutableListOf<GroundMapNode>()

        var immediateDanger = false
        var leftBlocked = false
        var rightBlocked = false

        for (c in 0 until gridCols) {
          for (r in 0 until gridRows) {
            val px = (width * (0.1f + (c * 0.08f))).toInt().coerceIn(0, width - 1)
            val py = (groundStartY + (r * ((height - groundStartY) / gridRows))).toInt().coerceIn(0, height - 1)

            // Calculate true distance via stereo disparity if available
            val depthCm = if (stereo != null) {
              calculateStereoDepth(stereo, px, py)
            } else {
              120
            }

            // Ground flat and safe validation (checking surface luma consistency and range)
            val pixelColor = bitmap.getPixel(px, py)
            val luma = (AndroidColor.red(pixelColor) + AndroidColor.green(pixelColor) + AndroidColor.blue(pixelColor)) / 3
            val isSafeSurface = depthCm > 45 && luma > 25

            val node = persistentGroundMap[c][r]
            // Maintain persistent spatial memory of the floor boundary
            node.isFlatAndSafe = if (!isSafeSurface) false else node.isFlatAndSafe
            node.surfaceDepthCm = depthCm
            nodeList.add(node)

            // Evaluate immediate wheels path (Rows 0 to 2 directly in front)
            if (r <= 2 && !node.isFlatAndSafe) {
              if (c <= 4) leftBlocked = true
              if (c in 5..6) immediateDanger = true
              if (c >= 7) rightBlocked = true
            }
          }
        }

        activeTurnCommand = when {
          !immediateDanger -> "FORWARD"
          !leftBlocked -> "TURN_LEFT"
          !rightBlocked -> "TURN_RIGHT"
          else -> "SPIN_SEARCH"
        }

        sendUdpCommandToRobot("CMD:$activeTurnCommand|MAP:PERSISTENT_ACTIVE")

        runOnUiThread {
          mapNodesState.value = nodeList
          systemStatusState.value = "3D MAP ACTIVE [Robot Command: $activeTurnCommand] | Floor Status: ${if (immediateDanger) "OBSTACLE / UNEVEN" else "CLEAR BOUNDARY"}"
        }
      } catch (e: Exception) {
        runOnUiThread { systemStatusState.value = "Mapping Error: ${e.message}" }
      } finally {
        bitmap.recycle()
        isScanning = false
        if (isCameraRunning) {
          copyHandler.postDelayed({ loopScanning() }, 100)
        }
      }
    }
  }

  private fun calculateStereoDepth(stereo: StereoFramePair, px: Int, py: Int): Int {
    val w = stereo.width
    val h = stereo.height
    val patch = 4
    val maxDisp = 50

    if (py < patch || py >= h - patch || px < patch || px + maxDisp >= w - patch) return 120

    var baseSum = 0
    for (dy in -patch..patch step 2) {
      val row = (py + dy) * w
      for (dx in -patch..patch step 2) {
        baseSum += (stereo.rightY[row + px + dx].toInt() and 0xFF)
      }
    }
    val mean = baseSum / ((patch * 2 + 1) * (patch * 2 + 1))

    var bestDisp = -1
    var minSad = Int.MAX_VALUE

    for (d in 2..maxDisp) {
      val lx = px + d
      var sad = 0
      for (dy in -patch..patch step 2) {
        val rRow = (py + dy) * w
        val lRow = (py + dy) * w
        for (dx in -patch..patch step 2) {
          val rVal = (stereo.rightY[rRow + px + dx].toInt() and 0xFF) - mean
          val lVal = (stereo.leftY[lRow + lx + dx].toInt() and 0xFF) - mean
          sad += abs(rVal - lVal)
        }
      }
      if (sad < minSad) {
        minSad = sad
        bestDisp = d
      }
    }

    if (bestDisp <= 0) return 120
    return ((stereo.baselineCm * stereo.focalLengthPx) / bestDisp.toFloat()).roundToInt().coerceIn(10, 300)
  }

  private fun sendUdpCommandToRobot(payload: String) {
    val socket = udpSocket ?: return
    networkExecutor.execute {
      try {
        val data = payload.toByteArray()
        val address = InetAddress.getByName(espIpAddress)
        val packet = DatagramPacket(data, data.size, address, espPort)
        socket.send(packet)
      } catch (_: Exception) {}
    }
  }

  private fun stopEnvironmentScanning() {
    isCameraRunning = false
    copyHandler.removeCallbacksAndMessages(null)
    viewModel.stopCamera()
    systemStatusState.value = "Environment Mapping Paused"
  }

  override fun onPause() {
    super.onPause()
    stopEnvironmentScanning()
  }

  override fun onDestroy() {
    super.onDestroy()
    stopEnvironmentScanning()
    objectDetector?.close()
    aiExecutor.shutdown()
    networkExecutor.shutdown()
    udpSocket?.close()
  }
}

@Composable
fun EnvironmentMappingUI(
    modifier: Modifier = Modifier,
    mapNodes: List<GroundMapNode>,
    statusText: String,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onStartMapping: () -> Unit,
    onStopMapping: () -> Unit,
    onExit: () -> Unit,
) {
  Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          TextureView(ctx).apply {
            surfaceTextureListener = object : SurfaceTextureListener {
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

    // PERSISTENT 3D GROUND BOUNDARY MESH OVERLAY
    Canvas(modifier = Modifier.fillMaxSize()) {
      val startX = size.width * 0.15f
      val startY = size.height * 0.55f
      val cellW = (size.width * 0.7f) / 12f
      val cellH = (size.height * 0.4f) / 10f

      for (node in mapNodes) {
        val color = if (node.isFlatAndSafe) Color(0x5500FF66) else Color(0xAAFF3B30)
        drawRect(
            color = color,
            topLeft = Offset(startX + (node.x * cellW), startY + (node.z * cellH)),
            size = Size(cellW - 2f, cellH - 2f),
            style = Stroke(width = 2f)
        )
      }
    }

    // Top Header Status Display
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 24.dp)
            .background(Color(0xAA000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
      Text(
          text = statusText,
          color = Color(0xFF00FF66),
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
      )
    }

    // Bottom Action Controls
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
      Button(onClick = onStartMapping, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC007AFF))) {
        Text("Start Mapping")
      }
      Button(onClick = onStopMapping, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC444444))) {
        Text("Pause")
      }
      Button(onClick = onExit, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCCFF3B30))) {
        Text("Exit")
      }
    }
  }
}

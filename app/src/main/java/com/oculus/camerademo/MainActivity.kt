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
import androidx.compose.ui.graphics.Color
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

// True 3D Object with 3D Spatial Coordinates (X, Y, Z in cm) and Dimensions
data class Spatial3DObject(
    val label: String,
    val distanceCm: Int,
    val x3dCm: Float, // Left/Right offset from robot center in cm
    val y3dCm: Float, // Height offset in cm
    val widthCm: Float,
    val heightCm: Float,
    val depthCm: Float,
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
  private val spatialObjectsState = mutableStateOf<List<Spatial3DObject>>(emptyList())
  private val aiStatusState = mutableStateOf("True 3D Spatial Engine Ready")

  // --- ESP32 UDP SETTINGS ---
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1"
  private val espPort = 4210

  private val realObjectDimensions = mapOf(
      "person" to Triple(50f, 170f, 40f),
      "chair" to Triple(60f, 85f, 60f),
      "couch" to Triple(140f, 85f, 80f),
      "dining table" to Triple(120f, 75f, 80f),
      "bed" to Triple(150f, 65f, 200f),
      "tv" to Triple(100f, 60f, 20f),
      "laptop" to Triple(35f, 5f, 25f),
      "bottle" to Triple(10f, 25f, 10f),
      "backpack" to Triple(35f, 45f, 20f),
      "potted plant" to Triple(30f, 50f, 30f)
  )

  private var lockedTurnDirection = "NONE"

  private val frameGrabber =
      object : Runnable {
        override fun run() {
          if (isCameraRunning) {
            scanTrue3DEnvironment()
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
          True3DRendererUi(
              modifier = Modifier.fillMaxSize(),
              cameraState = uiState,
              spatialObjects = spatialObjectsState.value,
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
      aiStatusState.value = "True 3D Spatial Scanner Initialized"
    } catch (e: Exception) {
      aiStatusState.value = "AI Error: ${e.message}"
    }
  }

  private fun scanTrue3DEnvironment() {
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
              processTrue3DSpace(bitmap)
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

  // True Stereo Depth Calculation returning exact depth in cm
  private fun measureExactDepthCm(stereo: StereoFramePair, px: Int, py: Int): Int? {
    val w = stereo.width
    val h = stereo.height
    val halfPatch = 6
    val maxDisp = 60

    if (py < halfPatch || py >= h - halfPatch || px < halfPatch || px + maxDisp >= w - halfPatch) return null

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

    if (bestDisp <= 0) return null
    return ((stereo.baselineCm * stereo.focalLengthPx) / bestDisp.toFloat()).roundToInt().coerceIn(20, 400)
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

  private fun processTrue3DSpace(bitmap: Bitmap) {
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

        val focalPx = stereo?.focalLengthPx ?: 540f

        val spatialObjects =
            result.detections().mapNotNull { detection ->
              val category = detection.categories().firstOrNull() ?: return@mapNotNull null
              val name = category.categoryName()
              val box = detection.boundingBox()

              val centerX = box.centerX().toInt()
              val centerY = box.centerY().toInt()

              val depthCm = if (stereo != null) {
                measureExactDepthCm(stereo, centerX, centerY) ?: 150
              } else {
                (((realObjectDimensions[name]?.second ?: 60f) * focalPx) / box.height()).toInt().coerceIn(20, 300)
              }

              // Calculate real 3D position relative to robot center (X, Y, Z in cm)
              // X: horizontal offset in cm (using similar triangles: X = depth * (pixel_x - cx) / focal)
              val x3d = (depthCm * (centerX - (lastImageWidth / 2f)) / focalPx)
              val y3d = (depthCm * ((lastImageHeight / 2f) - centerY) / focalPx) // Height relative to camera

              val dims = realObjectDimensions[name] ?: Triple(50f, 60f, 50f)

              Spatial3DObject(
                  label = name,
                  distanceCm = depthCm,
                  x3dCm = x3d,
                  y3dCm = y3d,
                  widthCm = dims.first,
                  heightCm = dims.second,
                  depthCm = dims.third,
              )
            }

        // Navigation decision based on closest 3D obstacle
        val closest = spatialObjects.minByOrNull { it.distanceCm }
        val blocked = closest != null && closest.distanceCm < 65 && abs(closest.x3dCm) < 45f

        val command: String
        if (!blocked) {
          lockedTurnDirection = "NONE"
          command = "FORWARD"
        } else {
          if (lockedTurnDirection == "NONE") {
            lockedTurnDirection = if (closest!!.x3dCm < 0f) "TURN_RIGHT" else "TURN_LEFT"
          }
          command = lockedTurnDirection
        }

        val minCm = closest?.distanceCm ?: 300
        val objLabel = closest?.label ?: "clear"

        sendUdpToEsp32("CMD:$command|OBJ:$objLabel|CM:$minCm")

        runOnUiThread {
          spatialObjectsState.value = spatialObjects
          aiStatusState.value = "TRUE 3D SPATIAL [$command] | Closest: $objLabel (${minCm}cm)"
        }
      } catch (e: Exception) {
        runOnUiThread { aiStatusState.value = "3D Error: ${e.message}" }
      } finally {
        bitmap.recycle()
        isDetecting = false
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.permissionRequestState.observe(this) { permissionState ->
      val req = mutableListOf<String>()
      if (!permissionState.nativeCameraPermissionGranted) req.add(PermissionManager.ANDROID_CAMERA_PERMISSION)
      if (!permissionState.vendorCameraPermissionGranted) req.add(PermissionManager.HZOS_CAMERA_PERMISSION)
      if (req.isNotEmpty()) {
        requestPermissionLauncher.launch(req.toTypedArray())
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
    spatialObjectsState.value = emptyList()
    aiStatusState.value = "Scanner Stopped"
  }

  private fun onExit() {
    onStopCamera()
    finish()
  }
}

@Composable
fun True3DRendererUi(
    modifier: Modifier = Modifier,
    cameraState: Any,
    spatialObjects: List<Spatial3DObject>,
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

    // TRUE 3D PERSPECTIVE WIREFRAME CUBOIDS RENDERER
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width / 2f
      val cy = size.height / 2f
      val focalPx = 540f // Optical focal length estimation

      val textPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 34f
        isAntiAlias = true
        setShadowLayer(6f, 2f, 2f, AndroidColor.BLACK)
      }

      for (obj in spatialObjects) {
        val z = obj.distanceCm.toFloat().coerceAtLeast(10f)
        val hw = obj.widthCm / 2f
        val hh = obj.heightCm / 2f
        val hd = obj.depthCm / 2f

        // 3D Bounding Box 8 Vertices (X, Y, Z relative to object center)
        // Front face and Back face corners
        val corners3D = listOf(
            Triple(obj.x3dCm - hw, obj.y3dCm - hh, z - hd), // 0: Front Top Left
            Triple(obj.x3dCm + hw, obj.y3dCm - hh, z - hd), // 1: Front Top Right
            Triple(obj.x3dCm + hw, obj.y3dCm + hh, z - hd), // 2: Front Bottom Right
            Triple(obj.x3dCm - hw, obj.y3dCm + hh, z - hd), // 3: Front Bottom Left
            Triple(obj.x3dCm - hw, obj.y3dCm - hh, z + hd), // 4: Back Top Left
            Triple(obj.x3dCm + hw, obj.y3dCm - hh, z + hd), // 5: Back Top Right
            Triple(obj.x3dCm + hw, obj.y3dCm + hh, z + hd), // 6: Back Bottom Right
            Triple(obj.x3dCm - hw, obj.y3dCm + hh, z + hd)  // 7: Back Bottom Left
        )

        // Project 3D vertices to 2D screen coordinates using perspective projection: screenX = cx + (X * focal / Z)
        val projected2D = corners3D.map { (x, y, depth) ->
          val screenX = cx + (x * focalPx / depth)
          val screenY = cy - (y * focalPx / depth) // Invert Y for screen coordinates
          Offset(screenX, screenY)
        }

        val boxColor = if (obj.distanceCm < 65) Color(0xFFFF3B30) else Color(0xFF00E5FF)
        val strokeWidth = 5f

        // Connect Front Face (0->1, 1->2, 2->3, 3->0)
        drawLine(boxColor, projected2D[0], projected2D[1], strokeWidth)
        drawLine(boxColor, projected2D[1], projected2D[2], strokeWidth)
        drawLine(boxColor, projected2D[2], projected2D[3], strokeWidth)
        drawLine(boxColor, projected2D[3], projected2D[0], strokeWidth)

        // Connect Back Face (4->5, 5->6, 6->7, 7->4)
        drawLine(boxColor.copy(alpha = 0.5f), projected2D[4], projected2D[5], strokeWidth)
        drawLine(boxColor.copy(alpha = 0.5f), projected2D[5], projected2D[6], strokeWidth)
        drawLine(boxColor.copy(alpha = 0.5f), projected2D[6], projected2D[7], strokeWidth)
        drawLine(boxColor.copy(alpha = 0.5f), projected2D[7], projected2D[4], strokeWidth)

        // Connect Connecting Edges (Front to Back)
        drawLine(boxColor.copy(alpha = 0.7f), projected2D[0], projected2D[4], strokeWidth)
        drawLine(boxColor.copy(alpha = 0.7f), projected2D[1], projected2D[5], strokeWidth)
        drawLine(boxColor.copy(alpha = 0.7f), projected2D[2], projected2D[6], strokeWidth)
        drawLine(boxColor.copy(alpha = 0.7f), projected2D[3], projected2D[7], strokeWidth)

        // Draw Object Label and Depth in 3D Space
        drawContext.canvas.nativeCanvas.drawText(
            "${obj.label.uppercase()} [${obj.distanceCm}cm]",
            projected2D[0].x,
            (projected2D[0].y - 12f).coerceAtLeast(40f),
            textPaint,
        )
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
        Text("Start True 3D")
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

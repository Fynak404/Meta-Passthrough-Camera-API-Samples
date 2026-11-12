// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.oculus.camerademo

import android.Manifest
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
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
import java.util.concurrent.Executors

data class DetectedItem(
    val label: String,
    val score: Float,
    val box: RectF,
)

class MainActivity : ComponentActivity() {
  companion object {
    private const val FLAG_ENABLED = "enabled"
  }

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
  private var textureViewRef: TextureView? = null
  private var objectDetector: ObjectDetector? = null
  private val aiExecutor = Executors.newSingleThreadExecutor()
  private var isDetecting = false
  private var lastImageWidth = 1
  private var lastImageHeight = 1
  private val detectedItemsState = mutableStateOf<List<DetectedItem>>(emptyList())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setupObjectDetector()

    viewModel.uiState.observe(this) { uiState ->
      setContent {
        CameraDemoTheme {
          Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            CameraDemoUi(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                cameraState = uiState,
                detectedItems = detectedItemsState.value,
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
              .setScoreThreshold(0.45f)
              .build()
      objectDetector = ObjectDetector.createFromOptions(this, options)
    } catch (e: Exception) {
      Toast.makeText(this, "AI Model load error: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  private fun runAiDetection(bitmap: Bitmap) {
    val detector = objectDetector ?: return
    if (isDetecting) return
    isDetecting = true
    lastImageWidth = bitmap.width
    lastImageHeight = bitmap.height

    aiExecutor.execute {
      try {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = detector.detect(mpImage)
        val items =
            result.detections().mapNotNull { detection ->
              val category = detection.categories().firstOrNull() ?: return@mapNotNull null
              DetectedItem(
                  label = category.categoryName(),
                  score = category.score(),
                  box = detection.boundingBox(),
              )
            }
        runOnUiThread { detectedItemsState.value = items }
      } catch (_: Exception) {
      } finally {
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
    viewModel.onPause()
  }

  override fun onStop() {
    super.onStop()
    viewModel.shutdown()
  }

  override fun onDestroy() {
    super.onDestroy()
    objectDetector?.close()
    aiExecutor.shutdown()
  }

  private fun onStartCamera() {
    checkPermissions()
    val surface = previewSurface
    if (surface == null) {
      Toast.makeText(this, "Something went wrong - surface is not ready", Toast.LENGTH_SHORT).show()
      return
    }
    viewModel.startCamera(surface)
  }

  private fun onStopCamera() {
    viewModel.stopCamera()
    detectedItemsState.value = emptyList()
  }

  private fun onExit() {
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
          Text("AI Detected")
          Text(
              if (detectedItems.isEmpty()) "Scanning..."
              else detectedItems.joinToString(", ") { "${it.label} (${(it.score * 100).toInt()}%)" }
          )
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
                textureViewRef = this
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

                      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        val bmp = textureViewRef?.bitmap ?: return
                        runAiDetection(bmp)
                      }
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

            val labelText = "${item.label} ${(item.score * 100).toInt()}%"
            val textY = (top - 8f).coerceAtLeast(36f)
            drawContext.canvas.nativeCanvas.drawText(labelText, left, textY, textPaint)
          }
        }
      }
    }
  }
}

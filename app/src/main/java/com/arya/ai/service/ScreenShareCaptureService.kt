package com.arya.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arya.ai.MainActivity
import com.arya.ai.util.VisionFrameProvider
import com.arya.ai.util.VisionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Screen-share vision mode for `LiveConversationScreen` — captures a screen frame every few
 * seconds via [MediaProjection]/[android.hardware.display.VirtualDisplay] and pushes it into
 * [VisionFrameProvider] for [WakeWordService]'s live-conversation loop to pick up. Unlike the
 * camera (see `CameraFrameCapture`), this can keep running while the app is backgrounded, as
 * long as this foreground service is alive — MediaProjection isn't subject to the same
 * "foreground UI only" restriction Android puts on the camera.
 *
 * The system re-asks for MediaProjection permission every time (it deliberately can't be
 * remembered across sessions) — `LiveConversationScreen` handles that via
 * `MediaProjectionManager.createScreenCaptureIntent()` and passes the resulting
 * resultCode/data here through [EXTRA_RESULT_CODE]/[EXTRA_RESULT_DATA].
 */
class ScreenShareCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "arya_screenshare"
        const val NOTIFICATION_ID = 4202
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CAPTURE_INTERVAL_MS = 2500L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handlerThread = HandlerThread("ArysScreenShare").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        // Downscaled — a vision model describing what's on screen doesn't need full res,
        // and it keeps each frame small for the relay upload path (VisionRelay fallback).
        val width = (metrics.widthPixels / 2).coerceAtLeast(1)
        val height = (metrics.heightPixels / 2).coerceAtLeast(1)
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ArysScreenShare", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        captureJob?.cancel()
        captureJob = scope.launch {
            while (isActive) {
                delay(CAPTURE_INTERVAL_MS)
                captureFrame(width, height)
            }
        }
    }

    private fun captureFrame(width: Int, height: Int) {
        val reader = imageReader ?: return
        try {
            val image = reader.acquireLatestImage() ?: return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            image.close()
            val cropped = if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, width, height)
            VisionFrameProvider.update(cropped, VisionSource.SCREEN)
        } catch (e: Exception) {
            // skip this frame — the next one in ~CAPTURE_INTERVAL_MS will be tried instead
        }
    }

    override fun onDestroy() {
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (VisionFrameProvider.source == VisionSource.SCREEN) VisionFrameProvider.clear()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Arya — screen dekh rahi hai")
        .setContentText("Live conversation me screen-share vision chalu hai")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Live screen-share vision", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

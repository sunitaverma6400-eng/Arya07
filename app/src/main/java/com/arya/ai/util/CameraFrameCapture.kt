package com.arya.ai.util

import android.content.Context
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Drives the camera preview + periodic still captures for `LiveConversationScreen`'s
 * camera-vision toggle. Camera access only works while this is bound to a foregrounded
 * lifecycle (Android doesn't allow background camera access) — [start] should be called from
 * the screen's `DisposableEffect`/`onResume`, [stop] on dispose/pause. Each captured frame is
 * pushed into [VisionFrameProvider] for [com.arya.ai.service.WakeWordService] to pick up.
 */
class CameraFrameCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var imageCapture: ImageCapture? = null
    private var captureJob: Job? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var intervalMs: Long = 2500
    private var currentSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    /** True while bound to the front camera — [LiveConversationScreen] uses this to show the
     *  right icon on its flip-camera button. */
    var isFrontFacing: Boolean = false
        private set

    /** Binds the camera and starts taking a frame every [intervalMs] until [stop] is called. */
    fun start(intervalMs: Long = 2500) {
        this.intervalMs = intervalMs
        bind(currentSelector)
        captureJob?.cancel()
        captureJob = lifecycleOwner.lifecycleScope.launch {
            // Bug fix (see chat history): this used to `delay(intervalMs)` BEFORE the very
            // first capture, so [VisionFrameProvider] had no frame at all for the first 2.5s
            // after the camera turned on — asking a vision question right after enabling the
            // camera got answered blind. A short warm-up delay (camera binding above is async
            // and needs a moment) is still needed before the very first capture, but it's much
            // shorter than a full interval.
            delay(800)
            captureFrame()
            while (isActive) {
                delay(this@CameraFrameCapture.intervalMs)
                captureFrame()
            }
        }
    }

    /** Switches between front and back camera without restarting the periodic-capture loop
     *  (unlike calling [stop] + [start] again, which would also reset the interval timer). */
    fun switchLens() {
        isFrontFacing = !isFrontFacing
        currentSelector = if (isFrontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        bind(currentSelector)
    }

    private fun bind(selector: CameraSelector) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            } catch (e: Exception) {
                // Some devices don't have a front camera — silently keep whatever was bound
                // before rather than crashing; isFrontFacing already reflects the attempt.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun captureFrame() {
        val capture = imageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) VisionFrameProvider.update(bitmap, VisionSource.CAMERA)
                } catch (e: Exception) {
                    // skip this frame — the next one in ~intervalMs will be tried instead
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                // skip this frame
            }
        })
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (e: Exception) {
            // nothing more to clean up
        }
        if (VisionFrameProvider.source == VisionSource.CAMERA) VisionFrameProvider.clear()
    }
}

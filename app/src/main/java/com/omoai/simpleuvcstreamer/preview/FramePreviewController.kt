package com.omoai.simpleuvcstreamer.preview

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.ImageView
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional UI preview only. Default off.
 * Main pipeline stays raw MJPEG (no decode) for low-latency HTTP push later.
 * When enabled: poll latest JPEG copy from native → BitmapFactory → ImageView.
 */
class FramePreviewController(
    private val imageView: ImageView,
    private val isStreaming: () -> Boolean
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val previewEnabled = AtomicBoolean(false)
    private val decoding = AtomicBoolean(false)

    private val pollRunnable = object : Runnable {
        override fun run() {
            val handler = workerHandler ?: return
            if (!previewEnabled.get()) return

            if (isStreaming() && !decoding.get()) {
                // Take-and-clear: at most one pending JPEG; drop older frames under load.
                val jpeg = UvcNative.nativeTakeLatestFrame()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    decoding.set(true)
                    try {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                        if (bmp != null) {
                            mainHandler.post {
                                if (previewEnabled.get()) {
                                    val old = (imageView.drawable as? BitmapDrawable)?.bitmap
                                    imageView.setImageBitmap(bmp)
                                    if (old != null && old !== bmp && !old.isRecycled) {
                                        old.recycle()
                                    }
                                } else {
                                    bmp.recycle()
                                }
                            }
                        }
                    } finally {
                        decoding.set(false)
                    }
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (previewEnabled.get() == enabled) return
        previewEnabled.set(enabled)
        // Native only copies JPEG into the preview slot when this is true.
        UvcNative.nativeSetPreviewEnabled(enabled)
        if (enabled) {
            ensureWorker()
            workerHandler?.removeCallbacks(pollRunnable)
            workerHandler?.post(pollRunnable)
        } else {
            workerHandler?.removeCallbacks(pollRunnable)
            mainHandler.post { imageView.setImageDrawable(null) }
        }
    }

    fun isEnabled(): Boolean = previewEnabled.get()

    fun release() {
        previewEnabled.set(false)
        UvcNative.nativeSetPreviewEnabled(false)
        workerHandler?.removeCallbacks(pollRunnable)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        mainHandler.post { imageView.setImageDrawable(null) }
    }

    private fun ensureWorker() {
        if (workerThread != null) return
        val thread = HandlerThread("UvcPreviewDecode").also { it.start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 33L
    }
}

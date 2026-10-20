package com.omoai.simpleuvcstreamer.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional on-device preview. Default off; the main pipeline stays JPEG for HTTP.
 *
 * Design notes (both matter for long sessions):
 *  - Exactly ONE long-lived poll thread. It is started once and only idles while
 *    the preview is off, so the poller can never be duplicated or accumulate over
 *    time (the previous handler-reposting loop could, which made long runs slower
 *    and slower).
 *  - At most ONE decoded frame waits for the UI thread. Older ones are recycled
 *    immediately, so the main thread and the heap can never build a backlog even
 *    if the UI is momentarily busy.
 *
 * @param onRenderStateChanged true once a frame is on screen, false while waiting.
 */
class FramePreviewController(
    private val imageView: ImageView,
    private val onRenderStateChanged: (Boolean) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewEnabled = AtomicBoolean(false)
    private val decoding = AtomicBoolean(false)

    /** The single decoded frame waiting for the UI thread (older ones dropped). */
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private val renderScheduled = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var haveFrame = false

    /** Consecutive polls that came back empty, used to self-heal a lost flag. */
    private var emptyPolls = 0

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        val thread = Thread({ pollLoop() }, "UvcPreviewDecode")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    private fun pollLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (!previewEnabled.get()) {
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }
                pollOnce()
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                FileLogger.log("preview poll threw: ${t.message}")
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun pollOnce() {
        if (decoding.get()) return

        // Native owns streaming truth and returns null when there is no frame yet.
        val jpeg = try {
            UvcNative.nativeTakeLatestFrame()
        } catch (t: Throwable) {
            FileLogger.log("preview: takeLatestFrame failed: ${t.message}")
            return
        }

        if (jpeg == null || jpeg.isEmpty()) {
            emptyPolls++
            if (emptyPolls % REASSERT_AFTER_POLLS == 0) {
                FileLogger.log(
                    "preview: still no frame after " +
                        "${emptyPolls * POLL_INTERVAL_MS / 1000}s, re-asserting native preview",
                )
                UvcNative.nativeSetPreviewEnabled(true)
            }
            return
        }

        emptyPolls = 0
        decoding.set(true)
        try {
            val bmp = decodeScaled(jpeg)
            if (bmp == null) {
                FileLogger.log("preview: decode returned null (${jpeg.size}B)")
            } else {
                postFrame(bmp)
            }
        } catch (t: Throwable) {
            FileLogger.log("preview: decode threw: ${t.message}")
        } finally {
            decoding.set(false)
        }
    }

    /**
     * Decode with a power-of-two sample size so a 4K MJPEG frame cannot allocate
     * a huge bitmap on every poll (this used to be able to stall a 4K camera).
     */
    private fun decodeScaled(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest > 0 && longest / sample > MAX_DECODE_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sample
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private fun postFrame(bmp: Bitmap) {
        if (!previewEnabled.get()) {
            bmp.recycle()
            return
        }
        pendingFrame.getAndSet(bmp)?.let { if (!it.isRecycled) it.recycle() }
        if (renderScheduled.compareAndSet(false, true)) {
            mainHandler.post { drainFrames() }
        }
    }

    private fun drainFrames() {
        while (true) {
            val bmp = pendingFrame.getAndSet(null) ?: break
            show(bmp)
        }
        renderScheduled.set(false)
        // A frame may have landed just as we were finishing; make sure one more
        // pass runs so the newest frame is never left waiting.
        if (pendingFrame.get() != null && renderScheduled.compareAndSet(false, true)) {
            mainHandler.post { drainFrames() }
        }
    }

    private fun show(bmp: Bitmap) {
        if (!previewEnabled.get()) {
            bmp.recycle()
            return
        }
        val old = (imageView.drawable as? BitmapDrawable)?.bitmap
        imageView.setImageBitmap(bmp)
        if (old != null && old !== bmp && !old.isRecycled) {
            old.recycle()
        }
        if (!haveFrame) {
            haveFrame = true
            FileLogger.log("preview: first frame on screen")
            onRenderStateChanged(true)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (previewEnabled.get() == enabled) {
            if (enabled) UvcNative.nativeSetPreviewEnabled(true)
            return
        }
        previewEnabled.set(enabled)
        if (enabled) {
            FileLogger.log("preview: enabled")
            emptyPolls = 0
        } else {
            FileLogger.log("preview: disabled")
        }
        // Native only copies JPEG into the preview slot when this is true.
        UvcNative.nativeSetPreviewEnabled(enabled)
        if (enabled) {
            markWaiting()
            ensureWorker()
        } else {
            clearFrame()
        }
    }

    fun clearFrame() {
        markWaiting()
        pendingFrame.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        mainHandler.post {
            val old = (imageView.drawable as? BitmapDrawable)?.bitmap
            imageView.setImageDrawable(null)
            if (old != null && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    /** Re-assert native flag after USB close/reopen (close must not leave preview stuck). */
    fun syncNative() {
        if (!previewEnabled.get()) return
        UvcNative.nativeSetPreviewEnabled(true)
        ensureWorker()
    }

    fun isEnabled(): Boolean = previewEnabled.get()

    fun hasFrame(): Boolean = haveFrame

    fun release() {
        previewEnabled.set(false)
        UvcNative.nativeSetPreviewEnabled(false)
        worker?.interrupt()
        worker = null
        pendingFrame.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
        haveFrame = false
        mainHandler.post {
            val old = (imageView.drawable as? BitmapDrawable)?.bitmap
            imageView.setImageDrawable(null)
            if (old != null && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    private fun markWaiting() {
        val changed = haveFrame
        haveFrame = false
        if (changed) {
            mainHandler.post { onRenderStateChanged(false) }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 33L

        /** Sleep while the preview is switched off. */
        private const val IDLE_SLEEP_MS = 100L

        /** Longest side of the decoded preview bitmap. */
        private const val MAX_DECODE_EDGE = 1280

        /** ~3s without a frame before we re-assert the native preview flag. */
        private const val REASSERT_AFTER_POLLS = 90
    }
}

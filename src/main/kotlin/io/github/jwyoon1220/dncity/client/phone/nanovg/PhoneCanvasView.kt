package io.github.jwyoon1220.dncity.client.phone.nanovg

import icyllis.modernui.core.Context
import icyllis.modernui.graphics.Bitmap
import icyllis.modernui.graphics.Canvas
import icyllis.modernui.graphics.Image
import icyllis.modernui.graphics.Paint
import icyllis.modernui.view.MotionEvent
import icyllis.modernui.view.View

/**
 * ModernUI [View] that displays [PhoneNanoVgSurface]'s latest NanoVG-rendered frame and forwards
 * taps as [PhoneAction]s. Runs entirely on ModernUI's own UI thread ([onDraw]/[onTouchEvent]) --
 * it never touches NanoVG/raw GL itself, only the thread-safe (volatile) frame/hit-rect state
 * [PhoneNanoVgSurface] publishes from Minecraft's render thread. See that class's doc comment for
 * why the two are kept on separate threads like this.
 */
class PhoneCanvasView(context: Context) : View(context) {
    /** Set by [io.github.jwyoon1220.dncity.client.phone.PhoneFragment] to route taps to the domain layer. */
    var onAction: (PhoneAction) -> Unit = {}

    private val paint = Paint()
    private var bitmap: Bitmap? = null
    private var image: Image? = null
    private var lastAppliedFrame: IntArray? = null

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val frame = PhoneNanoVgSurface.latestFrame
        if (frame != null && frame !== lastAppliedFrame) {
            var bmp = bitmap
            if (bmp == null) {
                bmp = Bitmap.createBitmap(PhoneNanoVgSurface.PANEL_WIDTH, PhoneNanoVgSurface.PANEL_HEIGHT, Bitmap.Format.RGBA_8888)
                bitmap = bmp
            }
            bmp.setPixels(frame, 0, PhoneNanoVgSurface.PANEL_WIDTH, 0, 0, PhoneNanoVgSurface.PANEL_WIDTH, PhoneNanoVgSurface.PANEL_HEIGHT)
            image?.close()
            image = Image.createTextureFromBitmap(bmp)
            lastAppliedFrame = frame
        }
        image?.let { canvas.drawImage(it, 0f, 0f, paint) }
        // Keep redrawing while the phone is open so the clock/call-banner state (drawn fresh into
        // every published NanoVG frame) keeps showing up, not just on the first frame.
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            PhoneNanoVgSurface.hitTest(event.x, event.y)?.let { onAction(it) }
        }
        return true
    }
}

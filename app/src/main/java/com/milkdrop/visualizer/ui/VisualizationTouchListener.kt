package com.milkdrop.visualizer.ui

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/** Surface-only gestures; Activity.dispatchTouchEvent still observes every shader pointer event. */
internal class VisualizationTouchListener(
    context: Context,
    private val canSwipe: () -> Boolean,
    private val onSwipe: (Swipe) -> Unit,
) : View.OnTouchListener {
    enum class Swipe { LEFT, RIGHT, UP, DOWN }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeDistance = max(48f * context.resources.displayMetrics.density, touchSlop * 4f)
    private var startX = 0f
    private var startY = 0f
    private var maxTravel = 0f
    private var tracking = false
    private var multiplePointers = false
    private var shortcutsAllowed = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val isTouch = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        val isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
        if (!isTouch && !isMouse) return false

        // A mouse drag used to fall through to View.performClick(), which toggled the controls
        // when the primary button was released. Treat it as the same stationary-click gesture as
        // touch, but do not map mouse drags to the touchscreen shortcut swipes.
        if (isMouse && event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.actionButton != MotionEvent.BUTTON_PRIMARY &&
            event.buttonState and MotionEvent.BUTTON_PRIMARY == 0
        ) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                maxTravel = 0f
                multiplePointers = event.pointerCount != 1
                shortcutsAllowed = !isMouse && canSwipe()
                tracking = true
                view.isPressed = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiplePointers = true
                view.isPressed = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                maxTravel = max(maxTravel, max(abs(event.x - startX), abs(event.y - startY)))
                if (maxTravel > touchSlop) view.isPressed = false
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                view.isPressed = false
                if (!multiplePointers) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    maxTravel = max(maxTravel, max(abs(dx), abs(dy)))
                    val swipe = when {
                        abs(dx) >= swipeDistance && abs(dx) > abs(dy) * 1.25f ->
                            if (dx < 0f) Swipe.LEFT else Swipe.RIGHT
                        abs(dy) >= swipeDistance && abs(dy) > abs(dx) * 1.25f ->
                            if (dy < 0f) Swipe.UP else Swipe.DOWN
                        else -> null
                    }
                    if (swipe != null && shortcutsAllowed && canSwipe()) onSwipe(swipe)
                    else if (maxTravel <= touchSlop) view.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                view.isPressed = false
            }
        }
        return true
    }
}

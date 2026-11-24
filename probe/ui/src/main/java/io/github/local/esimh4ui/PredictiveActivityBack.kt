package io.github.local.esimh4ui

import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback

/** Activity adaptation of InstallerX Revived's gesture scale and directional exit. */
internal fun installPredictiveActivityBack(activity: ThemedActivity) {
    val callback = object : OnBackPressedCallback(true) {
        private val easing = PathInterpolator(0.1f, 0.1f, 0f, 1f)
        private var edge = BackEventCompat.EDGE_LEFT
        private var committing = false
        private val page: View get() = activity.findViewById(android.R.id.content)

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            page.animate().cancel()
            edge = backEvent.swipeEdge
            page.pivotX = page.width * if (edge == BackEventCompat.EDGE_LEFT) 0.8f else 0.2f
            val location = IntArray(2)
            page.getLocationOnScreen(location)
            page.pivotY = (backEvent.touchY - location[1]).coerceIn(page.height * 0.1f, page.height * 0.9f)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (committing) return
            val progress = easing.getInterpolation(backEvent.progress.coerceIn(0f, 1f))
            if (ThemeConfig.predictiveBack == 1) {
                page.scaleX = 1f - 0.15f * progress
                page.scaleY = page.scaleX
            } else {
                page.translationX = direction() * page.width * 0.25f * progress
            }
        }

        private fun direction(): Float = when (ThemeConfig.backExitDirection) {
            1 -> 1f
            2 -> -1f
            else -> if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
        }

        override fun handleOnBackCancelled() {
            page.animate().scaleX(1f).scaleY(1f).translationX(0f)
                .setDuration(200).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        }

        override fun handleOnBackPressed() {
            if (committing) return
            committing = true
            page.animate().translationX(direction() * page.width).setDuration(200)
                .withEndAction {
                    activity.finish()
                    activity.overridePendingTransition(0, 0)
                }.start()
        }
    }
    // Root activities retain the system back-to-home animation. Dialogs and Compose handlers
    // register later and therefore take precedence over this activity callback.
    if (!activity.isTaskRoot && ThemeConfig.predictiveBack != 0) {
        activity.onBackPressedDispatcher.addCallback(activity, callback)
    }
}

object PredictiveActivityBack {
    @JvmStatic fun install(activity: ThemedActivity) = installPredictiveActivityBack(activity)
}

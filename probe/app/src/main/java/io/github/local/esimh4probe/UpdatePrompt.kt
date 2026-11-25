@file:JvmName("UpdatePrompt")

package io.github.local.esimh4probe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.github.local.esimh4ui.AppMiuixTheme
import io.github.local.esimh4ui.UiKit
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet

fun show(activity: Activity, title: String, details: String, download: Runnable) {
    if (activity.isFinishing || activity.isDestroyed) return
    val open = {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(details)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(activity, R.string.snack_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val label = activity.getString(R.string.update_details, "GitHub")
    if (!UiKit.isMiuix()) {
        val message = SpannableString(label)
        val start = label.indexOf("GitHub")
        message.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) { open() }
        }, start, start + 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ -> download.run() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        return
    }
    val parent = activity.window.decorView as ViewGroup
    val view = ComposeView(activity)
    val visible = mutableStateOf(true)
    val dismiss = {
        visible.value = false
        parent.post { parent.removeView(view) }
        Unit
    }
    view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    view.setContent {
        val controller = androidx.compose.runtime.remember {
            top.yukonga.miuix.kmp.theme.ThemeController(
                if (io.github.local.esimh4ui.ThemeConfig.dynamicColor)
                    top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetSystem
                else top.yukonga.miuix.kmp.theme.ColorSchemeMode.System,
            )
        }
        AppMiuixTheme(controller = controller) {
            WindowBottomSheet(show = visible.value, title = title, onDismissRequest = dismiss) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    TextButton(text = label, onClick = { open() }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = activity.getString(R.string.update_download),
                            onClick = { dismiss(); download.run() },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = activity.getString(android.R.string.cancel),
                            onClick = dismiss,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    parent.addView(view, ViewGroup.LayoutParams(0, 0))
}

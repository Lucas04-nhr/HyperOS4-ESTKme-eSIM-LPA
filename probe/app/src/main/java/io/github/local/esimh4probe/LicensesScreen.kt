@file:JvmName("LicensesScreen")

package io.github.local.esimh4probe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.local.esimh4ui.ThemeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shows the open source licenses page in the Miuix style, laid out like the Miuix example app's
 * third party licenses page: a glass top bar and one card per library ("1.13.0, Apache-2.0").
 * Tapping a library opens its web page; tapping this project shows its license text on the same
 * page, and back returns to the list.
 * [LicensesActivity] calls this; the Material 3 style uses its View page.
 */
fun setLicensesContent(activity: LicensesActivity) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        MiuixTheme(controller = controller) {
            LicensesPage(activity)
        }
    }
}

@Composable
private fun LicensesPage(activity: LicensesActivity) {
    val data by produceState<LicenseData?>(null) {
        value = withContext(Dispatchers.IO) { LicenseData.load(activity) }
    }
    // True while this project's license text is shown
    var showProject by rememberSaveable { mutableStateOf(false) }
    // Kept here, so returning from the license text shows the list where it was
    val listState = rememberLazyListState()
    val loaded = data
    val entry = if (showProject) loaded?.project else null
    BackHandler(enabled = entry != null) { showProject = false }

    val openFailed = stringResource(R.string.snack_open_link_failed)
    val open: (String) -> Unit = { url ->
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, openFailed, Toast.LENGTH_SHORT).show()
        }
    }

    PageScaffold(
        title = entry?.title ?: stringResource(R.string.licenses_title),
        onBack = { if (entry != null) showProject = false else activity.finish() },
    ) { padding, scrollModifier ->
        if (entry != null) {
            LazyColumn(modifier = scrollModifier, contentPadding = padding) {
                item(key = "license") {
                    LicenseCard {
                        Text(
                            text = entry.text ?: stringResource(R.string.licenses_no_text, entry.licenses),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                        )
                    }
                }
                val url = entry.url
                if (url != null) {
                    item(key = "web") {
                        LicenseCard {
                            ArrowPreference(
                                title = stringResource(R.string.licenses_open_web),
                                summary = url,
                                onClick = { open(url) },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = scrollModifier, contentPadding = padding) {
                val list = loaded ?: return@LazyColumn
                item(key = "projectTitle") { SmallTitle(text = stringResource(R.string.licenses_section_project)) }
                item(key = "project") {
                    LicenseCard {
                        ArrowPreference(
                            title = list.project.title,
                            summary = list.project.summary,
                            onClick = { showProject = true },
                        )
                    }
                }
                item(key = "librariesTitle") { SmallTitle(text = stringResource(R.string.licenses_section_libraries)) }
                // One card per library, like the Miuix example
                itemsIndexed(list.libraries, key = { index, _ -> "lib$index" }) { _, lib ->
                    val url = lib.url
                    LicenseCard {
                        ArrowPreference(
                            title = lib.title,
                            summary = lib.summary,
                            enabled = url != null,
                            onClick = { if (url != null) open(url) },
                        )
                    }
                }
            }
        }
    }
}

/** Miuix scaffold with a glass top bar: a small centered title and a glass back button. */
@Composable
private fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val scrollBehavior = MiuixScrollBehavior()
    // What the glass bar samples: the page behind it
    val backdrop = rememberLayerBackdrop()
    Scaffold(
        containerColor = colors.surface,
        topBar = {
            GlassTopAppBar(
                title = title,
                expandable = false,
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Glass.ChevronBackward,
                            contentDescription = stringResource(io.github.local.esimh4ui.R.string.ui_back),
                            modifier = Modifier.size(28.dp),
                            tint = colors.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.surface),
        ) {
            content(
                PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 12.dp,
                ),
                Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            )
        }
    }
}

@Composable
private fun LicenseCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

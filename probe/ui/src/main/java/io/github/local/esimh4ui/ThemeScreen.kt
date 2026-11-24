@file:JvmName("ThemeScreen")

package io.github.local.esimh4ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shows the Miuix theme page, written with the Miuix Compose components. [ThemeActivity] calls
 * this in the Miuix style; the Material 3 style still uses the View page.
 *
 * The page reads the settings as state, so it follows every change without being recreated.
 * Only switching to Material 3 recreates it, and the platform recreates it when the effective
 * dark mode or the language changes.
 */
fun setThemeContent(activity: ThemeActivity, showLanguage: Boolean) {
    activity.setContent {
        var dynamicColor by remember { mutableStateOf(ThemeConfig.dynamicColor) }
        // The app applies its dark mode setting through the platform (UiModeManager), so the
        // "System" modes follow it.
        val controller = remember(dynamicColor) {
            ThemeController(if (dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        MiuixTheme(controller = controller) {
            ThemePage(
                activity = activity,
                showLanguage = showLanguage,
                dynamicColor = dynamicColor,
                onDynamicColorChange = {
                    ThemeConfig.setDynamicColor(activity, it)
                    dynamicColor = it
                },
            )
        }
    }
}

@Composable
private fun ThemePage(
    activity: ThemeActivity,
    showLanguage: Boolean,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()

    var barGlass by remember { mutableStateOf(ThemeConfig.barGlass) }
    var progressiveBlur by remember { mutableStateOf(ThemeConfig.progressiveBlur) }
    var barFloating by remember { mutableStateOf(ThemeConfig.barFloating) }
    var liquidGlass by remember { mutableStateOf(ThemeConfig.liquidGlass) }
    var nightMode by remember { mutableIntStateOf(ThemeConfig.nightMode) }
    var language by remember { mutableIntStateOf(LanguageSetting.current(activity)) }
    var bgBlur by remember { mutableFloatStateOf(ThemeConfig.bgBlur.toFloat()) }
    var bgDim by remember { mutableFloatStateOf(ThemeConfig.bgDim.toFloat()) }
    var cardAlpha by remember { mutableFloatStateOf(ThemeConfig.cardAlpha.toFloat()) }
    var importing by remember { mutableStateOf(false) }

    // Bumped after the image is replaced or removed, so it is decoded again.
    var backgroundVersion by remember { mutableIntStateOf(0) }
    val background = remember(backgroundVersion) { ThemeConfig.loadBackground(activity)?.asImageBitmap() }
    val hasBackground = background != null

    val pickFailed = stringResource(R.string.theme_bg_pick_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ThemeConfig.importBackground(activity.applicationContext, uri) }
            importing = false
            if (ok) {
                backgroundVersion++
            } else {
                Toast.makeText(activity, pickFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickImage = {
        try {
            // The system photo picker, or the document picker where it is missing; no permission needed.
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Throwable) {
            Toast.makeText(activity, pickFailed, Toast.LENGTH_SHORT).show()
        }
    }

    // What the glass top bar and the glass popups sample: page color, background image, list.
    val backdrop = rememberLayerBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val title = stringResource(R.string.theme_title)
    val back = stringResource(R.string.ui_back)

    val cardColor = if (hasBackground) colors.surfaceContainer.copy(alpha = cardAlpha / 100f) else colors.surfaceContainer

    Scaffold(
        containerColor = colors.surface,
        topBar = {
            if (barGlass) {
                // Glass bar: transparent at rest; once the page scrolls, the back button gets its
                // glass pill and the band behind the bar fades in (a progressive blur if enabled).
                GlassTopAppBar(
                    title = title,
                    backdrop = backdrop,
                    isContentScrolled = listState.canScrollBackward,
                    scrollBehavior = scrollBehavior,
                    progressiveBlur = progressiveBlur,
                    navigationIcon = {
                        GlassIconButton(onClick = { activity.finish() }) {
                            Icon(
                                imageVector = MiuixIcons.Glass.ChevronBackward,
                                contentDescription = back,
                                modifier = Modifier.size(28.dp),
                                tint = colors.onSurface,
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = title,
                    color = colors.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { activity.finish() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = back,
                                tint = colors.onBackground,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.surface),
        ) {
            if (background != null) {
                Image(
                    bitmap = background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (bgBlur >= 1f) {
                                Modifier.blur(bgBlur.roundToInt().dp, BlurredEdgeTreatment.Rectangle)
                            } else {
                                Modifier
                            },
                        ),
                )
                // Dim toward the page color, so text keeps its contrast in both themes.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.surface.copy(alpha = bgDim.roundToInt().coerceIn(0, 90) / 100f)),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 12.dp,
                ),
            ) {
                // Appearance
                item(key = "appearanceTitle") { SmallTitle(text = stringResource(R.string.theme_section_appearance)) }
                item(key = "appearance") {
                    SettingsCard(cardColor) {
                        if (showLanguage) {
                            GlassDropdownPreference(
                                title = stringResource(R.string.lang_title),
                                items = LanguageSetting.labels(activity).toList(),
                                selectedIndex = language,
                                backdrop = backdrop,
                                startAction = { RowIcon(R.drawable.ic_language_24) },
                                onSelectedIndexChange = {
                                    // The platform recreates the activity in the new language.
                                    language = it
                                    LanguageSetting.apply(activity, it)
                                },
                            )
                        }
                        GlassDropdownPreference(
                            title = stringResource(R.string.theme_style),
                            summary = stringResource(R.string.theme_style_summary),
                            items = listOf(
                                stringResource(R.string.theme_style_material3),
                                stringResource(R.string.theme_style_miuix),
                            ),
                            selectedIndex = 1,
                            backdrop = backdrop,
                            startAction = { RowIcon(R.drawable.ic_style_24) },
                            onSelectedIndexChange = {
                                if (it == 0) {
                                    ThemeConfig.setStyle(activity, UiKit.Style.MATERIAL3)
                                    activity.recreate()
                                }
                            },
                        )
                        GlassDropdownPreference(
                            title = stringResource(R.string.theme_night),
                            items = listOf(
                                stringResource(R.string.theme_night_system),
                                stringResource(R.string.theme_night_light),
                                stringResource(R.string.theme_night_dark),
                            ),
                            selectedIndex = nightMode,
                            backdrop = backdrop,
                            startAction = { RowIcon(R.drawable.ic_dark_mode_24) },
                            onSelectedIndexChange = {
                                // The platform recreates the activity if the effective mode changes.
                                nightMode = it
                                ThemeConfig.setNightMode(activity, it)
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.theme_dynamic_color),
                            summary = stringResource(R.string.theme_dynamic_color_summary_miuix),
                            checked = dynamicColor,
                            startAction = { RowIcon(R.drawable.ic_dynamic_color_24) },
                            onCheckedChange = onDynamicColorChange,
                        )
                    }
                }

                // Top and bottom bars
                item(key = "barsTitle") { SmallTitle(text = stringResource(R.string.theme_section_bottom_bar)) }
                item(key = "bars") {
                    SettingsCard(cardColor) {
                        SwitchPreference(
                            title = stringResource(R.string.theme_bar_glass),
                            summary = stringResource(R.string.theme_bar_glass_summary),
                            checked = barGlass,
                            startAction = { RowIcon(R.drawable.ic_bottom_bar_24) },
                            onCheckedChange = {
                                ThemeConfig.setBarGlass(activity, it)
                                barGlass = it
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.theme_progressive_blur),
                            summary = stringResource(
                                if (barGlass) R.string.theme_progressive_blur_summary else R.string.theme_needs_glass,
                            ),
                            checked = progressiveBlur && barGlass,
                            enabled = barGlass,
                            startAction = { RowIcon(R.drawable.ic_blur_24) },
                            onCheckedChange = {
                                ThemeConfig.setProgressiveBlur(activity, it)
                                progressiveBlur = it
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.theme_bar_floating),
                            summary = stringResource(R.string.theme_bar_floating_summary),
                            checked = barFloating,
                            startAction = { RowIcon(R.drawable.ic_floating_bar_24) },
                            onCheckedChange = {
                                ThemeConfig.setBarFloating(activity, it)
                                barFloating = it
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.theme_liquid_glass),
                            summary = stringResource(
                                if (barFloating) R.string.theme_liquid_glass_summary else R.string.theme_liquid_glass_needs_floating,
                            ),
                            checked = liquidGlass && barFloating,
                            enabled = barFloating,
                            startAction = { RowIcon(R.drawable.ic_liquid_glass_24) },
                            onCheckedChange = {
                                ThemeConfig.setLiquidGlass(activity, it)
                                liquidGlass = it
                            },
                        )
                    }
                }

                // Background
                item(key = "backgroundTitle") { SmallTitle(text = stringResource(R.string.theme_section_background)) }
                item(key = "background") {
                    SettingsCard(cardColor) {
                        ArrowPreference(
                            title = stringResource(if (hasBackground) R.string.theme_bg_image_replace else R.string.theme_bg_image),
                            summary = stringResource(R.string.theme_bg_image_summary),
                            enabled = !importing,
                            startAction = { RowIcon(R.drawable.ic_image_24) },
                            endActions = {
                                if (importing) InfiniteProgressIndicator(color = colors.primary)
                            },
                            onClick = pickImage,
                        )
                        if (hasBackground) {
                            ArrowPreference(
                                title = stringResource(R.string.theme_bg_image_remove),
                                enabled = !importing,
                                onClick = {
                                    ThemeConfig.clearBackground(activity)
                                    backgroundVersion++
                                },
                            )
                        }
                        SliderPreference(
                            title = stringResource(R.string.theme_bg_blur),
                            value = bgBlur,
                            valueText = "${bgBlur.roundToInt()} dp",
                            valueRange = 0f..25f,
                            steps = 24,
                            enabled = hasBackground,
                            startAction = { RowIcon(R.drawable.ic_blur_24) },
                            onValueChange = { bgBlur = it },
                            onValueChangeFinished = { ThemeConfig.setBgBlur(activity, bgBlur.roundToInt()) },
                        )
                        SliderPreference(
                            title = stringResource(R.string.theme_bg_dim),
                            value = bgDim,
                            valueText = "${bgDim.roundToInt()}%",
                            valueRange = 0f..80f,
                            steps = 79,
                            enabled = hasBackground,
                            startAction = { RowIcon(R.drawable.ic_brightness_24) },
                            onValueChange = { bgDim = it },
                            onValueChangeFinished = { ThemeConfig.setBgDim(activity, bgDim.roundToInt()) },
                        )
                        SliderPreference(
                            title = stringResource(R.string.theme_card_alpha),
                            value = cardAlpha,
                            valueText = "${cardAlpha.roundToInt()}%",
                            valueRange = 40f..100f,
                            steps = 59,
                            enabled = hasBackground,
                            startAction = { RowIcon(R.drawable.ic_opacity_24) },
                            onValueChange = { cardAlpha = it },
                            onValueChangeFinished = { ThemeConfig.setCardAlpha(activity, cardAlpha.roundToInt()) },
                        )
                    }
                }
                if (!hasBackground) {
                    item(key = "backgroundHint") { SmallTitle(text = stringResource(R.string.theme_bg_hint)) }
                }
            }
        }
    }
}

/** A group of rows on one card, with the Miuix page margins. */
@Composable
private fun SettingsCard(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = color),
        content = content,
    )
}

/** The leading icon of a settings row (SukiSU style). */
@Composable
private fun RowIcon(id: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(id),
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

@file:JvmName("MainScreen")

package io.github.local.esimh4probe

import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.local.esimh4ui.BarItem
import io.github.local.esimh4ui.LanguageSetting
import io.github.local.esimh4ui.MiuixBottomBar
import io.github.local.esimh4ui.MiuixCard
import io.github.local.esimh4ui.MiuixPage
import io.github.local.esimh4ui.MiuixRowIcon
import io.github.local.esimh4ui.MiuixStatusCard
import io.github.local.esimh4ui.PageBackground
import io.github.local.esimh4ui.StatusMode
import io.github.local.esimh4ui.ThemeConfig
import io.github.local.esimh4ui.UiKit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import io.github.local.esimh4ui.R as UiR

/**
 * The main screen in the Miuix style, written with the Miuix Compose components: home, the
 * optional Test tab and settings in a pager, with the Miuix bottom bar and, on each page, a
 * large-title top bar and Miuix's pull to refresh. [MainActivity] calls this and keeps the logic;
 * the Material 3 style uses its View pages. Dialogs (diagnostics, root) are the shared
 * View dialogs of [UiKit], which follow the Miuix style too.
 */
fun setMainContent(activity: MainActivity, state: MainUiState) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        MiuixTheme(controller = controller) {
            MainContent(activity, state)
        }
    }
}

private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int, val pageId: Int) {
    HOME(R.string.tab_home, R.drawable.ic_home_24, MainActivity.PAGE_HOME),
    TESTER(R.string.tab_tester, R.drawable.ic_science_24, MainActivity.PAGE_TESTER),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings_24, MainActivity.PAGE_SETTINGS),
}

@Composable
private fun MainContent(activity: MainActivity, state: MainUiState) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val tabs = if (state.showTester) listOf(Tab.HOME, Tab.TESTER, Tab.SETTINGS) else listOf(Tab.HOME, Tab.SETTINGS)
    val initialIndex = remember { tabs.indexOfFirst { it.pageId == state.initialPage }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialIndex) { tabs.size }
    val current = tabs.getOrElse(pagerState.currentPage) { Tab.HOME }
    SideEffect { state.currentPage = current.pageId }

    // The Test tab appears or disappears from the settings page: stay there.
    var lastTabs by remember { mutableStateOf(tabs) }
    LaunchedEffect(tabs) {
        if (tabs != lastTabs) {
            val before = lastTabs.getOrNull(pagerState.currentPage) ?: Tab.SETTINGS
            val target = tabs.indexOf(before).takeIf { it >= 0 } ?: tabs.indexOf(Tab.SETTINGS)
            if (target != pagerState.currentPage) pagerState.scrollToPage(target)
            lastTabs = tabs
        }
    }
    val goTo: (Tab) -> Unit = { tab ->
        val index = tabs.indexOf(tab)
        if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
    }
    // Back on any tab but home returns to home.
    BackHandler(enabled = current != Tab.HOME) { goTo(Tab.HOME) }

    // What the liquid glass bottom bar samples: the pages and the background.
    val backdrop = rememberLayerBackdrop()
    Scaffold(
        containerColor = colors.surface,
        bottomBar = {
            MiuixBottomBar(
                items = tabs.map { BarItem(stringResource(it.label), it.icon) },
                selected = pagerState.currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                backdrop = backdrop,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.surface),
        ) {
            // One background behind all pages, so it stays put while the pages are swiped.
            PageBackground()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                key = { tabs[it] },
            ) { index ->
                // The pages run under the bottom bar and keep its height free at the end.
                val bottom = innerPadding.calculateBottomPadding()
                when (tabs[index]) {
                    Tab.HOME -> HomePage(activity, state, bottom, onOpenSettings = { goTo(Tab.SETTINGS) })
                    Tab.TESTER -> TesterTab(activity, state, bottom)
                    Tab.SETTINGS -> SettingsPage(activity, state, bottom, backdrop)
                }
            }
        }
    }
}

/** The small spinner in the top bar while a probe or an action runs. */
@Composable
private fun RowScope.BusyIndicator(show: Boolean) {
    if (show) {
        InfiniteProgressIndicator(
            modifier = Modifier.padding(end = 16.dp),
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Home
// -------------------------------------------------------------------------------------------------

@Composable
private fun HomePage(activity: MainActivity, state: MainUiState, bottom: Dp, onOpenSettings: () -> Unit) {
    val model = state.model
    val palette = activity.paletteColors()
    var refreshing by remember { mutableStateOf(false) }
    val refreshedText = stringResource(R.string.snack_refreshed)
    val showDiagnostics: () -> Unit = { activity.showDiagnosticsDialog() }
    MiuixPage(
        title = stringResource(R.string.app_name),
        bottomPadding = bottom,
        drawBackground = false,
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            activity.refreshDashboard(Runnable {
                refreshing = false
                activity.showToast(refreshedText)
            })
        },
        actions = { BusyIndicator(state.busy && !refreshing) },
    ) {
        item(key = "status") {
            if (model == null) {
                MiuixStatusCard(palette, StatusMode.LOADING, stringResource(R.string.status_loading), onClick = { activity.onProbeStatusTap() })
            } else if (model.bannerOk) {
                MiuixStatusCard(palette, StatusMode.OK, model.bannerTitle, model.bannerText, model.bannerTags, onClick = { activity.onProbeStatusTap() })
            } else {
                MiuixStatusCard(
                    palette = palette,
                    mode = StatusMode.ERROR,
                    title = model.bannerTitle,
                    text = model.bannerText,
                    onClick = { activity.onProbeStatusTap() },
                    actionText = model.bannerActionText,
                    onAction = when (model.bannerAction) {
                        DashboardModel.BannerAction.OPEN_SETTINGS -> onOpenSettings
                        DashboardModel.BannerAction.DIAGNOSTICS -> showDiagnostics
                        else -> null
                    },
                )
            }
        }
        val alert = model?.alert
        if (alert != null) {
            item(key = "alert") {
                MiuixCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp), color = Color(palette.errorContainer)) {
                    Row {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_error_24),
                            contentDescription = null,
                            modifier = Modifier.padding(top = 2.dp, end = 12.dp).size(20.dp),
                            tint = Color(palette.onErrorContainer),
                        )
                        Text(text = alert, color = Color(palette.onErrorContainer), fontSize = 12.8.sp)
                    }
                }
            }
        }
        item(key = "healthTitle") { SmallTitle(text = stringResource(R.string.section_health)) }
        item(key = "sim") { SimCard(activity, model, palette) }
    }
}

@Composable
private fun SimCard(activity: MainActivity, model: DashboardModel?, palette: UiKit.Palette) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(palette.secondaryContainer), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_sim_card_24),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = Color(palette.onSecondaryContainer),
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(
                    text = stringResource(R.string.sim2_title),
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = model?.sim2Meta ?: stringResource(R.string.sim2_carrier, "…"),
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            val badgeFg = if (model != null) Color(context.getColor(model.badgeFgRes)) else colors.onSurfaceVariantSummary
            val badgeBg = if (model != null) Color(context.getColor(model.badgeBgRes)) else Color(palette.tileBg)
            Text(
                text = stringResource(model?.badgeRes ?: R.string.badge_checking),
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                color = badgeFg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(Modifier.padding(top = 14.dp)) {
            HealthTile(model?.tileMount, R.string.tile_mount, palette)
            Spacer(Modifier.width(8.dp))
            HealthTile(model?.tilePhoneHook, R.string.tile_phone_hook, palette)
        }
        Row(Modifier.padding(top = 8.dp)) {
            HealthTile(model?.tileLpaHook, R.string.tile_lpa_hook, palette)
            Spacer(Modifier.width(8.dp))
            HealthTile(model?.tileEuicc, R.string.tile_euicc, palette)
        }
        TextButton(
            text = stringResource(R.string.btn_reprobe),
            onClick = { activity.triggerReprobeSim2() },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        TextButton(
            text = stringResource(R.string.btn_manage_esim),
            onClick = { activity.openNativeEsimManager() },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            enabled = model?.manageEnabled ?: false,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        TextButton(
            text = stringResource(R.string.btn_mobile_network),
            onClick = { activity.openMobileNetwork() },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    }
}

@Composable
private fun RowScope.HealthTile(tile: DashboardModel.Tile?, @StringRes label: Int, palette: UiKit.Palette) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .weight(1f)
            .background(Color(palette.tileBg), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = stringResource(label), color = colors.onSurfaceVariantSummary, fontSize = 11.5.sp)
        Text(
            text = tile?.display() ?: ("○ " + stringResource(R.string.badge_checking)),
            modifier = Modifier.padding(top = 3.dp),
            color = if (tile != null) Color(context.getColor(tile.colorRes())) else colors.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Test tab
// -------------------------------------------------------------------------------------------------

@Composable
private fun TesterTab(activity: MainActivity, state: MainUiState, bottom: Dp) {
    val tester = activity.testerPage() ?: return
    val palette = activity.paletteColors()
    val colors = MiuixTheme.colorScheme
    // Bumped by the tester whenever its status or log changed.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(tester) {
        tester.setListener { version++ }
        onDispose { tester.setListener(null) }
    }
    val status = remember(tester, version) { tester.status() }
    val log = remember(tester, version) { tester.log() }
    val logVisible = remember(tester, version) { tester.isLogVisible() }
    val code = rememberTextFieldState(TesterPage.DEFAULT_MOCK_CODE)

    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            tester.refreshStatus()
            // The status is read at once; keep the indicator visible briefly.
            delay(400)
            refreshing = false
        }
    }

    MiuixPage(
        title = stringResource(R.string.tester_title),
        bottomPadding = bottom,
        drawBackground = false,
        isRefreshing = refreshing,
        onRefresh = { refreshing = true },
        actions = { BusyIndicator(state.busy) },
    ) {
        item(key = "status") {
            when {
                status.checking -> MiuixStatusCard(palette, StatusMode.LOADING, status.title, onClick = { tester.onStatusTap() })
                status.ready -> MiuixStatusCard(palette, StatusMode.OK, status.title, status.text, status.tags, onClick = { tester.onStatusTap() })
                else -> MiuixStatusCard(palette, StatusMode.ERROR, status.title, status.text, onClick = { tester.onStatusTap() })
            }
        }
        item(key = "mockTitle") { SmallTitle(text = stringResource(R.string.tester_mock_title)) }
        item(key = "mock") {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text(
                    text = stringResource(R.string.tester_safety_note),
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 12.5.sp,
                )
                TextField(
                    state = code,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                TextButton(
                    text = stringResource(R.string.tester_reset_code),
                    onClick = {
                        code.setTextAndPlaceCursorAtEnd(TesterPage.DEFAULT_MOCK_CODE)
                        tester.logCodeReset()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                TextButton(
                    text = stringResource(R.string.tester_test1),
                    onClick = {
                        val raw = code.text.toString().trim()
                        if (raw.isEmpty()) code.setTextAndPlaceCursorAtEnd(TesterPage.DEFAULT_MOCK_CODE)
                        tester.startMockDownload(raw)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.tester_test2),
                    onClick = { tester.startLpaUriTest(code.text.toString()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                TextButton(
                    text = stringResource(R.string.tester_test3),
                    onClick = { tester.startStandardActivationTest() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
        if (logVisible) {
            item(key = "logTitle") {
                SmallTitle(text = stringResource(R.string.tester_console_title))
            }
            item(key = "log") {
                MiuixCard(insideMargin = PaddingValues(0.dp)) {
                    SelectionContainer {
                        Text(
                            text = log,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .background(Color(palette.tileBg), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            color = colors.onSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
            item(key = "clearLog") {
                TextButton(
                    text = stringResource(R.string.tester_clear_log),
                    onClick = { tester.clearLog() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Settings
// -------------------------------------------------------------------------------------------------

@Composable
private fun SettingsPage(activity: MainActivity, state: MainUiState, bottom: Dp, backdrop: LayerBackdrop) {
    var language by remember { mutableIntStateOf(LanguageSetting.current(activity)) }
    val model = state.model
    // A switch shows the new value while its change runs, and the old one again if it fails.
    val overrides = remember { mutableStateMapOf<String, Boolean>() }
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    // A new report shows the real state; keep only the values of changes still running.
    LaunchedEffect(model) {
        overrides.keys.filter { it !in pending }.forEach { overrides.remove(it) }
    }

    @Composable
    fun SettingSwitch(key: String, row: DashboardModel.Row?, @StringRes title: Int, @DrawableRes icon: Int) {
        SwitchPreference(
            checked = overrides[key] ?: row?.checked ?: false,
            onCheckedChange = { on ->
                overrides[key] = on
                pending[key] = true
                activity.requestSwitch(key, on) { ok ->
                    pending.remove(key)
                    if (!ok) overrides.remove(key)
                }
            },
            title = stringResource(title),
            summary = row?.summary ?: stringResource(R.string.status_loading_summary),
            startAction = { MiuixRowIcon(icon) },
            enabled = (row?.enabled ?: false) && pending[key] != true,
        )
    }

    MiuixPage(
        title = stringResource(R.string.tab_settings),
        bottomPadding = bottom,
        drawBackground = false,
        actions = { BusyIndicator(state.busy) },
    ) {
        item(key = "bridgeTitle") { SmallTitle(text = stringResource(R.string.section_bridge)) }
        item(key = "bridge") {
            MiuixCard {
                SettingSwitch(MainActivity.SW_MASTER, model?.master, R.string.row_master, R.drawable.ic_power_24)
                ArrowPreference(
                    title = stringResource(R.string.row_device_status),
                    summary = stringResource(R.string.row_device_status_summary),
                    startAction = { MiuixRowIcon(R.drawable.ic_sim_card_24) },
                    onClick = { activity.openDeviceStatus() },
                )
            }
        }
        item(key = "coreTitle") { SmallTitle(text = stringResource(R.string.section_core)) }
        item(key = "core") {
            MiuixCard {
                SettingSwitch(MainActivity.SW_SYSTEM_ESIM, model?.systemEsim, R.string.row_system_esim, R.drawable.ic_sync_alt_24)
                SettingSwitch(MainActivity.SW_CARRIER_BRIDGE, model?.carrierBridge, R.string.row_carrier_bridge, R.drawable.ic_verified_user_24)
                SettingSwitch(MainActivity.SW_LPA_URI_BRIDGE, model?.lpaUriBridge, R.string.row_lpa_uri, R.drawable.ic_link_24)
                SettingSwitch(MainActivity.SW_REWRITE_TEXT, model?.rewriteText, R.string.row_rewrite_text, R.drawable.ic_translate_24)
            }
        }
        item(key = "rootTitle") { SmallTitle(text = stringResource(R.string.section_root)) }
        item(key = "root") {
            MiuixCard {
                SettingSwitch(MainActivity.SW_UMOUNT_GUARD, model?.umountGuard, R.string.row_umount_guard, R.drawable.ic_shield_24)
                SettingSwitch(MainActivity.SW_ESTK_SE1, model?.estkSe1, R.string.row_estk_se1, R.drawable.ic_sync_alt_24)
            }
        }
        item(key = "personalizeTitle") { SmallTitle(text = stringResource(R.string.section_personalize)) }
        item(key = "personalize") {
            MiuixCard {
                SwitchPreference(
                    checked = state.showTester,
                    onCheckedChange = { activity.setTesterTabShown(it) },
                    title = stringResource(R.string.row_tester),
                    summary = stringResource(if (state.showTester) R.string.tester_sub_on else R.string.tester_sub_off),
                    startAction = { MiuixRowIcon(R.drawable.ic_science_24) },
                )
                GlassDropdownPreference(
                    title = stringResource(UiR.string.lang_title),
                    items = LanguageSetting.labels(activity).toList(),
                    selectedIndex = language,
                    backdrop = backdrop,
                    startAction = { MiuixRowIcon(UiR.drawable.ic_language_24) },
                    onSelectedIndexChange = {
                        // The platform recreates the activity in the new language.
                        language = it
                        LanguageSetting.apply(activity, it)
                    },
                )
                ArrowPreference(
                    title = stringResource(UiR.string.theme_title),
                    summary = stringResource(R.string.row_theme_summary),
                    startAction = { MiuixRowIcon(UiR.drawable.ic_palette_24) },
                    endActions = { EndText(stringResource(UiR.string.theme_style_miuix)) },
                    onClick = { activity.openTheme() },
                )
            }
        }
        item(key = "about") {
            MiuixCard {
                ArrowPreference(
                    title = stringResource(R.string.section_about),
                    startAction = { MiuixRowIcon(R.drawable.ic_info_24) },
                    endActions = { EndText(AboutInfo.versionName(activity)) },
                    onClick = { activity.openAbout() },
                )
            }
        }
    }
}

@Composable
private fun EndText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

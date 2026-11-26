package io.github.local.esimh4ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.local.esimh4ui.installerx.navigation.InstallerNavContainer
import io.github.local.esimh4ui.installerx.preferences.PredictiveBackAnimation
import io.github.local.esimh4ui.installerx.preferences.PredictiveBackExitDirection
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/** Stable string routes keep settings navigation across activity recreation. */
data class SettingsRoute(val page: String) : NavKey

@Composable
fun rememberSettingsBackStack(): NavBackStack = rememberSaveable(
    saver = listSaver<NavBackStack, String>(
        save = { stack -> stack.map { (it as SettingsRoute).page } },
        restore = { pages -> navBackStackOf(*pages.map(::SettingsRoute).toTypedArray()) },
    ),
) { navBackStackOf(SettingsRoute("root")) }

/** Use the ported InstallerX transitions for actual Compose destinations. */
@Composable
fun SettingsNavigation(
    backStack: NavBackStack,
    content: @Composable (String) -> Unit,
) {
    InstallerNavContainer(
        backStack = backStack,
        animation = when (ThemeConfig.predictiveBack) {
            ThemeConfig.BACK_SCALE -> PredictiveBackAnimation.Scale
            ThemeConfig.BACK_CLASSIC -> PredictiveBackAnimation.Classic
            ThemeConfig.BACK_NONE -> PredictiveBackAnimation.None
            ThemeConfig.BACK_MIUIX -> PredictiveBackAnimation.MIUIX
            else -> PredictiveBackAnimation.AOSP
        },
        exitDirection = when (ThemeConfig.backExitDirection) {
            1 -> PredictiveBackExitDirection.ALWAYS_RIGHT
            2 -> PredictiveBackExitDirection.ALWAYS_LEFT
            else -> PredictiveBackExitDirection.FOLLOW_GESTURE
        },
    ) {
        entry<SettingsRoute> { content(it.page) }
    }
}

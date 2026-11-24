@file:JvmName("AboutScreen")

package io.github.local.esimh4probe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.local.esimh4ui.ThemeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shows the About page in the Miuix style, laid out like the About page of the Miuix example app:
 * the HyperOS background effect behind the app icon, name and version, then cards for the
 * repository, the license and build, and the authors. Scrolling fades the header and the effect
 * out and the bar title in. [AboutActivity] calls this; the Material 3 style uses [AboutSection].
 */
fun setAboutContent(activity: AboutActivity, isDark: Boolean) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        MiuixTheme(controller = controller) {
            AboutPage(activity, isDark)
        }
    }
}

@Composable
private fun AboutPage(activity: AboutActivity, isDark: Boolean) {
    val colors = MiuixTheme.colorScheme
    val info by produceState<AboutInfo?>(null) {
        value = withContext(Dispatchers.IO) { AboutInfo.load(activity) }
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    // 0 while the header is fully visible, 1 once it has scrolled away.
    val progress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header" }
                if (header != null && header.size > 0) {
                    (listState.firstVisibleItemScrollOffset.toFloat() / header.size).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }
    val collapsed by remember { derivedStateOf { progress >= 0.6f } }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(200), label = "title")

    val openFailed = stringResource(R.string.snack_open_link_failed)
    val open: (String) -> Unit = { url ->
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, openFailed, Toast.LENGTH_SHORT).show()
        }
    }

    BgEffectBackground(
        isDark = isDark,
        surface = colors.surface,
        modifier = Modifier.fillMaxSize(),
        alpha = { 1f - progress },
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                SmallTopAppBar(
                    title = stringResource(R.string.section_about),
                    color = if (collapsed) colors.surface else Color.Transparent,
                    titleColor = colors.onSurface.copy(alpha = titleAlpha),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { activity.finish() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(io.github.local.esimh4ui.R.string.ui_back),
                                tint = colors.onBackground,
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
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
                item(key = "header") {
                    Header(
                        versionText = info?.let { "v" + it.versionText() } ?: "",
                        isDark = isDark,
                        progress = { progress },
                    )
                }
                val data = info ?: return@LazyColumn
                item(key = "repo") {
                    AboutCard {
                        ArrowPreference(
                            title = stringResource(R.string.about_repo),
                            summary = data.repo,
                            endActions = {
                                EndText(stringResource(if (data.fork) R.string.about_repo_fork else R.string.about_repo_original))
                            },
                            onClick = { open(data.repoUrl) },
                        )
                        if (data.fork && data.parent != null) {
                            ArrowPreference(
                                title = stringResource(R.string.about_upstream),
                                summary = data.parent,
                                onClick = { open(data.parentUrl) },
                            )
                        }
                    }
                }
                item(key = "build") {
                    AboutCard {
                        ArrowPreference(
                            title = stringResource(R.string.about_license),
                            endActions = { EndText(stringResource(R.string.about_license_value)) },
                            onClick = { open(data.licenseUrl()) },
                        )
                        val commit = data.shortCommit()
                        if (commit != null) {
                            ArrowPreference(
                                title = stringResource(R.string.about_commit),
                                endActions = { EndText(commit) },
                                onClick = { open(data.commitUrl()) },
                            )
                        }
                    }
                }
                item(key = "authorsTitle") { SmallTitle(text = stringResource(R.string.section_authors)) }
                item(key = "authors") {
                    AboutCard {
                        for (person in data.people) {
                            ArrowPreference(
                                title = person.name,
                                summary = AboutSection.personSummary(activity, person),
                                startAction = { Avatar(person) },
                                onClick = { open(person.url) },
                            )
                        }
                    }
                }
                item(key = "copyright") {
                    Text(
                        text = stringResource(R.string.about_copyright, data.years()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        color = colors.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** App icon, name (with a gradient, like the Miuix example) and version; fades out on scroll. */
@Composable
private fun Header(versionText: String, isDark: Boolean, progress: () -> Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val iconPx = with(LocalDensity.current) { 88.dp.roundToPx() }
    val icon = remember(iconPx) {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, iconPx, iconPx)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }
    val nameBrush = remember(isDark) {
        Brush.linearGradient(
            if (isDark) {
                listOf(Color(0xFFC3B8FF), Color(0xFFF2B6D9))
            } else {
                listOf(Color(0xFF3B2DB8), Color(0xFFA0609E))
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, bottom = 112.dp)
            .graphicsLayer {
                val p = (progress() / 0.5f).coerceIn(0f, 1f)
                alpha = 1f - p
                scaleX = 1f - p * 0.05f
                scaleY = 1f - p * 0.05f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(88.dp))
        BasicText(
            text = stringResource(R.string.app_name),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
            style = TextStyle(
                brush = nameBrush,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        Text(
            text = versionText,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Translucent card, so the background effect shows through while it is visible. */
@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)),
        content = content,
    )
}

@Composable
private fun EndText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

/** The contributor's GitHub avatar, or a person icon for a build without the workflow data. */
@Composable
private fun Avatar(person: AboutInfo.Person) {
    val modifier = Modifier
        .padding(end = 12.dp)
        .size(40.dp)
        .clip(CircleShape)
    val avatar = person.avatar
    if (avatar != null) {
        val bitmap = remember(avatar) { avatar.asImageBitmap() }
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_person_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

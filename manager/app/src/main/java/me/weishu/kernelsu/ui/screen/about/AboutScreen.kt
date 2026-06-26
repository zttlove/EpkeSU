package me.weishu.kernelsu.ui.screen.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.navigation3.LocalNavigator

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val githubLink = "<b><a href=\"$GITHUB_URL\">GitHub</a></b>"
    val telegramLink = "<b><a href=\"$TELEGRAM_URL\">Telegram</a></b>"
    val htmlString = remember(context) {
        runCatching {
            context.getString(R.string.about_source_code, githubLink, telegramLink)
        }.getOrDefault(defaultAboutLinksHtml(githubLink, telegramLink))
    }
    val links = remember(htmlString) {
        extractLinks(htmlString).ifEmpty { defaultAboutLinks() }
    }
    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        links = links,
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = uriHandler::openUri,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
        UiMode.Material -> AboutScreenMaterial(state, actions)
    }
}

private const val GITHUB_URL = "https://github.com/shengzimao/EpkeSU"
private const val TELEGRAM_URL = "https://t.me/+vIE0TtF9xxgyYzQ1"

private fun defaultAboutLinksHtml(githubLink: String, telegramLink: String): String {
    return "View source code at $githubLink<br/>Join our $telegramLink channel"
}

private fun defaultAboutLinks(): List<LinkInfo> {
    return listOf(
        LinkInfo("GitHub", GITHUB_URL),
        LinkInfo("Telegram", TELEGRAM_URL),
    )
}

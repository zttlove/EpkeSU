package me.weishu.kernelsu.ui.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import me.weishu.kernelsu.R

private const val LAUNCHER_ALIAS_PACKAGE = "me.weishu.kernelsu.ui"

enum class LauncherIconOption(
    val value: String,
    val aliasClassName: String,
    @StringRes val labelRes: Int,
    @DrawableRes val foregroundRes: Int,
) {
    Default(
        value = "default",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherDefault",
        labelRes = R.string.settings_app_icon_default,
        foregroundRes = R.mipmap.ic_launcher_foreground,
    ),
    Module(
        value = "module",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherModule",
        labelRes = R.string.settings_app_icon_module,
        foregroundRes = R.drawable.module_foreground,
    ),
    AnyKernel(
        value = "anykernel",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherAnyKernel",
        labelRes = R.string.settings_app_icon_anykernel,
        foregroundRes = R.drawable.anykernel_foreground,
    ),
    NekoStar(
        value = "neko_star",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherNekoStar",
        labelRes = R.string.settings_app_icon_neko_star,
        foregroundRes = R.mipmap.neko_star_foreground,
    ),
    AnimeBlueHair(
        value = "anime_blue_hair",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherAnimeBlueHair",
        labelRes = R.string.settings_app_icon_anime_blue_hair,
        foregroundRes = R.mipmap.anime_blue_hair_foreground,
    ),
    AnimeEyepatch(
        value = "anime_eyepatch",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherAnimeEyepatch",
        labelRes = R.string.settings_app_icon_anime_eyepatch,
        foregroundRes = R.mipmap.anime_eyepatch_foreground,
    ),
    AnimeBlonde(
        value = "anime_blonde",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherAnimeBlonde",
        labelRes = R.string.settings_app_icon_anime_blonde,
        foregroundRes = R.mipmap.anime_blonde_foreground,
    ),
    AnimeWhiteHair(
        value = "anime_white_hair",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherAnimeWhiteHair",
        labelRes = R.string.settings_app_icon_anime_white_hair,
        foregroundRes = R.mipmap.anime_white_hair_foreground,
    ),
    AnimePinkHair(
        value = "anime_pink_hair",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherAnimePinkHair",
        labelRes = R.string.settings_app_icon_anime_pink_hair,
        foregroundRes = R.mipmap.anime_pink_hair_foreground,
    ),
    FoxMask(
        value = "fox_mask",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherFoxMask",
        labelRes = R.string.settings_app_icon_fox_mask,
        foregroundRes = R.mipmap.fox_mask_foreground,
    ),
    OperaMask(
        value = "opera_mask",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherOperaMask",
        labelRes = R.string.settings_app_icon_opera_mask,
        foregroundRes = R.mipmap.opera_mask_foreground,
    ),
    SkRoot(
        value = "sk_root",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherSkRoot",
        labelRes = R.string.settings_app_icon_sk_root,
        foregroundRes = R.mipmap.sk_root_foreground,
    ),
    GridMark(
        value = "grid_mark",
        aliasClassName = "${LAUNCHER_ALIAS_PACKAGE}.LauncherGridMark",
        labelRes = R.string.settings_app_icon_grid_mark,
        foregroundRes = R.mipmap.grid_mark_foreground,
    );

    companion object {
        const val PREF_KEY = "launcher_icon"
        const val DEFAULT_VALUE = "default"

        fun fromValue(value: String?): LauncherIconOption {
            return entries.firstOrNull { it.value == value } ?: Default
        }

        fun selectedIndex(value: String?): Int {
            return entries.indexOf(fromValue(value)).coerceAtLeast(0)
        }
    }
}

fun applyLauncherIcon(context: Context, option: LauncherIconOption): Boolean {
    val packageManager = context.packageManager
    val orderedOptions = listOf(option) + LauncherIconOption.entries.filter { it != option }
    return runCatching {
        orderedOptions.forEach { candidate ->
            val enabledState = if (candidate == option) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, candidate.aliasClassName),
                enabledState,
                PackageManager.DONT_KILL_APP,
            )
        }
        true
    }.onFailure {
        Log.e("LauncherIcon", "failed to apply launcher icon ${option.value}", it)
    }.getOrDefault(false)
}

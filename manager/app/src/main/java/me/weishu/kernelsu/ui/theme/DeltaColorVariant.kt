package me.weishu.kernelsu.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

const val DELTA_COLOR_VARIANT_KEY = "delta_color_variant"

enum class DeltaColorVariant(val value: String) {
    Green("green"),
    Red("red");

    companion object {
        const val DEFAULT_VALUE = "green"

        fun fromValue(value: String): DeltaColorVariant {
            return entries.find { it.value == value } ?: Green
        }
    }
}

val LocalDeltaColorVariant = staticCompositionLocalOf { DeltaColorVariant.DEFAULT_VALUE }

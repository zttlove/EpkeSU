package me.weishu.kernelsu.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object KernelStatusEvents {
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick

    fun requestRefresh() {
        _refreshTick.value = _refreshTick.value + 1
    }
}

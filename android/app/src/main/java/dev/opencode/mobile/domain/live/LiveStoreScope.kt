package dev.opencode.mobile.domain.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LiveStoreScope(
    val coroutineScope: CoroutineScope,
)

fun defaultLiveStoreScope(): LiveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))

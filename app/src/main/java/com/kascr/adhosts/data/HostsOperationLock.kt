package com.kascr.adhosts.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

/** Shared by foreground Hosts operations and scheduled checks. */
object HostsOperationLock {
    val mutex = Mutex()
    // A Root write must finish its commit or rollback even if the screen is recreated.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

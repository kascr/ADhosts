package com.kascr.adhosts.data

import kotlinx.coroutines.sync.Mutex

/** Shared by foreground Hosts operations and scheduled checks. */
object HostsOperationLock {
    val mutex = Mutex()
}

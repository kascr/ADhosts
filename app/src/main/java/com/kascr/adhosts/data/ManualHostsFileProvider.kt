package com.kascr.adhosts.data

import androidx.core.content.FileProvider
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

class ManualHostsFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val appContext = context ?: throw FileNotFoundException()
        if (uri != ManualHostsEditor.uri(appContext)) throw FileNotFoundException()
        return if ('w' in mode) ManualHostsEditor.openForWrite(appContext, mode)
            else super.openFile(uri, mode) ?: throw FileNotFoundException()
    }
}

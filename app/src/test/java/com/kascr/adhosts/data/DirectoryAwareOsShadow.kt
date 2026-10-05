package com.kascr.adhosts.data

import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.Collections
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Static Os interception leaves Robolectric's shared Linux instance and shadow mapping intact. */
@Implements(Os::class)
class DirectoryAwareOsShadow {
    companion object {
        private val directoryDescriptors =
            Collections.synchronizedMap(IdentityHashMap<FileDescriptor, File>())

        @JvmStatic
        @Implementation(methodName = "open")
        fun openDescriptor(path: String, flags: Int, mode: Int): FileDescriptor {
            val directory = File(path)
            if (directory.isDirectory) {
                return FileDescriptor().also { directoryDescriptors[it] = directory }
            }
            return Shadow.directlyOn(
                Os::class.java, "open",
                ClassParameter.from(String::class.java, path),
                ClassParameter.from(Int::class.javaPrimitiveType!!, flags),
                ClassParameter.from(Int::class.javaPrimitiveType!!, mode)
            )
        }

        @JvmStatic
        @Implementation(methodName = "fsync")
        fun syncDescriptor(descriptor: FileDescriptor) {
            if (directoryDescriptors.containsKey(descriptor)) return
            descriptor.sync()
        }

        @JvmStatic
        @Implementation(methodName = "close")
        fun closeDescriptor(descriptor: FileDescriptor) {
            if (directoryDescriptors.remove(descriptor) != null) return
            FileInputStream(descriptor).close()
        }

        @JvmStatic
        @Resetter
        fun reset() { directoryDescriptors.clear() }
    }
}

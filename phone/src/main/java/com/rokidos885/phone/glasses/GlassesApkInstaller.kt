package com.rokidos885.phone.glasses

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object GlassesApkInstaller {
    /** The SDK opens this file locally and passes a ParcelFileDescriptor to Hi Rokid. */
    fun ensureApkOnDisk(context:Context):File {
        val directory=requireNotNull(context.getExternalFilesDir("rokid"))
        directory.mkdirs()
        val target=File(directory,"glasses.apk")
        val temporary=File(directory,"glasses.apk.tmp")
        try {
            context.assets.open("glasses.apk").use {input->temporary.outputStream().use(input::copyTo)}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        }finally{temporary.delete()}
        return target
    }
}

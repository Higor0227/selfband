// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    /** Atomic write: temp file + fsync + rename (SPEC section 3.6). */
    fun write(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        Files.move(
            tmp.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

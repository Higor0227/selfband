// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import io.github.higor0227.selfband.core.model.AppSettings
import io.github.higor0227.selfband.core.model.ProxyMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun missingFileGivesDefaults() {
        assertEquals(AppSettings(), AppSettingsStore(File(tmp.root, "settings.json")).load())
    }

    @Test
    fun roundTrip() {
        val store = AppSettingsStore(File(tmp.root, "settings.json"))
        store.save(AppSettings(proxyMode = ProxyMode.ALWAYS))
        assertEquals(ProxyMode.ALWAYS, store.load().proxyMode)
    }

    @Test
    fun unknownKeysAndValuesFallBackToDefaults() {
        val file = File(tmp.root, "settings.json")
        file.writeText("""{"schemaVersion":1,"proxyMode":"SOMETIMES","futureKey":3}""")
        assertEquals(ProxyMode.AUTO, AppSettingsStore(file).load().proxyMode)
    }
}

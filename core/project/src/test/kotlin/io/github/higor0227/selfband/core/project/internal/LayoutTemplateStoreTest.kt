// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import io.github.higor0227.selfband.core.model.LayoutTemplate
import io.github.higor0227.selfband.core.model.NormRect
import io.github.higor0227.selfband.core.model.SlotStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayoutTemplateStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val template = LayoutTemplate(
        id = "user-a",
        slots = mapOf(LayoutTemplate.DEFAULT to listOf(NormRect(0f, 0f, 1f, 0.5f), NormRect(0f, 0.5f, 1f, 0.5f))),
        name = "Duo",
        styles = listOf(SlotStyle(), SlotStyle(z = 1, cornerRadius = 0.1f)),
    )

    @Test
    fun missingOrBrokenFileGivesNoTemplates() {
        val file = File(tmp.root, "layout-templates.json")
        assertEquals(emptyList<LayoutTemplate>(), LayoutTemplateStore(file).load())
        file.writeText("{ not json")
        assertEquals(emptyList<LayoutTemplate>(), LayoutTemplateStore(file).load())
    }

    @Test
    fun roundTrip() {
        val store = LayoutTemplateStore(File(tmp.root, "layout-templates.json"))
        store.save(listOf(template))
        assertEquals(listOf(template), store.load())
    }

    @Test
    fun idsAreUserPrefixedAndUnique() {
        val a = LayoutTemplateStore.newId()
        val b = LayoutTemplateStore.newId()
        assertTrue(a.startsWith("user-"))
        assertTrue(a != b)
    }
}

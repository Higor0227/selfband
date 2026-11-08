// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import io.github.higor0227.selfband.core.model.DeviceProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * `<filesDir>/device_profiles.json`: `{ "schemaVersion": 1, "profiles": [...] }`.
 * Pure JVM so it can be unit tested.
 */
internal class DeviceProfileStore(private val file: File) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val listSerializer = ListSerializer(DeviceProfile.serializer())

    /** Returns an empty list if the file is missing or unreadable. */
    fun load(): List<DeviceProfile> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText()) as JsonObject
            json.decodeFromJsonElement(listSerializer, root.getValue(PROFILES))
        }.getOrDefault(emptyList())
    }

    fun save(profiles: List<DeviceProfile>) {
        val root = buildJsonObject {
            put(SCHEMA_VERSION, JsonPrimitive(CURRENT_SCHEMA_VERSION))
            put(PROFILES, json.encodeToJsonElement(listSerializer, profiles))
        }
        AtomicFiles.write(file, json.encodeToString(JsonObject.serializer(), root))
    }

    private companion object {
        const val SCHEMA_VERSION = "schemaVersion"
        const val PROFILES = "profiles"
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mozilla.components.feature.summarize.settings.SummarizationSettings
import mozilla.components.lib.ai.controls.AIFeatureBlockStorage
import mozilla.components.lib.ai.controls.dataStore
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.bool
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.backup.strings
import org.mozilla.fenix.translations.TranslationsEnabledSettings

/**
 * The content of one Preferences DataStore, saved without knowing which keys it has: **a key that is added to the
 * store later is backed up automatically**.
 *
 * Each line is `{"key":"...","type":"boolean","value":true}`, with the same types as [PreferencesCodec] and also
 * `double`.
 *
 * What happens when a backup is restored depends on [style].
 *
 * @param id See [BackupSection.id].
 * @param titleRes See [BackupSection.titleRes].
 * @param dataStore The store. It is looked up when it is needed, because a DataStore must only be created once.
 * @param style How restored values are combined with the values that are already there.
 * @param jsonMapKeys The keys whose value is a JSON object of text to text, which is combined entry by entry.
 */
internal class PreferencesDataStoreSection(
    override val id: String,
    @param:StringRes override val titleRes: Int,
    private val dataStore: () -> DataStore<Preferences>,
    private val style: Style,
    private val jsonMapKeys: Set<String> = emptySet(),
) : BackupSection {
    override val version = 1

    /** How a restored value is combined with the value that is already in the store. */
    enum class Style {
        /** Settings: the value in the backup replaces the value that is there. */
        SETTINGS,

        /**
         * State that the app collects while it is used, such as which tabs are locked: nothing that is there is lost.
         * Flags that are set stay set, counters keep the larger value, sets of text are joined, maps are joined with
         * the entries that are already there taking precedence, and anything else that is already there stays.
         */
        STATE,
    }

    override suspend fun export(out: SectionWriter) {
        val all = dataStore().data.first().asMap()
        for (entry in all.entries.sortedBy { it.key.name }) {
            val value = entry.value
            val line = if (value is Double) {
                buildJsonObject {
                    put("key", entry.key.name)
                    put("type", TYPE_DOUBLE)
                    put("value", JsonPrimitive(value))
                }
            } else {
                PreferencesCodec.encode(id, entry.key.name, value)?.let { encoded ->
                    // The name of the store is the name of the section, so it is not repeated on every line.
                    JsonObject(encoded.filterKeys { it != "file" })
                }
            }
            if (line == null) {
                out.warn("A value has a type that cannot be saved: ${entry.key.name}")
            } else {
                out.write(line)
            }
        }
    }

    override suspend fun import(input: SectionReader) {
        val lines = input.entries().toList()
        var restored = 0
        var skipped = 0
        dataStore().edit { preferences ->
            for (line in lines) {
                if (apply(preferences, line)) restored++ else skipped++
            }
        }
        input.restored(restored)
        input.skipped(skipped)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun apply(preferences: MutablePreferences, line: JsonObject): Boolean {
        val name = line.string("key") ?: return false
        val type = line.string("type") ?: return false
        val value = line["value"] ?: return false
        val primitive = value as? JsonPrimitive
        val state = style == Style.STATE
        try {
            when (type) {
                "boolean" -> {
                    val backup = primitive?.booleanOrNull ?: return false
                    val key = booleanPreferencesKey(name)
                    preferences[key] = if (state) backup || preferences[key] == true else backup
                }
                "int" -> {
                    val backup = primitive?.intOrNull ?: return false
                    val key = intPreferencesKey(name)
                    val local = preferences[key]
                    preferences[key] = if (state && local != null) maxOf(local, backup) else backup
                }
                "long" -> {
                    val backup = primitive?.longOrNull ?: return false
                    val key = longPreferencesKey(name)
                    val local = preferences[key]
                    preferences[key] = if (state && local != null) maxOf(local, backup) else backup
                }
                "float" -> {
                    val backup = primitive?.floatOrNull ?: return false
                    val key = floatPreferencesKey(name)
                    if (!state || preferences[key] == null) preferences[key] = backup
                }
                TYPE_DOUBLE -> {
                    val backup = primitive?.doubleOrNull ?: return false
                    val key = doublePreferencesKey(name)
                    if (!state || preferences[key] == null) preferences[key] = backup
                }
                "string" -> {
                    val backup = primitive?.contentOrNull ?: return false
                    val key = stringPreferencesKey(name)
                    val local = preferences[key]
                    preferences[key] = when {
                        !state || local == null -> backup
                        name in jsonMapKeys -> mergeJsonObjects(local, backup)
                        else -> local
                    }
                }
                "string_set" -> {
                    val backup = (value as? JsonArray)?.strings()?.toSet() ?: return false
                    val key = stringSetPreferencesKey(name)
                    preferences[key] = if (state) preferences[key].orEmpty() + backup else backup
                }
                else -> return false
            }
        } catch (e: ClassCastException) {
            // The store already holds a value of another type under this name.
            return false
        }
        return true
    }

    private companion object {
        const val TYPE_DOUBLE = "double"

        /** Joins two JSON objects. When both have an entry with the same name, [local] wins. */
        fun mergeJsonObjects(local: String, backup: String): String {
            val backupObject = parseObject(backup) ?: return local
            val localObject = parseObject(local) ?: return backup
            return JsonObject(backupObject + localObject).toString()
        }

        fun parseObject(text: String): JsonObject? = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * The on/off switches of features that keep them in their own small data stores: translations, the global switch for
 * AI features, and the summaries. The value in the backup replaces the value that is set now.
 *
 * Add a line to [export] and to [import] when another feature like this appears.
 */
internal class FeatureTogglesSection(private val context: Context) : BackupSection {
    override val id = "feature_toggles"
    override val version = 1
    override val titleRes = R.string.backup_section_feature_toggles

    override suspend fun export(out: SectionWriter) {
        out.write(toggle(TRANSLATIONS, TranslationsEnabledSettings.dataStore(context).isEnabled.first()))
        out.write(toggle(AI_BLOCKED, AIFeatureBlockStorage.dataStore(context).isBlocked.first()))

        val summarization = SummarizationSettings.dataStore(context)
        // Not set until the person has chosen, which is different from either answer.
        summarization.getFeatureEnabledUserStatus().first()?.let { out.write(toggle(SUMMARIES, it)) }
        out.write(toggle(SUMMARIES_GESTURE, summarization.getGestureEnabledUserStatus().first()))
    }

    override suspend fun import(input: SectionReader) {
        val summarization = SummarizationSettings.dataStore(context)
        for (line in input.entries()) {
            val enabled = line.bool("value")
            if (enabled == null) {
                input.skipped()
                continue
            }
            when (line.string("name")) {
                TRANSLATIONS -> TranslationsEnabledSettings.dataStore(context).setEnabled(enabled)
                AI_BLOCKED -> AIFeatureBlockStorage.dataStore(context).setBlocked(enabled)
                SUMMARIES -> summarization.setFeatureEnabledUserStatus(enabled)
                SUMMARIES_GESTURE -> summarization.setGestureEnabledUserStatus(enabled)
                else -> {
                    input.skipped()
                    continue
                }
            }
            input.restored()
        }
    }

    private fun toggle(name: String, enabled: Boolean) = buildJsonObject {
        put("name", name)
        put("value", enabled)
    }

    private companion object {
        const val TRANSLATIONS = "translations_enabled"
        const val AI_BLOCKED = "ai_features_blocked"
        const val SUMMARIES = "summaries_enabled"
        const val SUMMARIES_GESTURE = "summaries_gesture_enabled"
    }
}

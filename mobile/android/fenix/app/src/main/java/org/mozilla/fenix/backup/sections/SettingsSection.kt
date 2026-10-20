/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.backup.strings
import org.mozilla.fenix.utils.Settings

/**
 * One `SharedPreferences` file and the name it has inside backup files. The name inside the backup file never changes,
 * even when the file on disk is renamed, because backups made by older versions must stay readable.
 */
internal class PreferencesFile(val name: String, val open: (Context) -> SharedPreferences) {
    companion object {
        /** The file the settings screens and [Settings] use. */
        val FENIX = PreferencesFile("fenix_preferences") {
            it.getSharedPreferences(Settings.FENIX_PREFERENCES, Context.MODE_PRIVATE)
        }

        /** The default preferences file of the app, which a few settings screens use. */
        val DEFAULT = PreferencesFile("default") { PreferenceManager.getDefaultSharedPreferences(it) }

        /** The domains the user added to the autocomplete of the address bar. */
        val CUSTOM_AUTOCOMPLETE = PreferencesFile("custom_autocomplete") {
            it.getSharedPreferences("custom_autocomplete", Context.MODE_PRIVATE)
        }

        /** The font, size and colors of reader view. */
        val READER_VIEW = PreferencesFile("mozac_feature_reader_view") {
            it.getSharedPreferences("mozac_feature_reader_view", Context.MODE_PRIVATE)
        }

        /** What the search engine settings and the custom search engines need. */
        val SEARCH_METADATA = PreferencesFile("mozac_feature_search_metadata") {
            it.getSharedPreferences("mozac_feature_search_metadata", Context.MODE_PRIVATE)
        }

        /** The language the person chose for the app, when they did not leave it to the language of the device. */
        val LOCALE = PreferencesFile("mozac_support_base_locale_manager_preference") {
            it.getSharedPreferences("mozac_support_base_locale_manager_preference", Context.MODE_PRIVATE)
        }

        /** The location the person chose for IP protection. */
        val IP_PROTECTION = PreferencesFile("mozac_feature_ip_protection") {
            it.getSharedPreferences("mozac_feature_ip_protection", Context.MODE_PRIVATE)
        }
    }
}

/**
 * Reads and writes the typed values of a `SharedPreferences` file as lines of a backup section:
 *
 * ```
 * {"file":"fenix_preferences","key":"pref_key_x","type":"boolean","value":true}
 * ```
 *
 * The types are `boolean`, `int`, `long`, `float`, `string` and `string_set` (a JSON array of strings).
 */
internal object PreferencesCodec {
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "string_set"

    /** Turns a value of a `SharedPreferences` file into a line, or returns `null` when its type is not supported. */
    fun encode(file: String, key: String, value: Any?): JsonObject? {
        val type: String
        val json: JsonElement
        when (value) {
            is Boolean -> {
                type = TYPE_BOOLEAN
                json = JsonPrimitive(value)
            }
            is Int -> {
                type = TYPE_INT
                json = JsonPrimitive(value)
            }
            is Long -> {
                type = TYPE_LONG
                json = JsonPrimitive(value)
            }
            is Float -> {
                type = TYPE_FLOAT
                json = JsonPrimitive(value.toDouble())
            }
            is String -> {
                type = TYPE_STRING
                json = JsonPrimitive(value)
            }
            is Set<*> -> {
                type = TYPE_STRING_SET
                json = JsonArray(value.filterIsInstance<String>().sorted().map { JsonPrimitive(it) })
            }
            else -> return null
        }
        return buildJsonObject {
            put("file", file)
            put("key", key)
            put("type", type)
            put("value", json)
        }
    }

    /**
     * Writes the value of [line] to [editor].
     *
     * @param mergeSets When `true`, a set of strings is added to the set that is already in [current] instead of
     *   replacing it.
     * @return `false` when [line] has no usable key, type or value.
     */
    fun apply(
        editor: SharedPreferences.Editor,
        line: JsonObject,
        current: SharedPreferences? = null,
        mergeSets: Boolean = false,
    ): Boolean {
        val key = line.string("key") ?: return false
        val type = line.string("type") ?: return false
        val value = line["value"] ?: return false
        val primitive = value as? JsonPrimitive
        when (type) {
            TYPE_BOOLEAN -> editor.putBoolean(key, primitive?.booleanOrNull ?: return false)
            TYPE_INT -> editor.putInt(key, primitive?.intOrNull ?: return false)
            TYPE_LONG -> editor.putLong(key, primitive?.longOrNull ?: return false)
            TYPE_FLOAT -> editor.putFloat(key, primitive?.floatOrNull ?: return false)
            TYPE_STRING -> editor.putString(key, primitive?.contentOrNull ?: return false)
            TYPE_STRING_SET -> {
                val restored = (value as? JsonArray)?.strings()?.toSet() ?: return false
                val existing = if (mergeSets) current?.getStringSet(key, null) else null
                editor.putStringSet(key, if (existing == null) restored else existing + restored)
            }
            else -> return false
        }
        return true
    }
}

/**
 * Settings that belong to one installation of the app rather than to the person, which a backup must not carry over:
 * identifiers and bookkeeping for telemetry and install attribution, the account and key store state of the device,
 * wallpapers (which are files), and the settings of developers.
 *
 * Everything else in the preference files is saved, including settings that are added in the future. That is the point
 * of the lists being short: **a new setting is backed up without anyone having to remember it**. Add a key or a prefix
 * here only when restoring it on another device would do harm.
 */
internal object DeviceSpecificPreferences {
    /** Prefixes of the names of settings that are left out. */
    private val PREFIXES = listOf(
        // Install attribution and campaigns.
        "pref_key_adjust_",
        "pref_key_utm_",
        "pref_key_is_user_",
        "pref_key_referral_",
        "pref_key_rtamo_",
        // Bookkeeping of growth pings and experiments.
        "pref_key_growth_",
        "pref_key_first_week_post_install_",
        "pref_key_nimbus_",
        // Wallpapers are downloaded files that a backup does not hold.
        "pref_key_current_wallpaper",
        "pref_key_should_migrate_wallpaper",
        "pref_key_wallpapers",
        // Settings of developers: other servers, debug menus.
        "pref_key_sync_debug",
        "pref_key_debug_",
        "pref_key_dev_debug_",
        "pref_key_override_",
        "pref_key_remote_settings_server",
        "pref_key_leakcanary",
    )

    /** The names of settings that are left out. */
    private val KEYS = setOf(
        // Tied to the key store, the account and the apps of this device.
        "pref_key_encryption_key_generated",
        "pref_key_fxa_signed_in",
        "pref_key_sync_device_name", // The app itself removes it when the account is signed out.
        "project_id", // pref_key_push_project_id
        "pref_key_default_browser",
        "pref_key_search_widget_installed_2",
        "pref_key_downloads_default_location",
        "pref_key_last_known_mode_private",
        // Identifiers and counters that describe one installation.
        "pref_key_glean_usage_profile_id",
        "pref_key_contile_context_id",
        "pref_key_distribution_id",
        "pref_key_num_font_list_sent",
        "pref_key_times_app_opened",
        "pref_key_app_cold_start_count",
        "pref_key_app_launch_count",
        "pref_key_application_exit_info_last_handled_time",
        // Snapshots of the state of the app that are made again by the app itself.
        "pref_key_open_tabs_count",
        "pref_key_open_private_tabs_count",
        "pref_key_installed_addons_count",
        "pref_key_installed_addons_list",
        "pref_key_enabled_addons_count",
        "pref_key_enabled_addons_list",
        "pref_key_mobile_bookmarks_size",
        "pref_key_desktop_bookmarks_size",
    )

    /** Whether the setting [key] must be left out of backups and ignored when a backup is restored. */
    fun isExcluded(key: String): Boolean = key in KEYS || PREFIXES.any { key.startsWith(it) }

    /** The names that [isExcluded] looks for, for checks of the lists themselves. */
    val listedKeys: Set<String> get() = KEYS
    val listedPrefixes: List<String> get() = PREFIXES
}

/**
 * The settings of the app: the preference files in [files].
 *
 * Every setting in these files is saved except the ones in [DeviceSpecificPreferences]. When a backup is restored, the
 * value from the backup replaces the value that is set now, because that is what restoring settings means. Settings the
 * backup does not have stay as they are. The restored settings are fully used after the app is restarted.
 *
 * To back up another preference file, add it to [files] and give the file a name that never changes.
 */
internal class SettingsSection(
    private val context: Context,
    private val files: List<PreferencesFile> = listOf(
        PreferencesFile.FENIX,
        PreferencesFile.DEFAULT,
        PreferencesFile.CUSTOM_AUTOCOMPLETE,
        PreferencesFile.READER_VIEW,
        PreferencesFile.LOCALE,
        PreferencesFile.IP_PROTECTION,
    ),
) : BackupSection {
    override val id = "settings"
    override val version = 1
    override val titleRes = R.string.backup_section_settings

    override suspend fun export(out: SectionWriter) {
        for (file in files) {
            val all = file.open(context).all
            for (key in all.keys.sorted()) {
                if (DeviceSpecificPreferences.isExcluded(key)) continue
                val line = PreferencesCodec.encode(file.name, key, all[key])
                if (line == null) {
                    out.warn("A setting has a type that cannot be saved: $key")
                } else {
                    out.write(line)
                }
            }
        }
    }

    override suspend fun import(input: SectionReader) {
        val editors = HashMap<String, SharedPreferences.Editor>()
        for (line in input.entries()) {
            val fileName = line.string("file")
            val key = line.string("key")
            val file = files.firstOrNull { it.name == fileName }
            if (file == null || key == null || DeviceSpecificPreferences.isExcluded(key)) {
                input.skipped()
                continue
            }
            val editor = editors.getOrPut(file.name) { file.open(context).edit() }
            if (PreferencesCodec.apply(editor, line)) input.restored() else input.skipped()
        }
        // Writing synchronously makes sure the settings are on disk before the person is told that they are restored.
        val failed = editors.filterValues { !it.commit() }.keys
        if (failed.isNotEmpty()) throw IOException("The settings could not be written: ${failed.joinToString()}")
    }
}

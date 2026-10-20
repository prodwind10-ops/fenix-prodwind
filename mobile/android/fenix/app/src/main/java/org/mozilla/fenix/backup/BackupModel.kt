/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import androidx.annotation.StringRes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a backup file says about one of its sections.
 *
 * @property id The [BackupSection.id].
 * @property version The [BackupSection.version] the section had when it was saved.
 * @property entry The name of the file inside the archive that holds the lines of the section.
 * @property count How many entries the section saved.
 * @property failure Why the section could not be saved, or `null` when it was saved.
 * @property warnings Problems with single items that were left out.
 */
internal class SectionInfo(
    val id: String,
    val version: Int,
    val entry: String,
    val count: Int,
    val failure: String?,
    val warnings: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        put("file", entry)
        put("count", count)
        putIfNotNull("failure", failure)
        if (warnings.isNotEmpty()) put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
    }

    companion object {
        /** Reads a [SectionInfo], or returns `null` when [json] does not describe a section. */
        fun fromJson(json: JsonObject): SectionInfo? {
            val id = json.string("id") ?: return null
            val version = json.int("version") ?: return null
            val entry = json.string("file") ?: return null
            return SectionInfo(
                id = id,
                version = version,
                entry = entry,
                count = json.int("count") ?: 0,
                failure = json.string("failure"),
                warnings = json.array("warnings")?.strings().orEmpty(),
            )
        }
    }
}

/**
 * The table of contents of a backup file, stored in the archive as `manifest.json`.
 *
 * @property format The layout of the archive, see [BackupArchive.ARCHIVE_FORMAT].
 * @property createdAt When the backup was made, in milliseconds since 1970.
 * @property appVersionName The version of the app that made the backup.
 * @property appVersionCode The build number of the app that made the backup.
 * @property androidSdk The Android version of the device that made the backup.
 * @property sections The sections in the backup.
 */
internal class BackupManifest(
    val format: Int,
    val createdAt: Long,
    val appVersionName: String?,
    val appVersionCode: Long?,
    val androidSdk: Int?,
    val sections: List<SectionInfo>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("format", format)
        put("createdAt", createdAt)
        put(
            "app",
            buildJsonObject {
                putIfNotNull("versionName", appVersionName)
                putIfNotNull("versionCode", appVersionCode)
                if (androidSdk != null) put("androidSdk", androidSdk)
            },
        )
        put("sections", JsonArray(sections.map { it.toJson() }))
    }

    companion object {
        /**
         * Reads a [BackupManifest]. Throws a [BackupException] when [json] is not a manifest, or when it was written by
         * a version of the app that is newer than this one.
         */
        fun fromJson(json: JsonObject): BackupManifest {
            val format = json.int("format")
                ?: throw BackupException(BackupException.Reason.DAMAGED, "The backup file is damaged.")
            if (format > BackupArchive.ARCHIVE_FORMAT) {
                throw BackupException(
                    BackupException.Reason.NEWER_VERSION,
                    "This backup was made by a newer version of the app. Update the app and try again.",
                )
            }
            val app = json.obj("app")
            return BackupManifest(
                format = format,
                createdAt = json.long("createdAt") ?: 0L,
                appVersionName = app?.string("versionName"),
                appVersionCode = app?.long("versionCode"),
                androidSdk = app?.int("androidSdk"),
                sections = json.array("sections").orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(SectionInfo::fromJson) },
            )
        }
    }
}

/** What became of a section when it was saved or restored. */
internal enum class SectionOutcome {
    /** The section was saved or restored. Single items may have been left out, see the warnings. */
    DONE,

    /** The backup has no such section, for example because it was made by an older version of the app. */
    NOT_IN_BACKUP,

    /** The backup has the section, but it could not be saved when the backup was made. */
    NOT_SAVED,

    /** The section was saved by a newer version of the app than this one, which cannot read it. */
    NEWER_THAN_APP,

    /** The backup has a section that this version of the app does not know. */
    UNKNOWN_SECTION,

    /** The section could not be saved or restored. */
    FAILED,
}

/**
 * The result for one section.
 *
 * @property id The [BackupSection.id].
 * @property titleRes The title of the section, or 0 when this version of the app does not know the section.
 * @property outcome What happened.
 * @property processed How many entries were saved or restored.
 * @property skipped How many entries were left out.
 * @property message What went wrong, when something did.
 * @property warnings Problems with single items.
 */
internal class SectionReport(
    val id: String,
    @param:StringRes val titleRes: Int,
    val outcome: SectionOutcome,
    val processed: Int = 0,
    val skipped: Int = 0,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

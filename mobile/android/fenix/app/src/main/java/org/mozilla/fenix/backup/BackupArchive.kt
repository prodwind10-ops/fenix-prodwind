/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The ZIP archive inside a backup file (see [BackupContainer]):
 *
 * ```
 * manifest.json              the table of contents, see BackupManifest
 * sections/<id>.ndjson       one file per section, one JSON object per line
 * ```
 *
 * The manifest is written last, because it records how every section went.
 *
 * Saving and restoring never stop at the first section that fails: the other sections are still saved or restored,
 * and the result says what did not work.
 */
internal object BackupArchive {
    /** The layout of the archive. A change that older versions of the app cannot read raises it. */
    const val ARCHIVE_FORMAT = 1

    const val MANIFEST_ENTRY = "manifest.json"
    private const val SECTION_DIRECTORY = "sections/"
    private const val SECTION_EXTENSION = ".ndjson"
    private val SECTION_ID = Regex("[a-z0-9_]{1,64}")
    private const val MAX_MESSAGE_LENGTH = 300

    /** The version of the app and of Android that make a backup, for the manifest. */
    class AppInfo(val versionName: String?, val versionCode: Long?, val androidSdk: Int?)

    /** The name of the file inside the archive that holds the lines of the section with the given [id]. */
    fun entryName(id: String) = "$SECTION_DIRECTORY$id$SECTION_EXTENSION"

    /**
     * Writes the archive with the given [sections] to [archive], which is closed afterwards.
     *
     * @param archive Where the ZIP archive goes.
     * @param sections The sections to save, in the order they are saved.
     * @param app What goes into the manifest about the app.
     * @param createdAt What goes into the manifest as the time of the backup.
     * @param onSection Called before each section is saved.
     * @return How every section went.
     */
    suspend fun write(
        archive: OutputStream,
        sections: List<BackupSection>,
        app: AppInfo,
        createdAt: Long,
        onSection: (BackupSection) -> Unit = {},
    ): List<SectionReport> {
        require(sections.map { it.id }.toSet().size == sections.size) { "Two sections have the same id." }
        sections.forEach { require(SECTION_ID.matches(it.id)) { "Bad section id: ${it.id}" } }

        val reports = ArrayList<SectionReport>()
        val infos = ArrayList<SectionInfo>()
        ZipOutputStream(archive).use { zip ->
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            for (section in sections) {
                currentCoroutineContext().ensureActive()
                onSection(section)

                val entry = entryName(section.id)
                zip.putNextEntry(ZipEntry(entry))
                val out = SectionWriter(writer)
                var failure: String? = null
                try {
                    section.export(out)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    throw e
                } catch (e: Exception) {
                    failure = describe(e)
                } catch (e: OutOfMemoryError) {
                    failure = "There was not enough memory."
                }
                writer.flush()
                zip.closeEntry()

                infos.add(SectionInfo(section.id, section.version, entry, out.count, failure, out.warnings))
                reports.add(
                    SectionReport(
                        id = section.id,
                        titleRes = section.titleRes,
                        outcome = if (failure == null) SectionOutcome.DONE else SectionOutcome.FAILED,
                        processed = out.count,
                        message = failure,
                        warnings = out.warnings,
                    ),
                )
            }

            val manifest = BackupManifest(
                format = ARCHIVE_FORMAT,
                createdAt = createdAt,
                appVersionName = app.versionName,
                appVersionCode = app.versionCode,
                androidSdk = app.androidSdk,
                sections = infos,
            )
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            writer.write(manifest.toJson().toString())
            writer.flush()
            zip.closeEntry()
        }
        return reports
    }

    /**
     * Reads the manifest of [archive]. Throws a [BackupException] when [archive] is not a backup, is damaged, or was
     * made by a newer version of the app.
     */
    fun readManifest(archive: ZipFile): BackupManifest {
        val entry = archive.getEntry(MANIFEST_ENTRY)
            ?: throw BackupException(BackupException.Reason.NOT_A_BACKUP, "This file is not a backup made by this app.")
        val json = try {
            val text = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        return BackupManifest.fromJson(
            json ?: throw BackupException(BackupException.Reason.DAMAGED, "The backup file is damaged."),
        )
    }

    /**
     * Restores the [selected] sections found in [archive], in the order of [sections].
     *
     * @param archive The archive of the backup.
     * @param manifest The manifest of [archive].
     * @param sections All the sections this version of the app knows.
     * @param selected The [BackupSection.id]s of the sections to restore.
     * @param onSection Called before each section is restored.
     * @return How every section went. Sections of the backup that this version of the app does not know are included.
     */
    suspend fun restore(
        archive: ZipFile,
        manifest: BackupManifest,
        sections: List<BackupSection>,
        selected: Set<String>,
        onSection: (BackupSection) -> Unit = {},
    ): List<SectionReport> {
        val reports = ArrayList<SectionReport>()
        for (section in sections) {
            if (section.id !in selected) continue
            currentCoroutineContext().ensureActive()

            val info = manifest.sections.firstOrNull { it.id == section.id }
            when {
                info == null ->
                    reports.add(SectionReport(section.id, section.titleRes, SectionOutcome.NOT_IN_BACKUP))
                info.failure != null ->
                    reports.add(
                        SectionReport(section.id, section.titleRes, SectionOutcome.NOT_SAVED, message = info.failure),
                    )
                info.version > section.version ->
                    reports.add(SectionReport(section.id, section.titleRes, SectionOutcome.NEWER_THAN_APP))
                else -> {
                    onSection(section)
                    reports.add(restoreSection(archive, info, section))
                }
            }
        }
        for (info in manifest.sections) {
            if (sections.none { it.id == info.id }) {
                reports.add(SectionReport(info.id, 0, SectionOutcome.UNKNOWN_SECTION))
            }
        }
        return reports
    }

    private suspend fun restoreSection(archive: ZipFile, info: SectionInfo, section: BackupSection): SectionReport {
        val entry = archive.getEntry(info.entry)
            ?: return SectionReport(
                section.id,
                section.titleRes,
                SectionOutcome.FAILED,
                message = "The backup file is missing this part.",
            )
        return archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
            val input = SectionReader(reader, info.version)
            var failure: String? = null
            try {
                section.import(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = describe(e)
            } catch (e: OutOfMemoryError) {
                failure = "There was not enough memory."
            }
            SectionReport(
                id = section.id,
                titleRes = section.titleRes,
                outcome = if (failure == null) SectionOutcome.DONE else SectionOutcome.FAILED,
                processed = input.restored,
                skipped = input.skippedTotal,
                message = failure,
                warnings = input.warnings,
            )
        }
    }

    private fun describe(e: Exception): String {
        val text = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return text.take(MAX_MESSAGE_LENGTH)
    }
}

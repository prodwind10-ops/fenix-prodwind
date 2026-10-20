/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import androidx.annotation.StringRes
import java.io.BufferedReader
import java.io.IOException
import java.io.Writer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * One kind of app data that can be saved to a backup file and restored from it.
 *
 * Every place where the app keeps data the user would miss after a reinstall needs a section, and [BackupSections] is
 * where the sections are listed. See `CLAUDE.md` in the root of the repository for the rules for adding and changing
 * sections, and `README.md` in this package for how a backup file is laid out.
 *
 * A section writes its data as lines of JSON (one JSON object per line) and reads them back in the same way. The
 * lines of a section are free to hold whatever the section needs, but they must stay readable by newer versions of
 * the app: when the lines change in a way that older code could not understand, raise [version] and teach [import]
 * to read the old lines as well as the new ones.
 */
internal interface BackupSection {
    /**
     * Names this section inside backup files, so it can never change. Only lowercase letters, digits and underscores.
     */
    val id: String

    /** The version of the lines [export] writes. Start with 1. [import] must understand every version up to this. */
    val version: Int

    /** What the section is called in the app. */
    @get:StringRes val titleRes: Int

    /** Whether the section holds secrets, such as passwords or logins to websites. */
    val sensitive: Boolean get() = false

    /**
     * Writes everything this section has to [out].
     *
     * Throw an exception when the data could not be read. The section is then marked as failed in the backup file,
     * and the other sections are still saved. Use [SectionWriter.warn] for problems with single items.
     */
    suspend fun export(out: SectionWriter)

    /**
     * Restores the data from [input], which was written by [export] of this version of the app or an older one
     * ([SectionReader.sectionVersion]).
     *
     * Restoring merges: it adds what is missing and it does not delete what is already there. Restoring the same
     * backup twice must not duplicate anything. Report what was done with [SectionReader.restored] and
     * [SectionReader.skipped]. Throw an exception when the section could not be restored; what was restored up to
     * then stays restored.
     */
    suspend fun import(input: SectionReader)
}

/** Where a [BackupSection] writes its lines when it is exported. */
internal class SectionWriter(private val sink: Writer) {
    /** How many entries have been written with [write]. */
    var count = 0
        private set

    private val problems = ArrayList<String>()

    /** Problems with single items that did not keep the section from being saved. */
    val warnings: List<String> get() = problems

    /** Writes one entry of the section. */
    fun write(entry: JsonObject) {
        emit(entry)
        count++
    }

    /** Writes a line that is not an entry of its own, such as a header, so it is not counted. */
    fun writeMeta(entry: JsonObject) {
        emit(entry)
    }

    /** Notes a problem with an item that was left out, for the person who looks at the result. */
    fun warn(message: String) {
        if (problems.size < MAX_WARNINGS) problems.add(message)
    }

    private fun emit(entry: JsonObject) {
        try {
            sink.write(entry.toString())
            sink.write("\n")
        } catch (e: IOException) {
            // Not a problem of the section but of the file that is written: it must stop the whole backup.
            throw BackupException(BackupException.Reason.OTHER, "The backup file could not be written.", e)
        }
    }

    private companion object {
        const val MAX_WARNINGS = 20
    }
}

/**
 * Where a [BackupSection] reads its lines when it is restored.
 *
 * @param source The lines the section wrote.
 * @param sectionVersion The [BackupSection.version] the section had when the backup was made.
 */
internal class SectionReader(
    private val source: BufferedReader,
    val sectionVersion: Int,
) {
    /** How many entries were restored. */
    var restored = 0
        private set

    /** How many entries were left out, because they were already there or could not be restored. */
    var skipped = 0
        private set

    private var unreadable = 0
    private val problems = ArrayList<String>()

    /** Problems with single items that did not keep the section from being restored. */
    val warnings: List<String> get() = problems

    /** The number of entries that were skipped, including lines that could not be read at all. */
    val skippedTotal: Int get() = skipped + unreadable

    /** The lines of the section, one JSON object each. Lines that are not valid JSON objects are left out. */
    fun entries(): Sequence<JsonObject> = sequence {
        while (true) {
            val line = source.readLine() ?: break
            if (line.isBlank()) continue
            val entry = try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            }
            if (entry == null) {
                unreadable++
            } else {
                yield(entry)
            }
        }
    }

    /** Counts [count] more entries as restored. */
    fun restored(count: Int = 1) {
        restored += count
    }

    /** Counts [count] more entries as skipped. */
    fun skipped(count: Int = 1) {
        skipped += count
    }

    /** Notes a problem with an item, for the person who looks at the result. */
    fun warn(message: String) {
        if (problems.size < MAX_WARNINGS) problems.add(message)
    }

    private companion object {
        const val MAX_WARNINGS = 20
    }
}

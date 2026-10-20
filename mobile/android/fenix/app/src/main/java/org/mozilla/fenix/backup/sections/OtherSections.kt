/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mozilla.components.feature.top.sites.TopSite
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.putIfNotNull
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.ext.components

/**
 * The shortcuts the person pinned to the home screen. The shortcuts that came with the app are not saved, because the
 * app adds them itself. A site that is pinned already is not added again.
 */
internal class PinnedSitesSection(private val context: Context) : BackupSection {
    override val id = "pinned_sites"
    override val version = 1
    override val titleRes = R.string.backup_section_pinned_sites

    override suspend fun export(out: SectionWriter) {
        val sites = context.components.core.pinnedSiteStorage.getPinnedSites()
        for (site in sites.filterIsInstance<TopSite.Pinned>()) {
            out.write(
                buildJsonObject {
                    put("url", site.url)
                    putIfNotNull("title", site.title)
                    putIfNotNull("createdAt", site.createdAt)
                },
            )
        }
    }

    override suspend fun import(input: SectionReader) {
        val storage = context.components.core.pinnedSiteStorage
        val known = storage.getPinnedSites().map { it.url }.toHashSet()
        for (line in input.entries()) {
            val url = line.string("url")
            if (url.isNullOrEmpty() || url in known) {
                input.skipped()
                continue
            }
            storage.addPinnedSite(title = line.string("title") ?: url, url = url, isDefault = false)
            known.add(url)
            input.restored()
        }
    }
}

/**
 * The search engines the person added themselves, and which search engines are the default, hidden or turned off.
 *
 * A custom search engine is a small XML file. Engines that exist already are kept as they are. The choice of the
 * default search engine in the backup replaces the current choice, and the lists of hidden, disabled and added search
 * engines are joined with the lists that are there. The app reads its search engines when it starts, so the restored
 * ones show up after the app is restarted.
 */
internal class SearchEnginesSection(private val context: Context) : BackupSection {
    override val id = "search_engines"
    override val version = 1
    override val titleRes = R.string.backup_section_search_engines

    override suspend fun export(out: SectionWriter) {
        val directory = File(context.filesDir, ENGINES_DIRECTORY)
        for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
            if (!file.isFile || !ENGINE_FILE_NAME.matches(file.name)) continue
            if (file.length() > MAX_ENGINE_FILE_BYTES) {
                out.warn("A search engine is too large to be saved: ${file.name.take(MAX_NAME_IN_WARNING)}")
                continue
            }
            out.write(
                buildJsonObject {
                    put("kind", KIND_ENGINE)
                    put("file", file.name)
                    put("xml", file.readText(Charsets.UTF_8))
                },
            )
        }

        val all = PreferencesFile.SEARCH_METADATA.open(context).all
        for (key in all.keys.sorted()) {
            val line = PreferencesCodec.encode(PreferencesFile.SEARCH_METADATA.name, key, all[key])
            if (line == null) out.warn("A search setting has a type that cannot be saved: $key") else out.write(line)
        }
    }

    override suspend fun import(input: SectionReader) {
        val directory = File(context.filesDir, ENGINES_DIRECTORY)
        val preferences = PreferencesFile.SEARCH_METADATA.open(context)
        val editor = preferences.edit()
        for (line in input.entries()) {
            val handled = if (line.string("kind") == KIND_ENGINE) {
                restoreEngine(directory, line.string("file"), line.string("xml"))
            } else {
                line.string("file") == PreferencesFile.SEARCH_METADATA.name &&
                    PreferencesCodec.apply(editor, line, current = preferences, mergeSets = true)
            }
            if (handled) input.restored() else input.skipped()
        }
        if (!editor.commit()) throw IOException("The search settings could not be written.")
    }

    /** Writes the search engine file unless it is there already. Returns whether the file was written. */
    private fun restoreEngine(directory: File, name: String?, xml: String?): Boolean {
        // The name comes from a file that could have been made by anyone: it must not leave the folder.
        if (name == null || xml == null || !ENGINE_FILE_NAME.matches(name)) return false
        if (xml.length > MAX_ENGINE_FILE_BYTES) return false
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val target = File(directory, name)
        if (target.exists()) return false
        val temporary = File(directory, "$name.restoring")
        try {
            temporary.writeText(xml, Charsets.UTF_8)
            if (!temporary.renameTo(target)) throw IOException("Could not write $name")
        } finally {
            temporary.delete()
        }
        return true
    }

    private companion object {
        /** Where the search engines the person added are kept. It is decided by the search component. */
        const val ENGINES_DIRECTORY = "search-engines"
        const val KIND_ENGINE = "engine"
        const val MAX_ENGINE_FILE_BYTES = 1_000_000L
        const val MAX_NAME_IN_WARNING = 60

        /** The file of a search engine is named after its ID in URL-safe base64, with the extension .xml. */
        val ENGINE_FILE_NAME = Regex("[A-Za-z0-9_=-]{1,300}\\.xml")
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mozilla.components.browser.session.storage.serialize.BrowserStateReader
import mozilla.components.browser.session.storage.serialize.BrowserStateWriter
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.feature.tabgroups.storage.data.TabGroup
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupManager
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.array
import org.mozilla.fenix.backup.bool
import org.mozilla.fenix.backup.long
import org.mozilla.fenix.backup.obj
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.backup.strings
import org.mozilla.fenix.ext.components

/**
 * The tabs that are open, with the page history of each tab, which tab is selected, and the ID of each tab.
 *
 * The tabs are saved and restored with the same code the app uses to keep its tabs when it is closed
 * ([BrowserStateWriter] and [BrowserStateReader]), so what a tab remembers is the same. Private tabs are not saved,
 * just as the app does not keep them.
 *
 * A tab keeps its ID when it is restored, which is what ties it to the other things that are saved about it: whether it
 * is locked, its title, and the group it is in. Tabs that are already open (a tab with the same ID) are left alone,
 * so restoring a backup twice does not open the tabs twice. Restored tabs are added after the tabs that are open.
 */
internal class TabsSection(private val context: Context) : BackupSection {
    override val id = "tabs"
    override val version = 1
    override val titleRes = R.string.backup_section_tabs

    override suspend fun export(out: SectionWriter) {
        val state = context.components.core.store.state
        val writer = BrowserStateWriter()
        val directory = tempDirectory()
        for (tab in state.tabs) {
            if (tab.content.private) continue
            val file = AtomicFile(File(directory, "tab-${System.nanoTime()}.json"))
            try {
                if (!writer.writeTab(tab, file)) {
                    out.warn("A tab could not be saved: ${tab.content.url.take(MAX_URL_IN_WARNING)}")
                    continue
                }
                val json = file.openRead().bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
                out.write(
                    buildJsonObject {
                        put("id", tab.id)
                        if (tab.id == state.selectedTabId) put("selected", true)
                        put("tab", json)
                    },
                )
            } finally {
                file.delete()
            }
        }
    }

    override suspend fun import(input: SectionReader) {
        val components = context.components
        val store = components.core.store
        val engine = components.core.engine
        val reader = BrowserStateReader()
        val directory = tempDirectory()

        val taken = store.state.tabs.map { it.id }.toHashSet()
        val tabs = ArrayList<RecoverableTab>()
        var selectedTabId: String? = null
        for (line in input.entries()) {
            val tabJson = line.obj("tab")
            val tabId = line.string("id")
            if (tabJson == null || tabId == null || tabId in taken) {
                input.skipped()
                continue
            }
            val file = AtomicFile(File(directory, "tab-${System.nanoTime()}.json"))
            val tab = try {
                file.baseFile.writeText(tabJson.toString(), Charsets.UTF_8)
                reader.readTab(engine, file)
            } finally {
                file.delete()
            }
            if (tab == null || tab.state.private || tab.state.id in taken) {
                input.skipped()
                continue
            }
            taken.add(tab.state.id)
            tabs.add(tab)
            if (line.bool("selected") == true) selectedTabId = tab.state.id
        }
        if (tabs.isEmpty()) return

        store.dispatch(
            TabListAction.RestoreAction(
                tabs = tabs,
                selectedTabId = selectedTabId,
                restoreLocation = TabListAction.RestoreAction.RestoreLocation.END,
            ),
        )
        input.restored(tabs.size)

        // The app saves its tabs by itself a little after they change. Do it now, so that the restored tabs are not
        // lost when the app is closed right after the restore.
        val restoredIds = tabs.map { it.state.id }
        val arrived = withTimeoutOrNull(TABS_APPEAR_TIMEOUT_MILLIS) {
            while (!store.state.tabs.map { it.id }.containsAll(restoredIds)) delay(TABS_POLL_MILLIS)
            true
        }
        if (arrived == null) {
            input.warn("The restored tabs were slow to appear. They will be saved with the next change.")
            return
        }
        val saved = withContext(Dispatchers.IO) { components.core.sessionStorage.save(store.state) }
        if (!saved) {
            input.warn("The restored tabs could not be saved right away. They will be saved with the next change.")
        }
    }

    private fun tempDirectory(): File {
        val directory = File(BackupManager.tempDirectory(context), BackupManager.TABS_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        return directory
    }

    private companion object {
        const val MAX_URL_IN_WARNING = 100
        const val TABS_APPEAR_TIMEOUT_MILLIS = 5_000L
        const val TABS_POLL_MILLIS = 50L
    }
}

/**
 * The tab groups: their names and colors, whether they are closed, and which tabs are in them.
 *
 * Groups that exist already (same ID) are left alone. A tab that is in a group already is not moved to another one.
 * A group that is not closed and has none of its tabs open is not restored, because there would be nothing to show:
 * restore the tabs first, which the order of the sections does.
 */
internal class TabGroupsSection(private val context: Context) : BackupSection {
    override val id = "tab_groups"
    override val version = 1
    override val titleRes = R.string.backup_section_tab_groups

    override suspend fun export(out: SectionWriter) {
        val data = context.components.core.tabGroupRepository.tabGroupDataFlow.first()
        for (group in data.tabGroups) {
            out.write(
                buildJsonObject {
                    put("id", group.id)
                    put("title", group.title)
                    put("theme", group.theme)
                    put("closed", group.closed)
                    put("lastModified", group.lastModified)
                    put("tabIds", JsonArray(group.tabIds.map { JsonPrimitive(it) }))
                },
            )
        }
    }

    override suspend fun import(input: SectionReader) {
        val components = context.components
        val repository = components.core.tabGroupRepository
        val existing = repository.tabGroupDataFlow.first()
        val groupIds = existing.tabGroups.map { it.id }.toHashSet()
        val assignedTabIds = existing.tabGroupAssignments.keys.toHashSet()
        val openTabIds = components.core.store.state.tabs.map { it.id }.toHashSet()

        for (line in input.entries()) {
            val groupId = line.string("id")
            if (groupId == null || groupId in groupIds) {
                input.skipped()
                continue
            }
            val closed = line.bool("closed") ?: false
            val tabIds = line.array("tabIds")?.strings().orEmpty()
                .filter { it !in assignedTabIds && (closed || it in openTabIds) }
            if (!closed && tabIds.isEmpty()) {
                input.skipped()
                continue
            }
            repository.createTabGroupWithTabs(
                TabGroup(
                    id = groupId,
                    title = line.string("title").orEmpty(),
                    // A theme the app does not know is shown with its default theme.
                    theme = line.string("theme").orEmpty(),
                    closed = closed,
                    lastModified = line.long("lastModified") ?: System.currentTimeMillis(),
                    tabIds = tabIds,
                ),
            )
            groupIds.add(groupId)
            assignedTabIds.addAll(tabIds)
            input.restored()
        }
    }
}

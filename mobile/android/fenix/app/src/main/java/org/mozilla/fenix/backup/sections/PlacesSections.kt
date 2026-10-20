/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.VisitType
import mozilla.components.concept.storage.bookmarks.InsertableBookmarkTreeNode
import mozilla.components.concept.storage.bookmarks.InsertableBookmarkTreeRoot
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.int
import org.mozilla.fenix.backup.long
import org.mozilla.fenix.backup.putIfNotNull
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.ext.components

/**
 * The bookmarks: every folder, bookmark and separator below the four folders the app has built in (menu, toolbar, other
 * bookmarks and mobile bookmarks), with their order and dates.
 *
 * Restoring merges the bookmarks into the ones that are there. A folder is matched by its name, and a bookmark by its
 * address inside the folder it is in. What exists is not changed or deleted. A folder that is not there is added with
 * everything in it, and keeps the dates of the bookmarks. A bookmark that is added to a folder that is there gets the
 * date of the restore.
 */
internal class BookmarksSection(private val context: Context) : BackupSection {
    override val id = "bookmarks"
    override val version = 1
    override val titleRes = R.string.backup_section_bookmarks

    override suspend fun export(out: SectionWriter) {
        val storage = context.components.core.bookmarksStorage
        val root = storage.getTree(BookmarkRoot.Root.id, recursive = true).getOrThrow() ?: return
        for (builtIn in root.children.orEmpty()) {
            for (child in builtIn.children.orEmpty()) write(out, child)
        }
    }

    private fun write(out: SectionWriter, node: BookmarkNode) {
        out.write(
            buildJsonObject {
                put("kind", node.type.toKind())
                put("guid", node.guid)
                putIfNotNull("parent", node.parentGuid)
                node.position?.let { put("position", it.toInt()) }
                putIfNotNull("title", node.title)
                putIfNotNull("url", node.url)
                put("added", node.dateAdded)
                put("modified", node.lastModified)
            },
        )
        for (child in node.children.orEmpty()) write(out, child)
    }

    /** A bookmark, folder or separator of a backup file. */
    private class Entry(
        val kind: String,
        val guid: String,
        val title: String?,
        val url: String?,
        val position: Int,
        val added: Long,
        val modified: Long,
    )

    override suspend fun import(input: SectionReader) {
        val storage = context.components.core.bookmarksStorage

        val childrenOf = HashMap<String, MutableList<Entry>>()
        var total = 0
        for (line in input.entries()) {
            val guid = line.string("guid")
            val parent = line.string("parent")
            val kind = line.string("kind")
            if (guid == null || parent == null || kind == null || kind !in KINDS) {
                input.skipped()
                continue
            }
            total++
            childrenOf.getOrPut(parent) { ArrayList() }.add(
                Entry(
                    kind = kind,
                    guid = guid,
                    title = line.string("title"),
                    url = line.string("url"),
                    position = line.int("position") ?: Int.MAX_VALUE,
                    added = line.long("added") ?: 0L,
                    modified = line.long("modified") ?: 0L,
                ),
            )
        }
        for (entries in childrenOf.values) entries.sortBy { it.position }

        val merger = Merger(storage, childrenOf, input)
        for (builtIn in BUILT_IN_FOLDERS) {
            val entries = childrenOf[builtIn.id].orEmpty()
            if (entries.isEmpty()) continue
            val folder = storage.getTree(builtIn.id, recursive = true).getOrThrow()
            if (folder == null) {
                input.warn("A bookmarks folder of this app was not found: ${builtIn.id}")
                continue
            }
            merger.merge(folder, entries)
        }
        // Entries that hang below a folder that is not in the file cannot be placed anywhere.
        input.skipped((total - merger.handled).coerceAtLeast(0))
    }

    private class Merger(
        private val storage: BookmarksStorage,
        private val childrenOf: Map<String, List<Entry>>,
        private val input: SectionReader,
    ) {
        /** How many entries were looked at, whether they were added or already there. */
        var handled = 0
            private set

        private val seen = HashSet<String>()

        @Suppress("CyclomaticComplexMethod")
        suspend fun merge(folder: BookmarkNode, entries: List<Entry>) {
            val existing = folder.children.orEmpty()
            val knownUrls = existing.filter { it.type == BookmarkNodeType.ITEM }.mapNotNull { it.url }.toHashSet()
            for (entry in entries) {
                if (!seen.add(entry.guid)) continue
                handled++
                currentCoroutineContext().ensureActive()
                when (entry.kind) {
                    KIND_FOLDER -> {
                        val sameFolder = existing.firstOrNull {
                            it.type == BookmarkNodeType.FOLDER && it.title == entry.title
                        }
                        if (sameFolder != null) {
                            input.skipped()
                            merge(sameFolder, childrenOf[entry.guid].orEmpty())
                        } else {
                            addFolder(folder, entry)
                        }
                    }
                    KIND_ITEM -> {
                        val url = entry.url
                        if (url == null || url in knownUrls) {
                            input.skipped()
                        } else if (addItem(folder, entry, url)) {
                            knownUrls.add(url)
                        }
                    }
                    // A separator is only a visual divider: it is restored with a folder that is added, not into a
                    // folder that is there already.
                    else -> input.skipped()
                }
            }
        }

        private suspend fun addItem(folder: BookmarkNode, entry: Entry, url: String): Boolean {
            return try {
                storage.addItem(folder.guid, url, entry.title ?: url, null).getOrThrow()
                input.restored()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                input.skipped()
                input.warn("A bookmark could not be added: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }

        private suspend fun addFolder(parent: BookmarkNode, entry: Entry) {
            val tree = toTree(entry)
            try {
                storage.insertTree(InsertableBookmarkTreeRoot(parent.guid, tree)).getOrThrow()
                input.restored(1 + countBelow(tree))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                input.skipped(1 + countBelow(tree))
                input.warn("A bookmarks folder could not be added: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private fun toTree(entry: Entry): InsertableBookmarkTreeNode.Folder {
            val children = ArrayList<InsertableBookmarkTreeNode>()
            for (child in childrenOf[entry.guid].orEmpty()) {
                if (!seen.add(child.guid)) continue
                handled++
                when (child.kind) {
                    KIND_FOLDER -> children.add(toTree(child))
                    KIND_ITEM -> child.url?.let { url ->
                        children.add(
                            InsertableBookmarkTreeNode.Item(child.title, url, child.added, child.modified, null),
                        )
                    }
                    else -> children.add(InsertableBookmarkTreeNode.Separator(child.added, child.modified, null))
                }
            }
            return InsertableBookmarkTreeNode.Folder(entry.title, entry.added, entry.modified, null, children)
        }

        private fun countBelow(folder: InsertableBookmarkTreeNode.Folder): Int {
            var count = 0
            for (child in folder.children) {
                count += if (child is InsertableBookmarkTreeNode.Folder) 1 + countBelow(child) else 1
            }
            return count
        }
    }

    private companion object {
        const val KIND_FOLDER = "folder"
        const val KIND_ITEM = "item"
        const val KIND_SEPARATOR = "separator"
        val KINDS = setOf(KIND_FOLDER, KIND_ITEM, KIND_SEPARATOR)

        /** The folders that every set of bookmarks has. Everything else is inside one of them. */
        val BUILT_IN_FOLDERS =
            listOf(BookmarkRoot.Menu, BookmarkRoot.Toolbar, BookmarkRoot.Unfiled, BookmarkRoot.Mobile)

        fun BookmarkNodeType.toKind() = when (this) {
            BookmarkNodeType.FOLDER -> KIND_FOLDER
            BookmarkNodeType.SEPARATOR -> KIND_SEPARATOR
            BookmarkNodeType.ITEM -> KIND_ITEM
        }
    }
}

/**
 * The browsing history: the address, title, type and time of every visit that the history screen shows.
 *
 * The history of the app cannot be given the time of a visit, so restored visits are dated with the time of the
 * restore, in the order of the original visits. To keep how much each site was used, a site that was visited often is
 * restored with up to [MAX_VISITS_PER_SITE] visits. Sites that are in the history already are left alone, which also
 * means that restoring a backup twice does not add anything the second time.
 */
internal class HistorySection(private val context: Context) : BackupSection {
    override val id = "history"
    override val version = 1
    override val titleRes = R.string.backup_section_history

    override suspend fun export(out: SectionWriter) {
        val storage = context.components.core.historyStorage
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val visits = storage.getVisitsPaginated(offset, PAGE_SIZE.toLong(), EXCLUDED_TYPES)
            for (visit in visits) {
                out.write(
                    buildJsonObject {
                        put("url", visit.url)
                        putIfNotNull("title", visit.title)
                        put("time", visit.visitTime)
                        put("type", visit.visitType.type)
                    },
                )
            }
            if (visits.size < PAGE_SIZE) break
            offset += visits.size
        }
    }

    /** What a backup file says about the visits of one address. */
    private class Site(val url: String) {
        var title: String? = null
        var lastTime = Long.MIN_VALUE
        var lastType = VisitType.LINK
        var visits = 0
    }

    override suspend fun import(input: SectionReader) {
        val storage = context.components.core.historyStorage

        val sites = LinkedHashMap<String, Site>()
        for (line in input.entries()) {
            val url = line.string("url")
            if (url == null) {
                input.skipped()
                continue
            }
            val site = sites.getOrPut(url) { Site(url) }
            site.visits++
            val time = line.long("time") ?: 0L
            if (time >= site.lastTime) {
                site.lastTime = time
                site.lastType = VisitType.entries.firstOrNull { it.type == line.int("type") } ?: VisitType.LINK
                line.string("title")?.takeIf { it.isNotBlank() }?.let { site.title = it }
            }
        }

        // The oldest sites first, so the most recent ones also end up being the most recent in the history.
        val ordered = sites.values.sortedBy { it.lastTime }
        for (chunk in ordered.chunked(CHUNK_SIZE)) {
            currentCoroutineContext().ensureActive()
            val known = storage.getVisited(chunk.map { it.url })
            chunk.forEachIndexed { index, site ->
                if (known.getOrNull(index) == true || !storage.canAddUri(site.url)) {
                    input.skipped()
                    return@forEachIndexed
                }
                repeat(minOf(site.visits, MAX_VISITS_PER_SITE)) {
                    storage.recordVisit(site.url, PageVisit(site.lastType))
                }
                site.title?.let { storage.recordObservation(site.url, PageObservation(title = it)) }
                input.restored()
            }
        }
    }

    internal companion object {
        const val MAX_VISITS_PER_SITE = 3
        private const val PAGE_SIZE = 500
        private const val CHUNK_SIZE = 200

        /** The visits the history screen does not show: they are not something a person visited. */
        private val EXCLUDED_TYPES = listOf(
            VisitType.DOWNLOAD,
            VisitType.REDIRECT_PERMANENT,
            VisitType.REDIRECT_TEMPORARY,
            VisitType.RELOAD,
            VisitType.EMBED,
            VisitType.FRAMED_LINK,
        )
    }
}

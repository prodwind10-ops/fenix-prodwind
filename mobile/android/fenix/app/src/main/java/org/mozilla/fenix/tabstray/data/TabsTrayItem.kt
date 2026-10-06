/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.data

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.asImageBitmap
import java.util.UUID
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.isActive
import mozilla.components.compose.base.theme.layout.AcornWindowSize
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import org.mozilla.fenix.compose.TabThumbnailImageData
import org.mozilla.fenix.ext.maxActiveTime
import org.mozilla.fenix.tabstray.ext.toDisplayTitle

private const val SMALL_WINDOW_FULL_EXPAND_TAB_COUNT = 4
private const val MEDIUM_LARGE_WINDOW_FULL_EXPAND_TAB_COUNT = 8

/** Data entity representing items in the Tabs Tray. */
@Immutable
sealed interface TabsTrayItem {
    /** The ID of the item. */
    val id: String

    /** Whether the entity represents a Homepage item. */
    val isHomepageItem: Boolean

    /** Whether the entity is focused. */
    val isFocused: Boolean

    /** Whether the entity has active media. */
    val isMediaActive: Boolean

    /**
     * Data entity representing a tab in the Tabs Tray.
     *
     * @property id The ID of the item.
     * @property url The URL of the tab.
     * @property title The tab's display-friendly title.
     * @property inactive Whether the tab is inactive.
     * @property private Whether the tab is private.
     * @property icon The bitmap of the tab's favicon.
     * @property isMediaActive Whether the tab has active media.
     * @property lastAccess The last time this tab was accessed.
     * @property isFocused Whether the tab is focused. This is only set when the tab data model is generated.
     * @property locked Whether the tab is locked. A locked tab is excluded from "close all tabs" and cannot be
     *   closed individually until unlocked.
     * @property originalTitle The tab's page title, before any custom title the user gave the tab is applied. Equal
     *   to [title] unless the tab has been renamed.
     * @property rootUrl For a locked tab, the URL the tab was showing when it was locked, which the user can return
     *   the tab to. Null for unlocked tabs and for tabs locked before root URLs were recorded.
     */
    @Immutable
    data class Tab(
        override val id: String,
        val url: String,
        val title: String,
        val inactive: Boolean,
        val private: Boolean,
        val icon: Bitmap?,
        override val isMediaActive: Boolean,
        val lastAccess: Long,
        override val isFocused: Boolean,
        val locked: Boolean = false,
        val originalTitle: String = title,
        val rootUrl: String? = null,
    ) : TabsTrayItem {
        override val isHomepageItem: Boolean = url.equals(ABOUT_HOME_URL, ignoreCase = true)

        constructor(
            tab: TabSessionState,
            isFocused: Boolean = false,
            locked: Boolean = false,
        ) : this(
            id = tab.id,
            url = tab.content.url,
            title = tab.toDisplayTitle(),
            inactive = !tab.isActive(maxActiveTime = maxActiveTime),
            private = tab.content.private,
            icon = tab.content.icon,
            isMediaActive = tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING,
            lastAccess = tab.lastAccess,
            isFocused = isFocused,
            locked = locked,
        )

        /** Constructs a [TabThumbnailImageData] from the given tab data */
        fun toThumbnailImageData(): TabThumbnailImageData =
            TabThumbnailImageData(
                tabId = id,
                isPrivate = private,
                tabUrl = url,
                tabIcon = icon?.asImageBitmap(),
            )
    }

    /**
     * Data entity representing a tab group in the Tabs Tray.
     *
     * @property id The group's ID.
     * @property title The group's display title.
     * @property theme The group's [TabGroupTheme].
     * @property tabs The set of [Tab]s within the group.
     * @property closed Whether the group is closed and does not appear in the main tab item list.
     * @property lastModified Timestamp indicating the last time this group was updated.
     * @property isFocused Whether the tab is focused. This is only set when the tab data model is generated.
     * @property initialScrollIndex The index to open the tab group to when first expanded. This is only set when the
     *   tab data model is generated.
     * @property locked Whether the tab group is locked. A locked group is excluded from "close all tabs" and cannot
     *   be closed or deleted until unlocked. Locking a group does not lock the individual tabs within it.
     */
    @Immutable
    data class TabGroup(
        override val id: String = UUID.randomUUID().toString(),
        val title: String,
        val theme: TabGroupTheme,
        val tabs: List<Tab>,
        val closed: Boolean = false,
        val lastModified: Long = 0L,
        override val isFocused: Boolean = false,
        val initialScrollIndex: Int = 0,
        val locked: Boolean = false,
    ) : TabsTrayItem {
        override val isHomepageItem: Boolean = false
        override val isMediaActive: Boolean
            get() = tabs.any { it.isMediaActive }

        /** Retrieves the thumbnail image data for the first 4 tabs in the group's tab collection. */
        val thumbnails by lazy {
            tabs.take(4).map { it.toThumbnailImageData() }
        }

        /**
         * Helper function to determine whether to fully expand a tab group.
         *
         * @param windowSize The [AcornWindowSize] of the app window.
         */
        fun shouldFullyExpandOnFirstOpen(windowSize: AcornWindowSize): Boolean =
            (windowSize == AcornWindowSize.Small && tabs.size >= SMALL_WINDOW_FULL_EXPAND_TAB_COUNT) ||
                (windowSize.isNotSmall() && tabs.size >= MEDIUM_LARGE_WINDOW_FULL_EXPAND_TAB_COUNT)
    }

    /**
     * @param text The text to search for.
     * @return true if the item contains the given text.
     */
    fun contains(text: String): Boolean {
        return when (this) {
            is Tab -> {
                url.contains(text, ignoreCase = true) ||
                    title.contains(text, ignoreCase = true) ||
                    originalTitle.contains(text, ignoreCase = true)
            }
            is TabGroup -> false
        }
    }
}

internal fun createTab(
    url: String,
    id: String = UUID.randomUUID().toString(),
    title: String = "",
    inactive: Boolean = false,
    private: Boolean = false,
    lastAccess: Long = 0L,
    isFocused: Boolean = false,
    isMediaActive: Boolean = false,
    locked: Boolean = false,
): TabsTrayItem.Tab =
    TabsTrayItem.Tab(
        id = id,
        url = url,
        title = title,
        inactive = inactive,
        private = private,
        icon = null,
        isMediaActive = isMediaActive,
        lastAccess = lastAccess,
        isFocused = isFocused,
        locked = locked,
    )

internal fun createTabGroup(
    id: String = UUID.randomUUID().toString(),
    title: String = "",
    theme: TabGroupTheme = TabGroupTheme.default,
    tabs: List<TabsTrayItem.Tab> = listOf(),
    closed: Boolean = false,
    lastModified: Long = 0L,
    isFocused: Boolean = false,
    initialScrollIndex: Int = 0,
    locked: Boolean = false,
): TabsTrayItem.TabGroup =
    TabsTrayItem.TabGroup(
        id = id,
        title = title,
        theme = theme,
        tabs = tabs,
        closed = closed,
        lastModified = lastModified,
        isFocused = isFocused,
        initialScrollIndex = initialScrollIndex,
        locked = locked,
    )

/**
 * Returns a copy of this [TabsTrayItem.Tab] with the user's persisted customizations re-applied: [TabsTrayItem.Tab.locked]
 * based on whether its [TabsTrayItem.Tab.id] is present in [lockedTabIds], [TabsTrayItem.Tab.title] set to the custom
 * title in [customTitles] when there is one (otherwise the tab's page title), and [TabsTrayItem.Tab.rootUrl] set from
 * [rootUrls] for locked tabs. Used on tabs freshly rebuilt from
 * [mozilla.components.browser.state.state.BrowserState], which knows nothing about locking or custom titles.
 *
 * Every call site must pass the current [customTitles] and [rootUrls]: leaving them at their empty defaults removes
 * custom titles and root URLs from the result.
 */
internal fun TabsTrayItem.Tab.withLockApplied(
    lockedTabIds: Set<String>,
    customTitles: Map<String, String> = emptyMap(),
    rootUrls: Map<String, String> = emptyMap(),
): TabsTrayItem.Tab {
    val isLocked = id in lockedTabIds
    return copy(
        locked = isLocked,
        title = customTitles[id] ?: originalTitle,
        rootUrl = if (isLocked) rootUrls[id] else null,
    )
}

/**
 * Returns a copy of this [TabsTrayItem.TabGroup] with [locked] set based on whether its [TabsTrayItem.TabGroup.id] is
 * present in [lockedGroupIds], and with the customizations re-applied to each tab within the group (see
 * [TabsTrayItem.Tab.withLockApplied]). Locking a group does not lock the individual tabs within it, so both sets are
 * applied independently.
 */
internal fun TabsTrayItem.TabGroup.withLockApplied(
    lockedTabIds: Set<String>,
    lockedGroupIds: Set<String>,
    customTitles: Map<String, String> = emptyMap(),
    rootUrls: Map<String, String> = emptyMap(),
): TabsTrayItem.TabGroup =
    copy(
        locked = id in lockedGroupIds,
        tabs =
            tabs.map {
                it.withLockApplied(lockedTabIds = lockedTabIds, customTitles = customTitles, rootUrls = rootUrls)
            },
    )

/**
 * Returns a copy of this list of [TabsTrayItem]s with lock status, custom titles and root URLs re-applied to every
 * [TabsTrayItem.Tab] and [TabsTrayItem.TabGroup]. Intended to be called on tab data freshly received from
 * [mozilla.components.browser.state.state.BrowserState] before it enters
 * [TabsTrayState][org.mozilla.fenix.tabstray.redux.state.TabsTrayState], since that data has no concept of tab or group
 * locking or of custom titles.
 */
internal fun List<TabsTrayItem>.withLockApplied(
    lockedTabIds: Set<String>,
    lockedGroupIds: Set<String>,
    customTitles: Map<String, String> = emptyMap(),
    rootUrls: Map<String, String> = emptyMap(),
): List<TabsTrayItem> = map { item ->
    when (item) {
        is TabsTrayItem.Tab ->
            item.withLockApplied(lockedTabIds = lockedTabIds, customTitles = customTitles, rootUrls = rootUrls)
        is TabsTrayItem.TabGroup ->
            item.withLockApplied(
                lockedTabIds = lockedTabIds,
                lockedGroupIds = lockedGroupIds,
                customTitles = customTitles,
                rootUrls = rootUrls,
            )
    }
}

internal fun List<TabsTrayItem>.toTabList(): List<TabsTrayItem.Tab> = flatMap {
    when (it) {
        is TabsTrayItem.Tab -> listOf(it)
        is TabsTrayItem.TabGroup -> it.tabs
    }
}

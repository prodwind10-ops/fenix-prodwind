/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.controller

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.tabstray.repository.uistate.TabManagerUiStateRepository

/**
 * Navigates each of the tabs with [tabIds] that is locked and has a root URL back to that URL.
 *
 * A tab that is already showing its root URL is skipped so it keeps its scroll position and page state. This is a
 * normal navigation, so the page the tab was on stays in its history and the back button returns to it.
 *
 * @param browserStore The [BrowserStore] holding the tabs.
 * @param sessionUseCases Used to load the root URL into each tab.
 * @param tabIds The IDs of the tabs to consider. Tabs that are not locked or have no root URL are ignored.
 * @param lockedTabIds The IDs of all locked tabs.
 * @param rootUrls The root URL of each locked tab, keyed by tab ID.
 */
internal fun returnLockedTabsToRoot(
    browserStore: BrowserStore,
    sessionUseCases: SessionUseCases,
    tabIds: Collection<String>,
    lockedTabIds: Set<String>,
    rootUrls: Map<String, String>,
) {
    tabIds.filter { it in lockedTabIds }.forEach { tabId ->
        val rootUrl = rootUrls[tabId] ?: return@forEach
        if (browserStore.state.findTab(tabId)?.content?.url != rootUrl) {
            sessionUseCases.loadUrl(url = rootUrl, sessionId = tabId)
        }
    }
}

/**
 * Returns every locked tab to its root URL when the app is launched, the same as the Tab Manager's "return locked
 * tabs to root pages" button.
 *
 * Tabs are restored asynchronously after launch and the locked-tab data is read from disk, so [run] waits for both
 * before acting.
 *
 * @param browserStore The [BrowserStore] holding the tabs.
 * @param sessionUseCases Used to load the root URL into each tab.
 * @param uiStateRepository Provides the persisted locked tab IDs and root URLs.
 */
internal class ReturnLockedTabsToRootOnLaunch(
    private val browserStore: BrowserStore,
    private val sessionUseCases: SessionUseCases,
    private val uiStateRepository: TabManagerUiStateRepository,
) {

    /** Waits until the tabs have been restored and the persisted UI state is loaded, then returns the tabs. */
    suspend fun run() {
        browserStore.flow().first { it.restoreComplete }
        val persistedUiState = uiStateRepository.uiState.filterNotNull().first()

        returnLockedTabsToRoot(
            browserStore = browserStore,
            sessionUseCases = sessionUseCases,
            tabIds = browserStore.state.tabs.map { it.id },
            lockedTabIds = persistedUiState.lockedTabIds,
            rootUrls = persistedUiState.lockedTabRootUrls,
        )
    }
}

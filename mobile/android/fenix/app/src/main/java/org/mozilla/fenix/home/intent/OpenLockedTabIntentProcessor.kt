/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.intent

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.shortcut.LockedTabShortcut
import org.mozilla.fenix.tabstray.controller.returnLockedTabsToRoot
import org.mozilla.fenix.tabstray.repository.uistate.DefaultTabManagerUiStateRepository
import org.mozilla.fenix.tabstray.repository.uistate.TabManagerUiStateRepository
import org.mozilla.fenix.utils.Settings

/**
 * Handles a home screen shortcut created by [LockedTabShortcut]: selects the tab whose ID the intent carries and shows
 * it in the browser, without creating a new tab. See [LockedTabShortcut.ACTION_OPEN_LOCKED_TAB] and
 * [LockedTabShortcut.EXTRA_TAB_ID].
 *
 * If the tab is locked and has a root URL, it is returned to that URL before it is shown, the same as the Tab Manager's
 * "return locked tabs to root pages" button, so opening the shortcut starts from the page the tab was locked on. A tab
 * that is already showing its root URL is left as it is, and so is a tab that is no longer locked.
 *
 * Which tabs are locked, and their root URLs, are saved on disk, so the tab is opened as soon as that data has been
 * read. When the app is already running that takes a moment. On a cold start the tabs are restored asynchronously, so
 * the tab does not exist yet when the intent arrives; the processor then waits for the restore to finish first.
 */
class OpenLockedTabIntentProcessor(private val activity: HomeActivity) : HomeIntentProcessor {

    // The saved lock data is read through the same repository the Tab Manager uses. It is created on first use so
    // that nothing is read for this when no shortcut is opened.
    private val uiStateRepository: TabManagerUiStateRepository by lazy {
        DefaultTabManagerUiStateRepository(
            context = activity.applicationContext,
            stateFlowScope = activity.lifecycleScope,
        )
    }

    override fun process(intent: Intent, navController: NavController, out: Intent, settings: Settings): Boolean {
        if (intent.action != LockedTabShortcut.ACTION_OPEN_LOCKED_TAB) {
            return false
        }
        val tabId = intent.getStringExtra(LockedTabShortcut.EXTRA_TAB_ID) ?: return false
        val store = activity.components.core.store

        if (store.state.restoreComplete) {
            // If the tab no longer exists (it was closed), fall back to the app's normal behavior.
            if (store.state.findTab(tabId) == null) {
                return false
            }

            activity.lifecycleScope.launch { openTab(store, tabId) }
            return true
        }

        activity.lifecycleScope.launch {
            store.flow().first { it.restoreComplete }
            if (!openTab(store, tabId)) {
                activity.navigateToBrowserOnColdStart()
            }
        }
        return true
    }

    /**
     * Returns the tab to its root URL if it is locked, then selects it and shows it in the browser.
     *
     * @return Whether the tab was opened, which is false when it no longer exists.
     */
    private suspend fun openTab(store: BrowserStore, tabId: String): Boolean {
        // The saved data is read before looking for the tab, since the tab could be closed while waiting for it.
        val persistedUiState =
            withTimeoutOrNull(PERSISTED_UI_STATE_TIMEOUT_MS) { uiStateRepository.uiState.filterNotNull().first() }

        if (store.state.findTab(tabId) == null) {
            return false
        }

        // The root URL is loaded before the tab is selected, so the tab is never shown on the page it was left on.
        // Without the saved data (it could not be read in time) the tab is opened as it is.
        if (persistedUiState != null) {
            returnLockedTabsToRoot(
                browserStore = store,
                sessionUseCases = activity.components.useCases.sessionUseCases,
                tabIds = listOf(tabId),
                lockedTabIds = persistedUiState.lockedTabIds,
                rootUrls = persistedUiState.lockedTabRootUrls,
            )
        }

        activity.components.useCases.tabsUseCases.selectTab(tabId)
        activity.openToBrowser(BrowserDirection.FromGlobal)
        return true
    }

    private companion object {
        // How long to wait for the saved lock data before opening the tab without returning it to its root URL, so a
        // problem reading the data can never stop the shortcut from opening the tab.
        const val PERSISTED_UI_STATE_TIMEOUT_MS = 3_000L
    }
}

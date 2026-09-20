/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.SnackbarHostState
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingModeManager
import org.mozilla.fenix.components.usecases.FenixBrowserUseCases
import org.mozilla.fenix.ext.actualInactiveTabs
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.removeAllActiveUnlockedNormalTabs
import org.mozilla.fenix.ext.removeAllUnlockedNormalTabs
import org.mozilla.fenix.ext.removeAllUnlockedPrivateTabs
import org.mozilla.fenix.ext.tabClosedUndoMessage
import org.mozilla.fenix.ext.tabsClosedUndoMessage
import org.mozilla.fenix.home.HomeScreenViewModel.Companion.ALL_ACTIVE_NORMAL_TABS
import org.mozilla.fenix.home.HomeScreenViewModel.Companion.ALL_NORMAL_TABS
import org.mozilla.fenix.home.HomeScreenViewModel.Companion.ALL_PRIVATE_TABS
import org.mozilla.fenix.tabstray.repository.uistate.TabManagerUiStateRepository
import org.mozilla.fenix.utils.Settings
import org.mozilla.fenix.utils.allowUndo
import org.mozilla.fenix.utils.getUndoDelay

/**
 * Delegate to handle tab removal and undo actions in the homepage.
 *
 * @param context An Android [Context].
 * @param viewModel [HomeScreenViewModel] containing the data on the sessions to delete.
 * @param browserStore The [BrowserStore] that holds the currently open tabs.
 * @param browsingModeManager [BrowsingModeManager] used for fetching the current browsing mode.
 * @param navController [NavController] used for navigation.
 * @param tabsUseCases The [TabsUseCases] instance to perform tab actions.
 * @param fenixBrowserUseCases [FenixBrowserUseCases] used for adding new homepage tabs.
 * @param settings [Settings] used to check the application shared preferences.
 * @param snackbarHostState The [SnackbarHostState] used to display snackbars.
 * @param viewLifecycleScope The [CoroutineScope] to use for launching coroutines.
 * @param uiStateRepository [TabManagerUiStateRepository] used to read which tabs are currently locked, so that
 *   locked tabs are excluded from a queued "close all tabs" action reaching the home screen. Backed by the same
 *   on-disk storage the Tab Manager itself writes to, so lock status is consistent between the two.
 */
@Suppress("LongParameterList")
class TabsCleanupFeature(
    private val context: Context,
    private val viewModel: HomeScreenViewModel,
    private val browserStore: BrowserStore,
    private val browsingModeManager: BrowsingModeManager,
    private val navController: NavController,
    private val tabsUseCases: TabsUseCases,
    private val fenixBrowserUseCases: FenixBrowserUseCases,
    private val settings: Settings,
    private val snackbarHostState: SnackbarHostState,
    private val viewLifecycleScope: CoroutineScope,
    private val uiStateRepository: TabManagerUiStateRepository,
) : LifecycleAwareFeature {

    /** Removes the sessions that have been queued for deletion when the home screen is started. */
    override fun start() {
        viewModel.sessionToDelete?.also {
            if (it == ALL_NORMAL_TABS || it == ALL_PRIVATE_TABS || it == ALL_ACTIVE_NORMAL_TABS) {
                removeAllTabsAndShowSnackbar(it)
            } else {
                removeTabAndShowSnackbar(it)
            }
        }

        viewModel.sessionToDelete = null
    }

    override fun stop() = Unit

    /**
     * Shows an undo snackbar after removing one or more tabs.
     *
     * @param message The message to display in the snackbar.
     * @param onCancel The action to perform when the user clicks the "Undo" button.
     */
    @VisibleForTesting
    internal fun showUndoSnackbar(message: String, onCancel: () -> Unit) {
        viewLifecycleScope.allowUndo(
            snackbarHostState = snackbarHostState,
            message = message,
            undoActionTitle = context.getString(R.string.snackbar_deleted_undo),
            onCancel = onCancel,
            operation = {},
            undoDelay = context.components.settings.getUndoDelay(),
        )
    }

    private fun removeAllTabsAndShowSnackbar(sessionCode: String) {
        // The undo action restores the selected tab. Check if the selected tab is a
        // homepage tab before the tabs are removed.
        val isRestoringHomepageTab = browserStore.state.selectedTab?.content?.url == ABOUT_HOME_URL

        val tabsCount =
            when (sessionCode) {
                ALL_PRIVATE_TABS -> removeAllPrivateTabs()
                ALL_NORMAL_TABS -> removeAllNormalTabs()
                ALL_ACTIVE_NORMAL_TABS -> removeAllActiveNormalTabs()
                else -> return
            }

        var tabId: String? = null
        if (settings.enableHomepageAsNewTab) {
            // Add a new tab after all the tabs are removed to ensure there's always 1 tab.
            // Hold onto the new tab ID so that the new tab can be removed if the tabs are restored
            // by the undo action.
            tabId = fenixBrowserUseCases.addNewHomepageTab(private = browsingModeManager.mode.isPrivate)
        }

        showUndoSnackbar(
            message = context.tabsClosedUndoMessage(count = tabsCount),
            onCancel = {
                onUndoTabsRemoved(
                    tabId = tabId,
                    isRestoringHomepageTab = isRestoringHomepageTab,
                )
            },
        )
    }

    private fun removeAllPrivateTabs(): Int =
        tabsUseCases.removeAllUnlockedPrivateTabs(
            state = browserStore.state,
            lockedTabIds = getLockedPrivateTabIds(),
        )

    private fun removeAllNormalTabs(): Int =
        tabsUseCases.removeAllUnlockedNormalTabs(
            state = browserStore.state,
            lockedTabIds = getLockedNormalTabIds(),
        )

    private fun removeAllActiveNormalTabs(): Int =
        tabsUseCases.removeAllActiveUnlockedNormalTabs(
            state = browserStore.state,
            settings = settings,
            lockedTabIds = getLockedNormalTabIds(),
        )

    /**
     * Reads the current [org.mozilla.fenix.tabstray.repository.uistate.data.PersistedUIState] synchronously from
     * [uiStateRepository]. Safe to call from [start], which is not a suspend function: [TabManagerUiStateRepository.uiState]
     * is a [kotlinx.coroutines.flow.StateFlow] that always has a current value once initialized, and the persisted
     * state is expected to already be loaded by the time the home screen becomes visible.
     *
     * NOTE: this only covers standalone locked tabs, not tabs inside a locked tab group. Group membership is
     * resolved by the Tab Manager's own storage middleware, which this lightweight home-screen feature does not
     * depend on. A tab inside a locked group is fully protected when "close all tabs" is triggered from the Tab
     * Manager itself; on this specific path (reached only when homepage-as-new-tab is disabled), such a tab may
     * still be closed if it is not also individually locked.
     */
    private fun getLockedNormalTabIds(): Set<String> = uiStateRepository.uiState.value?.let { uiState ->
        val standaloneTabIds = browserStore.state.normalTabs.map { it.id }.toSet()
        // The persisted set may include tab IDs no longer open, or tabs now inside a tab group whose group-level
        // lock is tracked separately; intersecting with currently open normal tabs keeps this set accurate.
        uiState.lockedTabIds intersect standaloneTabIds
    } ?: emptySet()

    private fun getLockedPrivateTabIds(): Set<String> = uiStateRepository.uiState.value?.let { uiState ->
        val privateTabIds = browserStore.state.privateTabs.map { it.id }.toSet()
        uiState.lockedTabIds intersect privateTabIds
    } ?: emptySet()

    private fun removeTabAndShowSnackbar(sessionId: String) {
        val tab = browserStore.state.findTab(sessionId) ?: return
        val isPrivate = browsingModeManager.mode.isPrivate

        val lockedTabIds = if (isPrivate) getLockedPrivateTabIds() else getLockedNormalTabIds()
        if (sessionId in lockedTabIds) {
            // The tab is locked (or, for a normal tab, this only accounts for a standalone lock — see the
            // caveat on getLockedNormalTabIds() above regarding tabs inside a locked group) and should not be
            // closed until unlocked.
            return
        }

        // Check if this is the last tab being removed.
        val hasTabsRemaining =
            if (isPrivate) {
                browserStore.state.privateTabs.size > 1
            } else {
                browserStore.state.normalTabs.size > 1
            }

        val inactiveTabs = browserStore.state.actualInactiveTabs(settings = settings)

        tabsUseCases.removeTab(tabId = sessionId, excludedFallbackTabIds = inactiveTabs.map { it.id }.toSet())

        var tabId = ""
        if (settings.enableHomepageAsNewTab && !hasTabsRemaining) {
            // Add a new tab if the last tab is being removed to ensure there's always 1 tab.
            // Hold onto the new tab ID so that the new tab can be removed if the tabs are restored
            // by the undo action.
            tabId = fenixBrowserUseCases.addNewHomepageTab(private = isPrivate)
        }

        showUndoSnackbar(
            message = context.tabClosedUndoMessage(tab.content.private),
            onCancel = {
                onUndoTabsRemoved(
                    tabId = tabId,
                    isRestoringHomepageTab = tab.content.url == ABOUT_HOME_URL,
                )
            },
        )
    }

    /**
     * Callback invoked when the remove tabs action is cancelled.
     *
     * @param tabId Optional ID of the tab that should be removed after the tab removal is undone.
     * @param isRestoringHomepageTab Whether the restored selected tab is a homepage tab.
     */
    @VisibleForTesting
    internal fun onUndoTabsRemoved(
        tabId: String?,
        isRestoringHomepageTab: Boolean = false,
    ) {
        tabsUseCases.undo.invoke()

        if (tabId?.isNotBlank() == true) {
            tabsUseCases.removeTab.invoke(tabId)
        }

        // The homepage is already shown when the removed tabs are undone. Only navigate to the
        // browser when restoring a non-homepage tab.
        if (!isRestoringHomepageTab) {
            navController.navigate(HomeFragmentDirections.actionGlobalBrowser(null))
        }
    }
}

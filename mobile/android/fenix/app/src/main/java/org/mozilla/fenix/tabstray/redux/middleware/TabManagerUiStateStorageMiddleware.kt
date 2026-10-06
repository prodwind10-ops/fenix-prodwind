/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.redux.middleware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.tabstray.data.TabsTrayItem
import org.mozilla.fenix.tabstray.redux.action.TabGroupAction
import org.mozilla.fenix.tabstray.redux.action.TabManagerUiStateStorageAction
import org.mozilla.fenix.tabstray.redux.action.TabsTrayAction
import org.mozilla.fenix.tabstray.redux.action.TabsTrayAction.InitAction
import org.mozilla.fenix.tabstray.redux.state.Page
import org.mozilla.fenix.tabstray.redux.state.TabsTrayState
import org.mozilla.fenix.tabstray.repository.uistate.TabManagerUiStateRepository

/**
 * [Middleware] that reacts to [TabsTrayAction] and performs UI state storage side effects.
 *
 * @param uiStateRepository [TabManagerUiStateRepository] used for performing UI state storage side effects.
 * @param scope The [CoroutineScope] used for reading from/writing to [uiStateRepository].
 */
class TabManagerUiStateStorageMiddleware(
    private val uiStateRepository: TabManagerUiStateRepository,
    private val scope: CoroutineScope,
) : Middleware<TabsTrayState, TabsTrayAction> {

    private val logger = Logger("TabManagerUiStateStorageMiddleware")

    override fun invoke(
        store: Store<TabsTrayState, TabsTrayAction>,
        next: (TabsTrayAction) -> Unit,
        action: TabsTrayAction,
    ) {
        when (action) {
            is TabManagerUiStateStorageAction ->
                processAction(
                    action = action,
                    store = store,
                )

            else -> {}
        }

        next(action)
    }

    private fun processAction(
        action: TabManagerUiStateStorageAction,
        store: Store<TabsTrayState, TabsTrayAction>,
    ) {
        when (action) {
            InitAction -> {
                // Set up the data observer and set the Flow collection to the lifetime of main scope.
                scope.launch {
                    uiStateRepository.uiState.filterNotNull().distinctUntilChanged().collect { stateUpdate ->
                        store.dispatch(TabsTrayAction.PersistedUiStateUpdateReceived(update = stateUpdate))
                    }
                }
            }

            TabGroupAction.OnboardingDismissed ->
                scope.launch {
                    val success = uiStateRepository.dismissTabGroupOnboarding()
                    if (!success) {
                        logger.debug("Failed to update tab group onboarding was dismissed")
                    }
                }

            is TabsTrayAction.TabDataUpdateReceived -> {
                val userHasTabGroups = action.tabStorageUpdate.tabGroups.isNotEmpty()
                recordUserHadTabGroup(userHasTabGroups = userHasTabGroups)
                dismissTabGroupOnboardingForExistingGroups(userHasTabGroups = userHasTabGroups)
            }

            is TabsTrayAction.PageSelected -> {
                recordTabGroupsPageViewed(
                    page = action.page,
                    userHasTabGroups = store.state.tabGroupState.groups.isNotEmpty(),
                )
            }

            TabGroupAction.OnboardingShown -> {
                if (!store.state.tabGroupState.hasRecordedOnboardingImpression) {
                    recordOnboardingImpression()
                }
            }

            is TabGroupAction.UngroupRequested -> {
                if (store.state.tabGroupState.skipUngroupConfirmation) {
                    store.dispatch(TabGroupAction.UngroupConfirmed(group = action.group, dontAskAgain = true))
                } else {
                    store.dispatch(TabGroupAction.UngroupConfirmationRequested(group = action.group))
                }
            }

            is TabGroupAction.UngroupConfirmed -> setSkipUngroupConfirmation(action.dontAskAgain)

            is TabsTrayAction.ToggleTabLocked -> setTabsLocked(tabs = listOf(action.tab), locked = action.locked)

            is TabsTrayAction.ToggleSelectedTabsLocked -> setTabsLocked(tabs = action.tabs, locked = action.locked)

            is TabsTrayAction.RenameTab -> setTabCustomTitle(tabId = action.tab.id, newTitle = action.newTitle)

            is TabGroupAction.ToggleGroupLocked -> setGroupLocked(groupId = action.group.id, locked = action.locked)
        }
    }

    private fun setTabsLocked(tabs: Collection<TabsTrayItem.Tab>, locked: Boolean) {
        scope.launch {
            // Locked/unlocked one at a time via the same repository methods a single-tab toggle uses, rather than a
            // dedicated bulk repository method, since DataStore writes are cheap and this keeps a single source of
            // truth for what "locking a tab" means at the repository layer.
            val results =
                tabs.flatMap { tab ->
                    if (locked) {
                        listOfNotNull(
                            // The page a tab is on when it is locked becomes its root. This is written before the lock
                            // so the very first emission that shows the tab as locked already carries its root, and
                            // only for tabs that are newly locked: locking a selection that includes an already-locked
                            // tab must not replace that tab's root with wherever it has navigated to since.
                            if (!tab.locked && !tab.isHomepageItem) {
                                uiStateRepository.setTabRootUrl(tabId = tab.id, url = tab.url)
                            } else {
                                null
                            },
                            uiStateRepository.lockTab(tab.id),
                        )
                    } else {
                        listOf(
                            uiStateRepository.unlockTab(tab.id),
                            uiStateRepository.setTabRootUrl(tabId = tab.id, url = null),
                        )
                    }
                }
            if (results.any { !it }) {
                logger.debug("Failed to update locked status for one or more tabs")
            }
        }
    }

    private fun setTabCustomTitle(tabId: String, newTitle: String?) {
        scope.launch {
            // Saving with text always stores it as the tab's title, even if it matches the current page title, so a
            // user can freeze a title that the page keeps changing. Blank text removes the custom title.
            val success = uiStateRepository.setTabCustomTitle(tabId = tabId, title = newTitle?.trim())
            if (!success) {
                logger.debug("Failed to update the custom title for tab $tabId")
            }
        }
    }

    private fun setGroupLocked(groupId: String, locked: Boolean) {
        scope.launch {
            val success = if (locked) uiStateRepository.lockGroup(groupId) else uiStateRepository.unlockGroup(groupId)
            if (!success) {
                logger.debug("Failed to update locked status for group $groupId")
            }
        }
    }

    private fun setSkipUngroupConfirmation(dontAskAgain: Boolean) {
        if (dontAskAgain && uiStateRepository.uiState.value?.skipUngroupConfirmation != true) {
            scope.launch {
                val success = uiStateRepository.setSkipUngroupConfirmation()
                if (!success) {
                    logger.debug("Failed to update whether the ungroup confirmation was dismissed")
                }
            }
        }
    }

    private fun recordUserHadTabGroup(userHasTabGroups: Boolean) {
        // Check if we can avoid any unnecessary writes - skip if this is already true.
        if (userHasTabGroups && uiStateRepository.uiState.value?.hasUserEverHadOneTabGroup != true) {
            scope.launch {
                val success = uiStateRepository.recordUserHadTabGroup()
                if (!success) {
                    logger.debug("Failed to update whether the user had a tab group")
                }
            }
        }
    }

    private fun recordTabGroupsPageViewed(page: Page, userHasTabGroups: Boolean) {
        if (
            page == Page.TabGroups &&
                userHasTabGroups &&
                uiStateRepository.uiState.value?.hasViewedTabGroupsPage != true
        ) {
            scope.launch {
                val success = uiStateRepository.recordTabGroupsPageViewed()
                if (!success) {
                    logger.debug("Failed to update whether the tab groups page was viewed")
                }
            }
        }
    }

    private fun dismissTabGroupOnboardingForExistingGroups(userHasTabGroups: Boolean) {
        if (userHasTabGroups && uiStateRepository.uiState.value?.hasUserDismissedTabGroupOnboarding != true) {
            scope.launch {
                val success = uiStateRepository.dismissTabGroupOnboarding()
                if (!success) {
                    logger.debug("Failed to dismiss tab group onboarding for a user with a tab group")
                }
            }
        }
    }

    private fun recordOnboardingImpression() {
        scope.launch {
            val success = uiStateRepository.incrementTabGroupOnboardingImpressionCount()
            if (!success) {
                logger.debug("Failed to increment the tab group onboarding impression count")
            }
        }
    }
}

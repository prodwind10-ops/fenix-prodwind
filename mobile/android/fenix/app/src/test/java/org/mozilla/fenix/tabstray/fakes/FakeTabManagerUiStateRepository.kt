/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.fakes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.fenix.tabstray.repository.uistate.TabManagerUiStateRepository
import org.mozilla.fenix.tabstray.repository.uistate.data.PersistedUIState

class FakeTabManagerUiStateRepository(initialPersistedUIState: PersistedUIState? = null) : TabManagerUiStateRepository {

    private val uiStateFlow: MutableStateFlow<PersistedUIState?> = MutableStateFlow(initialPersistedUIState)

    override val uiState: StateFlow<PersistedUIState?> = uiStateFlow

    override suspend fun dismissTabGroupOnboarding(): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(hasUserDismissedTabGroupOnboarding = true))
        return true
    }

    override suspend fun incrementTabGroupOnboardingImpressionCount(): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        val impressionCount = currentSnapshot.tabGroupOnboardingImpressionCount
        uiStateFlow.emit(currentSnapshot.copy(tabGroupOnboardingImpressionCount = impressionCount + 1))
        return true
    }

    override suspend fun recordUserHadTabGroup(): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(hasUserEverHadOneTabGroup = true))
        return true
    }

    override suspend fun recordTabGroupsPageViewed(): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(hasViewedTabGroupsPage = true))
        return true
    }

    override suspend fun setSkipUngroupConfirmation(): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(skipUngroupConfirmation = true))
        return true
    }

    override suspend fun lockTab(tabId: String): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(lockedTabIds = currentSnapshot.lockedTabIds + tabId))
        return true
    }

    override suspend fun unlockTab(tabId: String): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(lockedTabIds = currentSnapshot.lockedTabIds - tabId))
        return true
    }

    override suspend fun lockGroup(groupId: String): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(lockedGroupIds = currentSnapshot.lockedGroupIds + groupId))
        return true
    }

    override suspend fun unlockGroup(groupId: String): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        uiStateFlow.emit(currentSnapshot.copy(lockedGroupIds = currentSnapshot.lockedGroupIds - groupId))
        return true
    }

    override suspend fun setTabCustomTitle(tabId: String, title: String?): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        val titles = if (title.isNullOrBlank()) {
            currentSnapshot.customTabTitles - tabId
        } else {
            currentSnapshot.customTabTitles + (tabId to title)
        }
        uiStateFlow.emit(currentSnapshot.copy(customTabTitles = titles))
        return true
    }

    override suspend fun setTabRootUrl(tabId: String, url: String?): Boolean {
        val currentSnapshot = uiStateFlow.value ?: PersistedUIState()
        val urls = if (url.isNullOrBlank()) {
            currentSnapshot.lockedTabRootUrls - tabId
        } else {
            currentSnapshot.lockedTabRootUrls + (tabId to url)
        }
        uiStateFlow.emit(currentSnapshot.copy(lockedTabRootUrls = urls))
        return true
    }
}

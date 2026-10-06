/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.repository.uistate

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.datastore.editOrCatch
import org.mozilla.fenix.tabstray.repository.uistate.data.PersistedUIState

/** Repository for performing side effects related to persisted UI state of the Tab Manager feature. */
interface TabManagerUiStateRepository {

    /** [StateFlow] for observing the [PersistedUIState] saved on disk. */
    val uiState: StateFlow<PersistedUIState?>

    /** Marks the tab group onboarding as dismissed by the user. */
    suspend fun dismissTabGroupOnboarding(): Boolean

    /** Increments the number of times a user has seen the tab group onboarding. */
    suspend fun incrementTabGroupOnboardingImpressionCount(): Boolean

    /** Records the user as having at least one tab group during their use of the application. */
    suspend fun recordUserHadTabGroup(): Boolean

    /** Records the user as having viewed the Tab Groups page. */
    suspend fun recordTabGroupsPageViewed(): Boolean

    /** Records that the ungroup confirmation dialog should be skipped from now on. */
    suspend fun setSkipUngroupConfirmation(): Boolean

    /** Marks the tab with [tabId] as locked, excluding it from "close all tabs" and individual closure. */
    suspend fun lockTab(tabId: String): Boolean

    /** Marks the tab with [tabId] as unlocked. */
    suspend fun unlockTab(tabId: String): Boolean

    /** Marks the tab group with [groupId] as locked, excluding it from "close all tabs" and closure/deletion. */
    suspend fun lockGroup(groupId: String): Boolean

    /** Marks the tab group with [groupId] as unlocked. */
    suspend fun unlockGroup(groupId: String): Boolean

    /**
     * Gives the tab with [tabId] a custom title that is kept regardless of its page title. A null or blank [title]
     * removes the custom title so the tab shows its page title again.
     */
    suspend fun setTabCustomTitle(tabId: String, title: String?): Boolean

    /**
     * Records [url] as the root URL of the locked tab with [tabId]. A null or blank [url] removes the root URL, which
     * is what happens when the tab is unlocked.
     */
    suspend fun setTabRootUrl(tabId: String, url: String?): Boolean
}

/**
 * The default implementation of [TabManagerUiStateRepository].
 *
 * @param dataStore The [DataStore] used to read/write [PersistedUIState].
 * @param stateFlowScope The [CoroutineScope] used to construct the [StateFlow] for [uiState].
 */
class DefaultTabManagerUiStateRepository
internal constructor(
    private val dataStore: DataStore<Preferences>,
    stateFlowScope: CoroutineScope,
) : TabManagerUiStateRepository {

    private val logger = Logger(tag = "DefaultTabManagerUiStateRepository")

    constructor(
        context: Context,
        stateFlowScope: CoroutineScope,
    ) : this(
        dataStore = context.uiStateDataStore,
        stateFlowScope = stateFlowScope,
    )

    override val uiState: StateFlow<PersistedUIState?> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    logger.error("Error reading preferences.", exception)
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                PersistedUIState(
                    hasUserDismissedTabGroupOnboarding = preferences[hasUserDismissedTabGroupOnboardingKey] ?: false,
                    tabGroupOnboardingImpressionCount = preferences[tabGroupOnboardingImpressionCountKey] ?: 0,
                    hasUserEverHadOneTabGroup = preferences[hasUserEverHadOneTabGroupKey] ?: false,
                    hasViewedTabGroupsPage = preferences[hasViewedTabGroupsPageKey] ?: false,
                    skipUngroupConfirmation = preferences[skipUngroupConfirmationKey] ?: false,
                    lockedTabIds = preferences[lockedTabIdsKey] ?: emptySet(),
                    lockedGroupIds = preferences[lockedGroupIdsKey] ?: emptySet(),
                    customTabTitles = preferences[customTabTitlesKey].toStringMap(),
                    lockedTabRootUrls = preferences[lockedTabRootUrlsKey].toStringMap(),
                )
            }
            .stateIn(
                scope = stateFlowScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    override suspend fun dismissTabGroupOnboarding(): Boolean = updateDataStore { preferences ->
        preferences[hasUserDismissedTabGroupOnboardingKey] = true
    }

    override suspend fun incrementTabGroupOnboardingImpressionCount(): Boolean = updateDataStore { preferences ->
        val currentImpressionCount = preferences[tabGroupOnboardingImpressionCountKey] ?: 0
        preferences[tabGroupOnboardingImpressionCountKey] = currentImpressionCount + 1
    }

    override suspend fun recordUserHadTabGroup(): Boolean = updateDataStore { preferences ->
        preferences[hasUserEverHadOneTabGroupKey] = true
    }

    override suspend fun recordTabGroupsPageViewed(): Boolean = updateDataStore { preferences ->
        preferences[hasViewedTabGroupsPageKey] = true
    }

    override suspend fun setSkipUngroupConfirmation(): Boolean = updateDataStore { preferences ->
        preferences[skipUngroupConfirmationKey] = true
    }

    override suspend fun lockTab(tabId: String): Boolean = updateDataStore { preferences ->
        val currentLockedTabIds = preferences[lockedTabIdsKey] ?: emptySet()
        preferences[lockedTabIdsKey] = currentLockedTabIds + tabId
    }

    override suspend fun unlockTab(tabId: String): Boolean = updateDataStore { preferences ->
        val currentLockedTabIds = preferences[lockedTabIdsKey] ?: emptySet()
        preferences[lockedTabIdsKey] = currentLockedTabIds - tabId
    }

    override suspend fun lockGroup(groupId: String): Boolean = updateDataStore { preferences ->
        val currentLockedGroupIds = preferences[lockedGroupIdsKey] ?: emptySet()
        preferences[lockedGroupIdsKey] = currentLockedGroupIds + groupId
    }

    override suspend fun unlockGroup(groupId: String): Boolean = updateDataStore { preferences ->
        val currentLockedGroupIds = preferences[lockedGroupIdsKey] ?: emptySet()
        preferences[lockedGroupIdsKey] = currentLockedGroupIds - groupId
    }

    override suspend fun setTabCustomTitle(tabId: String, title: String?): Boolean = updateDataStore { preferences ->
        val updated = preferences[customTabTitlesKey].toStringMap().withEntry(tabId, title)
        preferences[customTabTitlesKey] = updated.toJsonString()
    }

    override suspend fun setTabRootUrl(tabId: String, url: String?): Boolean = updateDataStore { preferences ->
        val updated = preferences[lockedTabRootUrlsKey].toStringMap().withEntry(tabId, url)
        preferences[lockedTabRootUrlsKey] = updated.toJsonString()
    }

    /** Returns a copy of this map with [key] set to [value], or removed when [value] is null or blank. */
    private fun Map<String, String>.withEntry(key: String, value: String?): Map<String, String> {
        val cleaned = value?.takeIf { it.isNotBlank() }
        return if (cleaned == null) this - key else this + (key to cleaned)
    }

    @VisibleForTesting
    internal suspend fun initializeDataStore(initialUiState: PersistedUIState) {
        dataStore.editOrCatch(onError = {}) { preferences ->
            preferences[hasUserDismissedTabGroupOnboardingKey] = initialUiState.hasUserDismissedTabGroupOnboarding
            preferences[tabGroupOnboardingImpressionCountKey] = initialUiState.tabGroupOnboardingImpressionCount
            preferences[hasUserEverHadOneTabGroupKey] = initialUiState.hasUserEverHadOneTabGroup
            preferences[hasViewedTabGroupsPageKey] = initialUiState.hasViewedTabGroupsPage
            preferences[skipUngroupConfirmationKey] = initialUiState.skipUngroupConfirmation
            preferences[lockedTabIdsKey] = initialUiState.lockedTabIds
            preferences[lockedGroupIdsKey] = initialUiState.lockedGroupIds
            preferences[customTabTitlesKey] = initialUiState.customTabTitles.toJsonString()
            preferences[lockedTabRootUrlsKey] = initialUiState.lockedTabRootUrls.toJsonString()
        }
    }

    private suspend fun updateDataStore(transform: suspend (MutablePreferences) -> Unit): Boolean {
        var preferenceWriteSucceeded = true
        dataStore.editOrCatch(
            onError = {
                preferenceWriteSucceeded = false
            },
            transform = transform,
        )

        return preferenceWriteSucceeded
    }
}

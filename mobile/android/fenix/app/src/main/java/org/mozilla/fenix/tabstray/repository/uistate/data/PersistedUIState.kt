/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.repository.uistate.data

/**
 * Model for data emitted from the Tab Manager UI data storage layer.
 *
 * @property hasUserDismissedTabGroupOnboarding Whether the user has previously dismissed the onboarding.
 * @property tabGroupOnboardingImpressionCount How many times the user has been presented the onboarding.
 * @property hasUserEverHadOneTabGroup Whether the user has ever had a tab group.
 * @property hasViewedTabGroupsPage Whether the user has viewed the Tab Groups page.
 * @property skipUngroupConfirmation Whether the ungroup confirmation dialog should be skipped.
 * @property lockedTabIds The IDs of tabs the user has locked. A locked tab is excluded from "close all tabs" and
 *   cannot be closed individually until unlocked.
 * @property lockedGroupIds The IDs of tab groups the user has locked. A locked group is excluded from
 *   "close all tabs" and cannot be closed or deleted until unlocked.
 * @property customTabTitles Titles the user has given to tabs, keyed by tab ID. A tab with an entry here keeps that
 *   title in the Tab Manager no matter how its page title changes.
 * @property lockedTabRootUrls The "root" URL of each locked tab, keyed by tab ID: the page the tab was showing when it
 *   was locked, which the user can return the tab to after navigating away.
 */
data class PersistedUIState(
    val hasUserDismissedTabGroupOnboarding: Boolean = false,
    val tabGroupOnboardingImpressionCount: Int = 0,
    val hasUserEverHadOneTabGroup: Boolean = false,
    val hasViewedTabGroupsPage: Boolean = false,
    val skipUngroupConfirmation: Boolean = false,
    val lockedTabIds: Set<String> = emptySet(),
    val lockedGroupIds: Set<String> = emptySet(),
    val customTabTitles: Map<String, String> = emptyMap(),
    val lockedTabRootUrls: Map<String, String> = emptyMap(),
)

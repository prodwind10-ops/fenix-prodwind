/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.ext

import mozilla.components.browser.state.state.BrowserState
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.fenix.utils.Settings

/**
 * Removes all the active normal tabs, leaving any inactive tabs open. Inactive tabs are also excluded when selecting a
 * fallback tab.
 *
 * @param state The [BrowserState] holding the currently open tabs.
 * @param settings [Settings] used to check whether the inactive tabs feature is enabled.
 * @return The number of tabs that were removed.
 */
fun TabsUseCases.removeAllActiveNormalTabs(state: BrowserState, settings: Settings): Int {
    val (activeTabs, inactiveTabs) = state.partitionNormalTabsByActiveTime(settings = settings)
    val tabIdsToRemove = activeTabs.map { it.id }

    removeTabs(ids = tabIdsToRemove, excludedFallbackTabIds = inactiveTabs.mapTo(mutableSetOf()) { it.id })

    return tabIdsToRemove.size
}

/**
 * Removes all normal tabs except those in [lockedTabIds], leaving locked tabs open. Locked tabs are also excluded
 * when selecting a fallback tab, since they are not being closed and should remain a valid destination.
 *
 * @param state The [BrowserState] holding the currently open tabs.
 * @param lockedTabIds The IDs of all normal tabs that are locked, either directly or via a locked tab group.
 * @return The number of tabs that were removed.
 */
fun TabsUseCases.removeAllUnlockedNormalTabs(state: BrowserState, lockedTabIds: Set<String>): Int {
    val (lockedTabs, unlockedTabs) = state.normalTabs.partition { it.id in lockedTabIds }
    val tabIdsToRemove = unlockedTabs.map { it.id }

    removeTabs(ids = tabIdsToRemove, excludedFallbackTabIds = lockedTabs.mapTo(mutableSetOf()) { it.id })

    return tabIdsToRemove.size
}

/**
 * Removes all active normal tabs except those in [lockedTabIds], leaving any inactive or locked tabs open. Inactive
 * and locked tabs are also excluded when selecting a fallback tab, since neither is being closed.
 *
 * @param state The [BrowserState] holding the currently open tabs.
 * @param settings [Settings] used to check whether the inactive tabs feature is enabled.
 * @param lockedTabIds The IDs of all normal tabs that are locked, either directly or via a locked tab group.
 * @return The number of tabs that were removed.
 */
fun TabsUseCases.removeAllActiveUnlockedNormalTabs(
    state: BrowserState,
    settings: Settings,
    lockedTabIds: Set<String>,
): Int {
    val (activeTabs, inactiveTabs) = state.partitionNormalTabsByActiveTime(settings = settings)
    val (lockedActiveTabs, unlockedActiveTabs) = activeTabs.partition { it.id in lockedTabIds }
    val tabIdsToRemove = unlockedActiveTabs.map { it.id }

    val excludedFallbackTabIds =
        (inactiveTabs.map { it.id } + lockedActiveTabs.map { it.id }).toMutableSet()

    removeTabs(ids = tabIdsToRemove, excludedFallbackTabIds = excludedFallbackTabIds)

    return tabIdsToRemove.size
}

/**
 * Removes all private tabs except those in [lockedTabIds], leaving locked tabs open. Locked tabs are also excluded
 * when selecting a fallback tab, since they are not being closed and should remain a valid destination.
 *
 * @param state The [BrowserState] holding the currently open tabs.
 * @param lockedTabIds The IDs of all private tabs that are locked.
 * @return The number of tabs that were removed.
 */
fun TabsUseCases.removeAllUnlockedPrivateTabs(state: BrowserState, lockedTabIds: Set<String>): Int {
    val (lockedTabs, unlockedTabs) = state.privateTabs.partition { it.id in lockedTabIds }
    val tabIdsToRemove = unlockedTabs.map { it.id }

    removeTabs(ids = tabIdsToRemove, excludedFallbackTabIds = lockedTabs.mapTo(mutableSetOf()) { it.id })

    return tabIdsToRemove.size
}

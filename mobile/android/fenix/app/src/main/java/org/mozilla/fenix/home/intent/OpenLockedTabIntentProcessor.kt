/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.intent

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.shortcut.LockedTabShortcut
import org.mozilla.fenix.utils.Settings

/**
 * Handles a home screen shortcut created by [LockedTabShortcut]: selects the tab whose ID the intent carries and shows
 * it in the browser, without creating a new tab. See [LockedTabShortcut.ACTION_OPEN_LOCKED_TAB] and
 * [LockedTabShortcut.EXTRA_TAB_ID].
 *
 * When the app is already running the tab is selected immediately. On a cold start the tabs are restored
 * asynchronously, so the tab does not exist yet when the intent arrives; the processor then waits for the restore to
 * finish before selecting it.
 */
class OpenLockedTabIntentProcessor(private val activity: HomeActivity) : HomeIntentProcessor {

    override fun process(intent: Intent, navController: NavController, out: Intent, settings: Settings): Boolean {
        if (intent.action != LockedTabShortcut.ACTION_OPEN_LOCKED_TAB) {
            return false
        }
        val tabId = intent.getStringExtra(LockedTabShortcut.EXTRA_TAB_ID) ?: return false
        val store = activity.components.core.store

        if (store.state.restoreComplete) {
            // If the tab no longer exists (it was closed), fall back to the app's normal behavior.
            return openTab(store, tabId)
        }

        activity.lifecycleScope.launch {
            store.flow().first { it.restoreComplete }
            if (!openTab(store, tabId)) {
                activity.navigateToBrowserOnColdStart()
            }
        }
        return true
    }

    private fun openTab(store: BrowserStore, tabId: String): Boolean {
        if (store.state.findTab(tabId) == null) {
            return false
        }

        activity.components.useCases.tabsUseCases.selectTab(tabId)
        activity.openToBrowser(BrowserDirection.FromGlobal)
        return true
    }
}

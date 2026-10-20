/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import android.content.Context
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.sections.BookmarksSection
import org.mozilla.fenix.backup.sections.CookiesSection
import org.mozilla.fenix.backup.sections.FeatureTogglesSection
import org.mozilla.fenix.backup.sections.HistorySection
import org.mozilla.fenix.backup.sections.LoginExceptionsSection
import org.mozilla.fenix.backup.sections.LoginsSection
import org.mozilla.fenix.backup.sections.PinnedSitesSection
import org.mozilla.fenix.backup.sections.PreferencesDataStoreSection
import org.mozilla.fenix.backup.sections.SearchEnginesSection
import org.mozilla.fenix.backup.sections.SettingsSection
import org.mozilla.fenix.backup.sections.TabGroupsSection
import org.mozilla.fenix.backup.sections.TabsSection
import org.mozilla.fenix.datastore.preferencesDataStore
import org.mozilla.fenix.tabstray.repository.uistate.customTabTitlesKey
import org.mozilla.fenix.tabstray.repository.uistate.lockedTabRootUrlsKey
import org.mozilla.fenix.tabstray.repository.uistate.uiStateDataStore

/**
 * **The list of everything that is saved in a backup.** When the app gets a new place where it keeps data the person
 * would miss, add a section here (see the rules in `CLAUDE.md` in the root of the repository).
 *
 * The order matters, because it is the order sections are saved and restored in: tab groups and the state of the tab
 * manager (which tabs are locked) refer to tabs, so they come after the tabs.
 *
 * ## What is not saved on purpose
 *
 * - The Firefox account and what it syncs: signing in again gets it back.
 * - Add-ons and what they stored, site permissions, downloads and the files in them, credit cards, addresses, the
 *   recently closed tabs, private tabs, the wallpapers, and the history of the settings search.
 * - Data of the website that is stored by the websites themselves (local storage, IndexedDB, caches), because the
 *   browser engine gives no way to get at it. Cookies are the exception and have their own section.
 * - Things that belong to the device or to the install, not to the person: see `DeviceSpecificPreferences`.
 * - The stores of the debug menu and the voices chosen for reading pages aloud (they belong to this device).
 */
internal object BackupSections {
    /** The sections in the order they are saved and restored. */
    fun all(context: Context): List<BackupSection> {
        // A section keeps the context it is given for as long as it exists.
        val app = context.applicationContext
        return listOf(
            SettingsSection(app),
            PreferencesDataStoreSection(
                id = "app_datastore",
                titleRes = R.string.backup_section_app_datastore,
                dataStore = { app.preferencesDataStore },
                style = PreferencesDataStoreSection.Style.SETTINGS,
            ),
            FeatureTogglesSection(app),
            TabsSection(app),
            TabGroupsSection(app),
            PreferencesDataStoreSection(
                id = "tab_manager",
                titleRes = R.string.backup_section_tab_manager,
                dataStore = { app.uiStateDataStore },
                style = PreferencesDataStoreSection.Style.STATE,
                jsonMapKeys = setOf(customTabTitlesKey.name, lockedTabRootUrlsKey.name),
            ),
            BookmarksSection(app),
            HistorySection(app),
            LoginsSection(app),
            LoginExceptionsSection(app),
            PinnedSitesSection(app),
            SearchEnginesSection(app),
            CookiesSection(app),
        )
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.components.DefaultShortcutManagerCompatWrapper
import org.mozilla.fenix.components.ShortcutManagerCompatWrapper
import org.mozilla.fenix.tabstray.data.TabsTrayItem

/**
 * Home screen shortcuts that open an existing, locked tab.
 *
 * Unlike the regular "Add to Home screen" shortcut, which opens its URL in a new tab, these shortcuts carry the ID of
 * the tab itself. Opening one makes [HomeActivity] select that tab: when the app is already running it switches to
 * the tab, and on a cold start it opens with that tab showing. The shortcut is handled by
 * [org.mozilla.fenix.home.intent.OpenLockedTabIntentProcessor].
 */
object LockedTabShortcut {

    /** Intent action of a shortcut created by [requestPin]. */
    const val ACTION_OPEN_LOCKED_TAB = "org.mozilla.fenix.OPEN_LOCKED_TAB"

    /** Intent extra holding the ID of the tab to open. */
    const val EXTRA_TAB_ID = "org.mozilla.fenix.OPEN_LOCKED_TAB.tab_id"

    private const val SHORTCUT_ID_PREFIX = "locked_tab_"
    private const val ICON_SIZE_PX = 192
    private const val ICON_CONTENT_FRACTION = 0.5f
    private const val LETTER_TEXT_SIZE_FRACTION = 0.3f
    private val LETTER_BACKGROUND_COLOR = 0xFF5B5B66.toInt()

    /** Creates the [Intent] a shortcut for the tab with [tabId] launches. */
    fun createIntent(context: Context, tabId: String): Intent =
        Intent(context, HomeActivity::class.java).apply {
            action = ACTION_OPEN_LOCKED_TAB
            putExtra(EXTRA_TAB_ID, tabId)
            // The tab being opened is a normal tab, so make sure the app is not left in private browsing mode.
            putExtra(HomeActivity.PRIVATE_BROWSING_MODE, false)
            // No CLEAR_TASK: an already-running app must be switched to the tab, not restarted.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /**
     * Asks the launcher to pin a shortcut that opens [tab]. The launcher shows its own confirmation. The shortcut ID
     * is derived from the tab ID, so requesting it again for the same tab updates that shortcut instead of adding a
     * duplicate.
     *
     * @param context The current [Context].
     * @param tab The tab to create a shortcut for. Private tabs are not supported, since they do not survive the app
     *   being closed.
     * @param shortcutManager Used to talk to the system's shortcut manager.
     * @return Whether the request was sent to the launcher.
     */
    fun requestPin(
        context: Context,
        tab: TabsTrayItem.Tab,
        shortcutManager: ShortcutManagerCompatWrapper = DefaultShortcutManagerCompatWrapper(),
    ): Boolean {
        if (tab.private || !shortcutManager.isRequestPinShortcutSupported(context)) {
            return false
        }

        val label = tab.title.ifBlank { tab.url }
        val shortcut =
            ShortcutInfoCompat.Builder(context, SHORTCUT_ID_PREFIX + tab.id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithAdaptiveBitmap(buildIcon(tab.icon, label)))
                .setIntent(createIntent(context, tab.id))
                .build()

        return shortcutManager.requestPinShortcut(context, shortcut, null)
    }

    /**
     * Draws the shortcut icon: the tab's favicon centered on a white background, or the first letter of the label on
     * a colored disc when the tab has no favicon. Adaptive icons crop the outer part of the bitmap, so everything is
     * kept within the central area.
     */
    private fun buildIcon(favicon: Bitmap?, label: String): Bitmap {
        val size = ICON_SIZE_PX
        val sizeF = size.toFloat()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, sizeF, sizeF, paint)

        val contentSize = sizeF * ICON_CONTENT_FRACTION
        val offset = (sizeF - contentSize) / 2f
        if (favicon != null && favicon.width > 0 && favicon.height > 0) {
            paint.isFilterBitmap = true
            canvas.drawBitmap(favicon, null, RectF(offset, offset, offset + contentSize, offset + contentSize), paint)
        } else {
            paint.color = LETTER_BACKGROUND_COLOR
            canvas.drawCircle(sizeF / 2f, sizeF / 2f, contentSize / 2f, paint)

            val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = sizeF * LETTER_TEXT_SIZE_FRACTION
            val baseline = sizeF / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, sizeF / 2f, baseline, paint)
        }

        return bitmap
    }
}

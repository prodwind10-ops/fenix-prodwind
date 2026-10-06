/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.ui.banner

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import mozilla.components.compose.base.button.TextButton
import org.mozilla.fenix.R
import org.mozilla.fenix.tabstray.TabsTrayTestTag
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Dialog for renaming a tab. Saving blank text removes the custom title, so the tab shows its page title again.
 *
 * @param initialTitle The text the field starts with, normally the tab's current title.
 * @param onConfirm Invoked with the entered text when the user taps Save.
 * @param onDismiss Invoked when the user cancels or dismisses the dialog.
 */
@Composable
internal fun RenameTabDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var titleState by remember {
        mutableStateOf(TextFieldValue(text = initialTitle, selection = TextRange(0, initialTitle.length)))
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.tab_rename_dialog_title),
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            OutlinedTextField(
                value = titleState,
                onValueChange = { titleState = it },
                label = { Text(stringResource(R.string.tab_rename_dialog_label)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.tab_rename_dialog_hint),
                        style = FirefoxTheme.typography.caption,
                    )
                },
                singleLine = true,
                modifier =
                    Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(TabsTrayTestTag.RENAME_TAB_DIALOG_TEXT_FIELD),
            )
        },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.tab_rename_dialog_confirm_button),
                onClick = { onConfirm(titleState.text) },
                modifier = Modifier.testTag(TabsTrayTestTag.RENAME_TAB_DIALOG_CONFIRM_BUTTON),
            )
        },
        dismissButton = {
            TextButton(
                text = stringResource(R.string.tab_rename_dialog_cancel_button),
                onClick = onDismiss,
            )
        },
    )
}

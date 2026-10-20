/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R

/**
 * The screen, opened from Settings, where the person saves all the data of the app to one file and restores it from
 * such a file. The work is done by [BackupManager]; this class only asks the questions and shows the results.
 *
 * Making a backup: choose what goes into it and a passphrase, choose where the file goes (the system's file picker, so
 * the file can be put anywhere, such as the Downloads folder or a cloud drive), wait. Restoring: choose the file, enter
 * the passphrase if it has one, choose what to restore, wait, restart the app.
 *
 * The views are made in code and the system dialogs are used, so that the screen does not depend on the layouts or
 * the design components of the rest of the app.
 */
class BackupActivity : AppCompatActivity() {
    private lateinit var manager: BackupManager
    private lateinit var actions: View
    private lateinit var progress: View
    private lateinit var progressText: TextView

    private var working = false
    private var openBackup: BackupManager.OpenBackup? = null
    private var pendingExport: PendingExport? = null

    /** Keeps the back button from leaving the screen while a backup is made or restored. */
    private val blockBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    private val chooseDestination = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_TYPE),
    ) { uri -> onDestinationChosen(uri) }

    private val chooseSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onSourceChosen(uri)
    }

    /** What the person decided in the first step of making a backup, while they choose where the file goes. */
    private class PendingExport(val selected: Set<String>, val passphrase: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = BackupManager(this)
        onBackPressedDispatcher.addCallback(this, blockBack)
        setContentView(buildScreen())
        lifecycleScope.launch { manager.cleanUp() }
    }

    override fun onDestroy() {
        closeBackup()
        super.onDestroy()
    }

    // region Screen

    private fun buildScreen(): View {
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.backup_title)
            setNavigationIcon(iconsR.drawable.mozac_ic_back_24)
            setNavigationContentDescription(R.string.backup_back)
            setNavigationOnClickListener { if (!working) finish() }
        }

        actions = column {
            addView(paragraph(getString(R.string.backup_intro)))
            addView(
                button(R.string.backup_create_button, outlined = false) { showCreateOptions() },
                spaced(top = BIG_GAP),
            )
            addView(
                button(R.string.backup_restore_button, outlined = true) { chooseSource.launch(arrayOf(ANY_TYPE)) },
                spaced(top = SMALL_GAP),
            )
            addView(note(getString(R.string.backup_restore_hint)), spaced(top = BIG_GAP))
            addView(note(getString(R.string.backup_not_included)), spaced(top = SMALL_GAP))
        }

        progressText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = BODY_SIZE
        }
        progress = column {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ProgressBar(this@BackupActivity).apply { isIndeterminate = true })
            addView(progressText, spaced(top = SMALL_GAP))
            visibility = View.GONE
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions)
            addView(progress, spaced(top = BIG_GAP))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(ScrollView(this@BackupActivity).apply { addView(body) })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    /** Shows the progress and hides the buttons while the work runs, and the other way round afterwards. */
    private suspend fun <T> whileWorking(block: suspend () -> T): T {
        working = true
        blockBack.isEnabled = true
        actions.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progressText.text = getString(R.string.backup_progress_working)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            return block()
        } finally {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progress.visibility = View.GONE
            actions.visibility = View.VISIBLE
            blockBack.isEnabled = false
            working = false
        }
    }

    /** Runs [block] and shows what went wrong when it throws. */
    private fun guarded(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                showError(e.message ?: getString(R.string.backup_error_unexpected, e.javaClass.simpleName))
            } catch (e: Exception) {
                val detail = e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
                showError(getString(R.string.backup_error_unexpected, detail))
            }
        }
    }

    private fun showProgress(text: String) {
        // Called from the thread that does the work.
        progressText.post { progressText.text = text }
    }

    // endregion

    // region Making a backup

    private fun showCreateOptions() {
        val boxes = manager.sections.map { section ->
            section to CheckBox(this).apply {
                text = labelOf(section)
                isChecked = true
            }
        }
        val passphrase = passphraseField(R.string.backup_passphrase_hint)
        val repeat = passphraseField(R.string.backup_passphrase_repeat_hint)
        val error = errorText()
        val view = scrollable {
            addView(paragraph(getString(R.string.backup_create_choose)))
            boxes.forEach { addView(it.second) }
            addView(note(getString(R.string.backup_passphrase_explanation)), spaced(top = BIG_GAP))
            addView(passphrase)
            addView(repeat)
            addView(error)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_create_title)
            .setView(view)
            .setPositiveButton(R.string.backup_create_continue, null)
            .setNegativeButton(R.string.backup_cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here, so that the dialog stays open when something is wrong with the answers.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val selected = boxes.filter { it.second.isChecked }.map { it.first }
                val secret = passphrase.text.toString()
                val problem = when {
                    selected.isEmpty() -> R.string.backup_error_nothing_selected
                    secret != repeat.text.toString() -> R.string.backup_error_passphrase_mismatch
                    secret.isNotEmpty() && secret.length < MIN_PASSPHRASE_LENGTH ->
                        R.string.backup_error_passphrase_short
                    else -> null
                }
                if (problem != null) {
                    error.setText(problem)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                dialog.dismiss()
                val ids = selected.map { it.id }.toSet()
                if (secret.isEmpty() && selected.any { it.sensitive }) {
                    confirmUnprotected { chooseWhereToSave(ids, null) }
                } else {
                    chooseWhereToSave(ids, secret.ifEmpty { null })
                }
            }
        }
        dialog.show()
    }

    private fun confirmUnprotected(onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_unprotected_title)
            .setMessage(R.string.backup_unprotected_message)
            .setPositiveButton(R.string.backup_unprotected_confirm) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.backup_cancel, null)
            .show()
    }

    private fun chooseWhereToSave(selected: Set<String>, passphrase: String?) {
        pendingExport = PendingExport(selected, passphrase)
        chooseDestination.launch(defaultFileName())
    }

    private fun onDestinationChosen(uri: Uri?) {
        val export = pendingExport
        pendingExport = null
        if (uri == null) return
        if (export == null) {
            // The screen was recreated while the file picker was open.
            showError(getString(R.string.backup_error_lost_choices))
            return
        }
        guarded {
            val reports = whileWorking {
                manager.create(uri, export.passphrase, export.selected) { section ->
                    showProgress(getString(R.string.backup_progress_saving, titleOf(section)))
                }
            }
            showResult(reports, restoring = false)
        }
    }

    private fun defaultFileName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "$FILE_NAME_PREFIX$day$FILE_EXTENSION"
    }

    // endregion

    // region Restoring a backup

    private fun onSourceChosen(uri: Uri?) {
        if (uri == null) return
        guarded {
            val needsPassphrase = whileWorking { manager.isProtected(uri) }
            if (needsPassphrase) {
                askPassphrase(uri, wrong = false)
            } else {
                openAndChoose(uri, null)
            }
        }
    }

    private fun askPassphrase(uri: Uri, wrong: Boolean) {
        val passphrase = passphraseField(R.string.backup_passphrase_hint)
        val view = scrollable {
            val prompt = if (wrong) R.string.backup_passphrase_wrong else R.string.backup_passphrase_prompt
            addView(paragraph(getString(prompt)))
            addView(passphrase)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_passphrase_title)
            .setView(view)
            .setPositiveButton(R.string.backup_passphrase_open) { _, _ ->
                val secret = passphrase.text.toString()
                guarded { openAndChoose(uri, secret) }
            }
            .setNegativeButton(R.string.backup_cancel, null)
            .show()
    }

    private suspend fun openAndChoose(uri: Uri, passphrase: String?) {
        closeBackup()
        val backup = try {
            whileWorking { manager.open(uri, passphrase) }
        } catch (e: BackupException) {
            when (e.reason) {
                BackupException.Reason.PASSPHRASE_REQUIRED -> askPassphrase(uri, wrong = false)
                BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED -> askPassphrase(uri, wrong = true)
                else -> throw e
            }
            return
        }
        openBackup = backup
        showRestoreChoices(backup)
    }

    private fun showRestoreChoices(backup: BackupManager.OpenBackup) {
        val manifest = backup.manifest
        val rows = ArrayList<Pair<BackupSection, CheckBox>>()
        val error = errorText()
        val view = scrollable {
            addView(note(describe(manifest)))
            addView(paragraph(getString(R.string.backup_restore_choose)), spaced(top = BIG_GAP))
            // The sections this version of the app knows come in the order they are restored in.
            for (section in manager.sections) {
                val info = manifest.sections.firstOrNull { it.id == section.id } ?: continue
                when {
                    info.failure != null ->
                        addView(note(getString(R.string.backup_restore_unavailable_not_saved, titleOf(section))))
                    info.version > section.version ->
                        addView(note(getString(R.string.backup_restore_unavailable_newer, titleOf(section))))
                    else -> {
                        val box = CheckBox(this@BackupActivity).apply {
                            text = getString(R.string.backup_section_with_count, labelOf(section), info.count)
                            isChecked = true
                        }
                        rows.add(section to box)
                        addView(box)
                    }
                }
            }
            for (info in manifest.sections) {
                if (manager.sections.none { it.id == info.id }) {
                    addView(note(getString(R.string.backup_restore_unavailable_unknown, info.id)))
                }
            }
            addView(note(getString(R.string.backup_restore_hint)), spaced(top = BIG_GAP))
            addView(error)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore_title)
            .setView(view)
            .setPositiveButton(R.string.backup_restore_confirm, null)
            .setNegativeButton(R.string.backup_cancel) { _, _ -> closeBackup() }
            .setOnCancelListener { closeBackup() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val selected = rows.filter { it.second.isChecked }.map { it.first.id }.toSet()
                if (selected.isEmpty()) {
                    error.setText(R.string.backup_error_nothing_selected)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                dialog.dismiss()
                restore(backup, selected)
            }
        }
        dialog.show()
    }

    private fun restore(backup: BackupManager.OpenBackup, selected: Set<String>) {
        guarded {
            try {
                val reports = whileWorking {
                    manager.restore(backup, selected) { section ->
                        showProgress(getString(R.string.backup_progress_restoring, titleOf(section)))
                    }
                }
                showResult(reports, restoring = true)
            } finally {
                closeBackup()
            }
        }
    }

    private fun closeBackup() {
        openBackup?.close()
        openBackup = null
    }

    private fun describe(manifest: BackupManifest): String {
        val unknown = getString(R.string.backup_unknown)
        val date = if (manifest.createdAt > 0) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(manifest.createdAt))
        } else {
            unknown
        }
        return getString(R.string.backup_restore_info, date, manifest.appVersionName ?: unknown)
    }

    // endregion

    // region Results

    private fun showResult(reports: List<SectionReport>, restoring: Boolean) {
        val problems = reports.any { it.outcome == SectionOutcome.FAILED || it.outcome == SectionOutcome.NOT_SAVED }
        val title = when {
            problems -> R.string.backup_result_title_problems
            restoring -> R.string.backup_result_title_restored
            else -> R.string.backup_result_title_saved
        }
        val message = buildString {
            reports.forEach { append(describe(it, restoring)).append("\n") }
            append("\n")
            append(getString(if (restoring) R.string.backup_result_restart_hint else R.string.backup_result_keep_hint))
        }
        val builder = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
        if (restoring) {
            builder
                .setPositiveButton(R.string.backup_restart_now) { _, _ -> restartApp() }
                .setNegativeButton(R.string.backup_restart_later, null)
        } else {
            builder.setPositiveButton(R.string.backup_close, null)
        }
        builder.show()
    }

    private fun describe(report: SectionReport, restoring: Boolean): String {
        val title = if (report.titleRes != 0) getString(report.titleRes) else report.id
        val line = when (report.outcome) {
            SectionOutcome.DONE -> if (restoring) {
                getString(R.string.backup_result_restored, title, report.processed, report.skipped)
            } else {
                getString(R.string.backup_result_saved, title, report.processed)
            }
            SectionOutcome.NOT_IN_BACKUP -> getString(R.string.backup_result_not_in_backup, title)
            SectionOutcome.NOT_SAVED -> getString(R.string.backup_result_not_saved, title, report.message.orEmpty())
            SectionOutcome.NEWER_THAN_APP -> getString(R.string.backup_result_newer, title)
            SectionOutcome.UNKNOWN_SECTION -> getString(R.string.backup_result_unknown, title)
            SectionOutcome.FAILED -> getString(R.string.backup_result_failed, title, report.message.orEmpty())
        }
        val warnings = report.warnings.take(MAX_WARNINGS_SHOWN).joinToString(separator = "") { "\n    • $it" }
        return line + warnings
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_error_title)
            .setMessage(message)
            .setPositiveButton(R.string.backup_close, null)
            .show()
    }

    private fun restartApp() {
        val main = packageManager.getLaunchIntentForPackage(packageName)?.component
        if (main == null) {
            finishAffinity()
            return
        }
        startActivity(Intent.makeRestartActivityTask(main))
        // Kill the existing process to ensure we get a clean start of the application.
        Process.killProcess(Process.myPid())
    }

    // endregion

    // region Views

    private fun titleOf(section: BackupSection): String = getString(section.titleRes)

    private fun labelOf(section: BackupSection): String = if (section.sensitive) {
        getString(R.string.backup_sensitive_label, titleOf(section))
    } else {
        titleOf(section)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    /** A vertical list with the margins of the screen. */
    private fun column(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(SCREEN_MARGIN), dp(SCREEN_MARGIN), dp(SCREEN_MARGIN), dp(SCREEN_MARGIN))
        build()
    }

    /** The content of a dialog: a vertical list that scrolls when it is too long. */
    private fun scrollable(build: LinearLayout.() -> Unit): View {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(DIALOG_MARGIN), dp(SMALL_GAP), dp(DIALOG_MARGIN), 0)
            build()
        }
        return ScrollView(this).apply { addView(list) }
    }

    private fun paragraph(text: String) = TextView(this).apply {
        this.text = text
        textSize = BODY_SIZE
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = NOTE_SIZE
        setTextColor(themeColor(android.R.attr.textColorSecondary))
    }

    private fun errorText() = TextView(this).apply {
        textSize = NOTE_SIZE
        setTextColor(themeColor(androidx.appcompat.R.attr.colorError))
        visibility = View.GONE
    }

    private fun passphraseField(@StringRes hint: Int) = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSingleLine()
    }

    private fun button(@StringRes label: Int, outlined: Boolean, onClick: () -> Unit): MaterialButton {
        val button = if (outlined) {
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        } else {
            MaterialButton(this)
        }
        return button.apply {
            setText(label)
            setOnClickListener { onClick() }
        }
    }

    // endregion

    private companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val ANY_TYPE = "*/*"
        const val FILE_NAME_PREFIX = "fenix-backup-"
        const val FILE_EXTENSION = ".fxbk"
        const val MIN_PASSPHRASE_LENGTH = 8
        const val MAX_WARNINGS_SHOWN = 3
        const val SCREEN_MARGIN = 16
        const val DIALOG_MARGIN = 24
        const val SMALL_GAP = 8
        const val BIG_GAP = 16
        const val BODY_SIZE = 16f
        const val NOTE_SIZE = 14f
    }
}

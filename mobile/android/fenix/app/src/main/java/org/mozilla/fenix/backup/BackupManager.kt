/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.pm.PackageInfoCompat
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes backup files and restores them. It sits between the screen ([BackupActivity]) and the files: the layers below
 * are [BackupContainer] (the header and the encryption), [BackupArchive] (the ZIP archive with a file for each section)
 * and the [BackupSection]s that know what the data looks like.
 *
 * Every function that touches files does its work on a background thread.
 *
 * @param context Any context. The application context of it is used.
 * @param sections What can be saved and restored, in the order of the work. Only tests give other sections.
 */
internal class BackupManager(
    context: Context,
    val sections: List<BackupSection> = BackupSections.all(context),
) {
    private val appContext: Context = context.applicationContext
    private val tempDir = tempDirectory(appContext)

    /** A backup file that is open for restoring. [close] deletes the decrypted copy that was made to read it. */
    internal class OpenBackup(
        val manifest: BackupManifest,
        private val opened: BackupContainer.Opened,
    ) : Closeable {
        val archive get() = opened.archive

        override fun close() = opened.close()
    }

    /** Deletes what an earlier run left behind in the temporary folder, for example because the app was killed. */
    suspend fun cleanUp() {
        withContext(Dispatchers.IO) {
            BackupContainer.deleteLeftovers(tempDir)
            File(tempDir, TABS_DIRECTORY).deleteRecursively()
        }
    }

    /**
     * Whether the file at [source] needs a passphrase to be opened. Throws a [BackupException] when it is not a backup
     * file this version of the app can read.
     */
    suspend fun isProtected(source: Uri): Boolean = withContext(Dispatchers.IO) {
        openInput(source).use { BackupContainer.readHeader(it).encrypted }
    }

    /**
     * Opens the backup file at [source]. The caller must close the result.
     *
     * Throws a [BackupException] when the file is not a backup, needs a passphrase that [passphrase] does not match, or
     * is damaged.
     */
    suspend fun open(source: Uri, passphrase: String?): OpenBackup = withContext(Dispatchers.IO) {
        val opened = BackupContainer.open(openInput(source), passphrase, tempDir)
        try {
            OpenBackup(BackupArchive.readManifest(opened.archive), opened)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
    }

    /**
     * Saves the sections with the ids in [selected] to the file at [destination], which is read back to make sure it
     * can be restored. The file is deleted when anything keeps it from being complete.
     *
     * @param destination The file to write, which the person chose.
     * @param passphrase Protects the file. The file is not protected when this is `null` or empty.
     * @param selected The ids of the sections to save.
     * @param onSection Called, from a background thread, before each section is saved.
     * @return How every section went.
     */
    suspend fun create(
        destination: Uri,
        passphrase: String?,
        selected: Set<String>,
        onSection: (BackupSection) -> Unit = {},
    ): List<SectionReport> = withContext(Dispatchers.IO) {
        val chosen = sections.filter { it.id in selected }
        var complete = false
        try {
            val out = appContext.contentResolver.openOutputStream(destination, "wt")
                ?: throw BackupException(BackupException.Reason.OTHER, "The file could not be opened for writing.")
            val archive = BackupContainer.create(out, passphrase)
            val reports = try {
                BackupArchive.write(archive, chosen, appInfo(), System.currentTimeMillis(), onSection)
            } finally {
                // Closes the file. This also happens inside write() when it ends well, and closing twice does no harm.
                runCatching { archive.close() }
            }
            verify(destination, passphrase)
            complete = true
            reports
        } finally {
            if (!complete) discard(destination)
        }
    }

    /**
     * Restores the sections with the ids in [selected] from [backup].
     *
     * @param onSection Called, from a background thread, before each section is restored.
     * @return How every section went.
     */
    suspend fun restore(
        backup: OpenBackup,
        selected: Set<String>,
        onSection: (BackupSection) -> Unit = {},
    ): List<SectionReport> = withContext(Dispatchers.IO) {
        BackupArchive.restore(backup.archive, backup.manifest, sections, selected, onSection)
    }

    /** Opens the file that was just written, to find out whether it can be used. */
    private fun verify(file: Uri, passphrase: String?) {
        try {
            BackupContainer.open(openInput(file), passphrase, tempDir).use { BackupArchive.readManifest(it.archive) }
        } catch (e: BackupException) {
            throw unreadable(e)
        } catch (e: IOException) {
            throw unreadable(e)
        }
    }

    private fun unreadable(cause: Exception) = BackupException(
        BackupException.Reason.OTHER,
        "The backup file was written, but it could not be read back. Try again, or choose another place for the file.",
        cause,
    )

    /** Deletes a file that was made for a backup that did not work out, so that nobody mistakes it for one that did. */
    private fun discard(file: Uri) {
        try {
            DocumentsContract.deleteDocument(appContext.contentResolver, file)
        } catch (e: Exception) {
            // The file stays. There is nothing else to do about it, and the problem that is reported is the first one.
        }
    }

    private fun openInput(source: Uri): InputStream = appContext.contentResolver.openInputStream(source)
        ?: throw BackupException(BackupException.Reason.OTHER, "The file could not be opened.")

    private fun appInfo(): BackupArchive.AppInfo {
        val info = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return BackupArchive.AppInfo(
            versionName = info?.versionName,
            versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) },
            androidSdk = Build.VERSION.SDK_INT,
        )
    }

    companion object {
        private const val TEMP_DIRECTORY = "backup"

        /** The folder inside it where the tabs are written, one file each, while they are saved and restored. */
        const val TABS_DIRECTORY = "tabs"

        /** Where files are kept while a backup is made or restored. They are deleted afterwards. */
        fun tempDirectory(context: Context): File = File(context.cacheDir, TEMP_DIRECTORY)
    }
}

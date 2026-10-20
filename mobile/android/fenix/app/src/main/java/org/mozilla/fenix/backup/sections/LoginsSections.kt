/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mozilla.components.concept.storage.LoginEntry
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.putIfNotNull
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.ext.components

/**
 * The saved logins: the website, user name and password, and the details of the form they were saved from.
 *
 * Restoring adds the logins that are not saved yet. A login is saved already when there is one for the same website and
 * user name, and then the saved one is left as it is, including its password, so a restore never overwrites a password
 * that was changed since the backup. When a login is added, the times it was created and last used are the time of the
 * restore: the storage of logins does not let them be set. The times are kept in the backup file all the same.
 *
 * The passwords are written to the backup file as text, so this section is marked as [sensitive]: the screen that makes
 * backups asks for a passphrase to protect the file.
 */
internal class LoginsSection(private val context: Context) : BackupSection {
    override val id = "logins"
    override val version = 1
    override val titleRes = R.string.backup_section_logins
    override val sensitive = true

    override suspend fun export(out: SectionWriter) {
        for (login in context.components.core.passwordsStorage.list()) {
            out.write(
                buildJsonObject {
                    put("origin", login.origin)
                    putIfNotNull("formActionOrigin", login.formActionOrigin)
                    putIfNotNull("httpRealm", login.httpRealm)
                    put("usernameField", login.usernameField)
                    put("passwordField", login.passwordField)
                    put("username", login.username)
                    put("password", login.password)
                    put("timesUsed", login.timesUsed)
                    put("timeCreated", login.timeCreated)
                    put("timeLastUsed", login.timeLastUsed)
                    put("timePasswordChanged", login.timePasswordChanged)
                },
            )
        }
    }

    override suspend fun import(input: SectionReader) {
        val storage = context.components.core.passwordsStorage
        for (line in input.entries()) {
            val origin = line.string("origin")
            val password = line.string("password")
            if (origin == null || password == null) {
                input.skipped()
                continue
            }
            val entry = LoginEntry(
                origin = origin,
                formActionOrigin = line.string("formActionOrigin"),
                httpRealm = line.string("httpRealm"),
                usernameField = line.string("usernameField").orEmpty(),
                passwordField = line.string("passwordField").orEmpty(),
                username = line.string("username").orEmpty(),
                password = password,
            )
            try {
                if (storage.findLoginToUpdate(entry) != null) {
                    input.skipped()
                } else {
                    storage.add(entry)
                    input.restored()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the website is named: nothing about the login itself may end up in a message.
                input.skipped()
                input.warn("A login for $origin could not be added: ${e.javaClass.simpleName}")
            }
        }
    }
}

/** The websites the person told the app never to save logins for. Websites that are in the list already are kept. */
internal class LoginExceptionsSection(private val context: Context) : BackupSection {
    override val id = "login_exceptions"
    override val version = 1
    override val titleRes = R.string.backup_section_login_exceptions

    override suspend fun export(out: SectionWriter) {
        val storage = context.components.core.loginExceptionStorage
        for (exception in storage.getLoginExceptions().first()) {
            out.write(buildJsonObject { put("origin", exception.origin) })
        }
    }

    override suspend fun import(input: SectionReader) {
        val storage = context.components.core.loginExceptionStorage
        for (line in input.entries()) {
            val origin = line.string("origin")
            if (origin.isNullOrEmpty()) {
                input.skipped()
                continue
            }
            // The storage is a database that must not be used on the main thread.
            val added = withContext(Dispatchers.IO) {
                if (storage.isLoginExceptionByOrigin(origin)) {
                    false
                } else {
                    storage.addLoginException(origin)
                    true
                }
            }
            if (added) input.restored() else input.skipped()
        }
    }
}

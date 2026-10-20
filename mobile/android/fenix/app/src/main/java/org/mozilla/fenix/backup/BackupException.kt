/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

/**
 * A problem with a backup file or a backup operation that the user can understand. [message] is written to be shown to
 * the user as it is; [reason] lets the UI react (for example by asking for the passphrase again).
 */
class BackupException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Why a backup operation failed. */
    enum class Reason {
        /** The file is not a backup made by this feature. */
        NOT_A_BACKUP,

        /** The file was made by a newer version of the app that this version cannot read. */
        NEWER_VERSION,

        /** The file is encrypted and no passphrase was given. */
        PASSPHRASE_REQUIRED,

        /** The passphrase is wrong, or the encrypted file was damaged or cut short. */
        WRONG_PASSPHRASE_OR_DAMAGED,

        /** The file is damaged. */
        DAMAGED,

        /** Anything else. */
        OTHER,
    }
}

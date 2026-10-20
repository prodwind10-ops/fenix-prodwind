/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipFile

/**
 * The outer layer of a backup file: a small header followed by a ZIP archive, which is encrypted when the backup has
 * a passphrase. What is inside the ZIP archive is described in [BackupArchive].
 *
 * ```
 * "FXBK"                    4 bytes   magic
 * format version            1 byte    FORMAT_VERSION
 * flags                     1 byte    bit 0: the archive is encrypted
 * ---- only when encrypted ----
 * key derivation function   1 byte    BackupCrypto.KDF_PBKDF2_HMAC_SHA256
 * iterations                4 bytes   big endian
 * salt                      16 bytes
 * nonce prefix              7 bytes
 * chunk size                4 bytes   big endian
 * ---- then ----
 * the ZIP archive, or the encrypted chunks of it (see BackupCrypto)
 * ```
 *
 * A change that older versions of the app cannot read raises [FORMAT_VERSION]. Newer versions keep reading every
 * older format version.
 *
 * Only `java.*` classes are used, so this can be tested on a plain JVM.
 */
internal object BackupContainer {
    const val FORMAT_VERSION = 1

    private val MAGIC = byteArrayOf(0x46, 0x58, 0x42, 0x4B) // "FXBK"
    private const val FLAG_ENCRYPTED = 1
    private const val KNOWN_FLAGS = FLAG_ENCRYPTED
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val TEMP_PREFIX = "backup-"
    private const val TEMP_SUFFIX = ".tmp"
    private val NO_BYTES = ByteArray(0)

    /** What the header of a backup file says. */
    class Header internal constructor(
        val formatVersion: Int,
        val encrypted: Boolean,
        internal val iterations: Int,
        internal val salt: ByteArray,
        internal val noncePrefix: ByteArray,
        internal val chunkBytes: Int,
        internal val raw: ByteArray,
    )

    /** A backup file that has been decrypted into a temporary file. [close] deletes that file. */
    class Opened internal constructor(
        val archive: ZipFile,
        private val tempFile: File,
    ) : Closeable {
        override fun close() {
            try {
                archive.close()
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Starts a backup file on [out] and returns the stream that the ZIP archive is written to. Closing that stream
     * finishes the file and closes [out]. When the file is encrypted, it is only complete after the stream is closed.
     *
     * @param out Where the file goes. It is closed when something goes wrong here.
     * @param passphrase Encrypts the file. When it is `null` or empty, the file is not encrypted.
     * @param iterations The key derivation work factor, stored in the file.
     * @param chunkBytes The size of the encrypted chunks, stored in the file.
     * @param random Where the salt and nonce prefix come from.
     */
    fun create(
        out: OutputStream,
        passphrase: String?,
        iterations: Int = BackupCrypto.DEFAULT_ITERATIONS,
        chunkBytes: Int = BackupCrypto.DEFAULT_CHUNK_BYTES,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        try {
            if (passphrase.isNullOrEmpty()) {
                out.write(buildHeader(encrypted = false, iterations = 0, salt = NO_BYTES, noncePrefix = NO_BYTES, 0))
                return out
            }
            val salt = ByteArray(BackupCrypto.SALT_BYTES).also { random.nextBytes(it) }
            val noncePrefix = ByteArray(BackupCrypto.NONCE_PREFIX_BYTES).also { random.nextBytes(it) }
            val header = buildHeader(encrypted = true, iterations, salt, noncePrefix, chunkBytes)
            val key = BackupCrypto.deriveKey(passphrase, salt, iterations)
            out.write(header)
            return BackupCrypto.EncryptingOutputStream(out, key, noncePrefix, header, chunkBytes)
        } catch (e: Throwable) {
            runCatching { out.close() }
            throw e
        }
    }

    /**
     * Writes a whole backup file to [out], which is closed afterwards. See [create] for the parameters.
     *
     * @param writeArchive Writes the ZIP archive to the stream it is given. It may close that stream.
     */
    @Suppress("LongParameterList")
    fun write(
        out: OutputStream,
        passphrase: String?,
        iterations: Int = BackupCrypto.DEFAULT_ITERATIONS,
        chunkBytes: Int = BackupCrypto.DEFAULT_CHUNK_BYTES,
        random: SecureRandom = SecureRandom(),
        writeArchive: (OutputStream) -> Unit,
    ) {
        val archive = create(out, passphrase, iterations, chunkBytes, random)
        try {
            writeArchive(archive)
            archive.close()
        } finally {
            // Also close when something went wrong. Closing twice does no harm, and a failure here must not hide the
            // original problem.
            runCatching { archive.close() }
        }
    }

    /**
     * Reads the header of a backup file from the start of [input], to find out what it takes to open it.
     * Throws a [BackupException] when [input] is not a backup file this version of the app can read.
     */
    fun readHeader(input: InputStream): Header {
        val raw = ByteArrayOutputStream()
        var recognized = false

        fun take(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var total = 0
            while (total < count) {
                val read = input.read(bytes, total, count - total)
                if (read < 0) throw EOFException()
                total += read
            }
            raw.write(bytes)
            return bytes
        }

        try {
            if (!take(MAGIC.size).contentEquals(MAGIC)) throw notABackup()
            recognized = true

            val formatVersion = take(1)[0].toInt() and 0xFF
            if (formatVersion < 1) throw notABackup()
            if (formatVersion > FORMAT_VERSION) throw newerVersion()

            val flags = take(1)[0].toInt() and 0xFF
            if (flags and KNOWN_FLAGS.inv() != 0) throw newerVersion()
            if (flags and FLAG_ENCRYPTED == 0) {
                return Header(formatVersion, false, 0, NO_BYTES, NO_BYTES, 0, raw.toByteArray())
            }

            if ((take(1)[0].toInt() and 0xFF) != BackupCrypto.KDF_PBKDF2_HMAC_SHA256) throw newerVersion()
            val iterations = ByteBuffer.wrap(take(4)).int
            val salt = take(BackupCrypto.SALT_BYTES)
            val noncePrefix = take(BackupCrypto.NONCE_PREFIX_BYTES)
            val chunkBytes = ByteBuffer.wrap(take(4)).int
            if (iterations !in 1..BackupCrypto.MAX_ITERATIONS ||
                chunkBytes !in BackupCrypto.MIN_CHUNK_BYTES..BackupCrypto.MAX_CHUNK_BYTES
            ) {
                throw BackupException(BackupException.Reason.DAMAGED, "The backup file is damaged.")
            }
            return Header(formatVersion, true, iterations, salt, noncePrefix, chunkBytes, raw.toByteArray())
        } catch (e: EOFException) {
            throw if (recognized) {
                BackupException(BackupException.Reason.DAMAGED, "The backup file is incomplete.", e)
            } else {
                notABackup(e)
            }
        }
    }

    /**
     * Reads the backup file from [input] and decrypts it into a temporary file in [tempDir], which is opened as a ZIP
     * archive. [input] is closed afterwards. The caller must close the result to delete the temporary file.
     *
     * Throws a [BackupException] when [input] is not a backup file, needs a passphrase that [passphrase] does not
     * match, or is damaged.
     */
    fun open(input: InputStream, passphrase: String?, tempDir: File): Opened {
        input.use {
            val header = readHeader(input)
            val plain: InputStream = if (header.encrypted) {
                if (passphrase.isNullOrEmpty()) {
                    throw BackupException(
                        BackupException.Reason.PASSPHRASE_REQUIRED,
                        "This backup is protected with a passphrase.",
                    )
                }
                BackupCrypto.DecryptingInputStream(
                    input,
                    BackupCrypto.deriveKey(passphrase, header.salt, header.iterations),
                    header.noncePrefix,
                    header.raw,
                    header.chunkBytes,
                )
            } else {
                input
            }

            if (!tempDir.isDirectory && !tempDir.mkdirs()) throw IOException("Could not create $tempDir")
            val tempFile = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, tempDir)
            try {
                tempFile.outputStream().use { plain.copyTo(it, COPY_BUFFER_BYTES) }
                val archive = try {
                    ZipFile(tempFile)
                } catch (e: IOException) {
                    throw BackupException(BackupException.Reason.DAMAGED, "The backup file is damaged.", e)
                }
                return Opened(archive, tempFile)
            } catch (e: Throwable) {
                tempFile.delete()
                throw e
            }
        }
    }

    /** Deletes decrypted files that an earlier run left behind, for example because the app was killed. */
    fun deleteLeftovers(tempDir: File) {
        tempDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX)) file.delete()
        }
    }

    private fun buildHeader(
        encrypted: Boolean,
        iterations: Int,
        salt: ByteArray,
        noncePrefix: ByteArray,
        chunkBytes: Int,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.write(MAGIC)
            data.writeByte(FORMAT_VERSION)
            data.writeByte(if (encrypted) FLAG_ENCRYPTED else 0)
            if (encrypted) {
                data.writeByte(BackupCrypto.KDF_PBKDF2_HMAC_SHA256)
                data.writeInt(iterations)
                data.write(salt)
                data.write(noncePrefix)
                data.writeInt(chunkBytes)
            }
        }
        return bytes.toByteArray()
    }

    private fun notABackup(cause: Throwable? = null) = BackupException(
        BackupException.Reason.NOT_A_BACKUP,
        "This file is not a backup made by this app.",
        cause,
    )

    private fun newerVersion() = BackupException(
        BackupException.Reason.NEWER_VERSION,
        "This backup was made by a newer version of the app. Update the app and try again.",
    )
}

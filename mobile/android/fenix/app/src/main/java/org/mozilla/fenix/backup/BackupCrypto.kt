/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption of a backup file.
 *
 * - The key is derived from the passphrase with PBKDF2-HMAC-SHA256 and a random salt, so every file has its own key.
 * - The data is cut into chunks that are each sealed with AES-256-GCM. A chunk's nonce holds its position and a flag
 *   that marks the last chunk, so chunks cannot be reordered, dropped, or cut off at the end without being noticed.
 * - The file header is authenticated together with every chunk, so it cannot be changed either.
 *
 * Everything here only uses `java.*` and `javax.crypto.*`, so it can be tested on a plain JVM.
 */
internal object BackupCrypto {
    const val KDF_PBKDF2_HMAC_SHA256 = 1

    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 7
    const val TAG_BYTES = 16

    /** Work factor for new backups. The value that was used is stored in each file, so this can be raised later. */
    const val DEFAULT_ITERATIONS = 600_000

    /** Largest work factor accepted when reading a file, so a damaged header cannot make the app hang. */
    const val MAX_ITERATIONS = 20_000_000

    const val DEFAULT_CHUNK_BYTES = 64 * 1024
    const val MIN_CHUNK_BYTES = 1
    const val MAX_CHUNK_BYTES = 16 * 1024 * 1024

    private const val KEY_BITS = 256
    private const val TAG_BITS = TAG_BYTES * 8
    private const val NONCE_BYTES = 12
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAX_CHUNKS = 1L shl 32

    /**
     * Turns [passphrase] into an AES key. The passphrase is normalized (NFC) first so that the same text typed on
     * different keyboards gives the same key.
     */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        require(passphrase.isNotEmpty()) { "The passphrase must not be empty." }
        require(iterations in 1..MAX_ITERATIONS) { "Unsupported iteration count: $iterations" }
        val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        try {
            val keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            try {
                return SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun nonce(prefix: ByteArray, chunkIndex: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_BYTES)
        nonce[7] = (chunkIndex ushr 24).toByte()
        nonce[8] = (chunkIndex ushr 16).toByte()
        nonce[9] = (chunkIndex ushr 8).toByte()
        nonce[10] = chunkIndex.toByte()
        nonce[11] = if (last) 1 else 0
        return nonce
    }

    private fun newCipher(): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)

    /**
     * Encrypts everything written to it and writes the result to [out]. [close] must be called to seal the last
     * chunk; without it the file is cut short and will be rejected when it is read.
     *
     * @param out Where the encrypted data goes. It is closed when this stream is closed.
     * @param key The key from [deriveKey].
     * @param noncePrefix [NONCE_PREFIX_BYTES] random bytes stored in the file header.
     * @param aad The file header, which is authenticated along with the data.
     * @param chunkBytes How much data goes into each chunk.
     */
    class EncryptingOutputStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
        private val aad: ByteArray,
        private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    ) : OutputStream() {
        private val cipher = newCipher()
        private val buffer = ByteArray(chunkBytes)
        private var buffered = 0
        private var chunkIndex = 0L
        private var closed = false

        init {
            require(noncePrefix.size == NONCE_PREFIX_BYTES) { "Bad nonce prefix size" }
            require(chunkBytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "Bad chunk size" }
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream is closed")
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // A full chunk is only sealed once more data follows, because the last chunk is marked as such.
                if (buffered == chunkBytes) sealChunk(last = false)
                val count = minOf(remaining, chunkBytes - buffered)
                System.arraycopy(b, offset, buffer, buffered, count)
                buffered += count
                offset += count
                remaining -= count
            }
        }

        override fun flush() {
            if (!closed) out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                sealChunk(last = true)
                out.flush()
            } finally {
                buffer.fill(0)
                out.close()
            }
        }

        private fun sealChunk(last: Boolean) {
            if (chunkIndex >= MAX_CHUNKS) throw IOException("The backup is too large")
            try {
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, nonce(noncePrefix, chunkIndex, last)),
                )
                cipher.updateAAD(aad)
                out.write(cipher.doFinal(buffer, 0, buffered))
            } catch (e: GeneralSecurityException) {
                throw IOException("Could not encrypt the backup", e)
            }
            buffered = 0
            chunkIndex++
        }
    }

    /**
     * Reads what [EncryptingOutputStream] wrote. Data is only handed out after its chunk has been verified.
     *
     * Throws a [BackupException] when the passphrase is wrong or the data was changed or cut short.
     *
     * @param input The encrypted data, positioned right after the file header. It is closed with this stream.
     * @param key The key from [deriveKey].
     * @param noncePrefix The prefix stored in the file header.
     * @param aad The file header exactly as it was read.
     * @param chunkBytes The chunk size stored in the file header.
     */
    class DecryptingInputStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
        private val aad: ByteArray,
        chunkBytes: Int,
    ) : InputStream() {
        private val cipher = newCipher()
        private val sealedChunkBytes = chunkBytes + TAG_BYTES

        // One sealed chunk plus one byte of look-ahead. The look-ahead is how the last chunk is recognized.
        private val window = ByteArray(sealedChunkBytes + 1)
        private var windowBytes = 0
        private var started = false
        private var finished = false
        private var chunkIndex = 0L
        private var plain = ByteArray(0)
        private var plainPosition = 0
        private val single = ByteArray(1)

        init {
            require(noncePrefix.size == NONCE_PREFIX_BYTES) { "Bad nonce prefix size" }
            require(chunkBytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "Bad chunk size" }
        }

        override fun read(): Int {
            val count = read(single, 0, 1)
            return if (count <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            while (plainPosition == plain.size) {
                if (finished) return -1
                openNextChunk()
            }
            val count = minOf(len, plain.size - plainPosition)
            System.arraycopy(plain, plainPosition, b, off, count)
            plainPosition += count
            return count
        }

        override fun available(): Int = plain.size - plainPosition

        override fun close() {
            plain.fill(0)
            input.close()
        }

        private fun openNextChunk() {
            if (!started) {
                windowBytes = readFully(window, 0, window.size)
                started = true
            }
            val last = windowBytes <= sealedChunkBytes
            val chunkLength = if (last) windowBytes else sealedChunkBytes
            if (chunkLength < TAG_BYTES) {
                throw BackupException(
                    BackupException.Reason.DAMAGED,
                    "The backup file is incomplete.",
                )
            }
            val opened = try {
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, nonce(noncePrefix, chunkIndex, last)),
                )
                cipher.updateAAD(aad)
                cipher.doFinal(window, 0, chunkLength)
            } catch (e: GeneralSecurityException) {
                // If the very first chunk does not check out, the key is wrong (or the file is damaged right at the
                // start). Later on, the key has proven to be right, so something happened to the file.
                throw if (chunkIndex == 0L) {
                    BackupException(
                        BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED,
                        "Wrong passphrase, or the backup file is damaged.",
                        e,
                    )
                } else {
                    BackupException(
                        BackupException.Reason.DAMAGED,
                        "The backup file is damaged or incomplete.",
                        e,
                    )
                }
            }
            plain.fill(0)
            plain = opened
            plainPosition = 0
            chunkIndex++
            if (last) {
                finished = true
            } else {
                window[0] = window[sealedChunkBytes]
                windowBytes = 1 + readFully(window, 1, sealedChunkBytes)
            }
        }

        private fun readFully(into: ByteArray, off: Int, len: Int): Int {
            var total = 0
            while (total < len) {
                val count = input.read(into, off + total, len - total)
                if (count < 0) break
                total += count
            }
            return total
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mozilla.components.concept.engine.CancellableOperation
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.bool
import org.mozilla.fenix.backup.double
import org.mozilla.fenix.backup.int
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.ext.components

/**
 * The conversation between the app and the small extension that reads and writes the cookies of the browser (see
 * `assets/extensions/backup_cookies/background.js`, which is the other side of it).
 *
 * The browser engine has no way to list or add cookies for an app, only for extensions. So the app installs a built-in
 * extension for as long as the work takes, and the extension asks the app what to do. Every message of the extension
 * is answered with the next thing to do, which makes this a plain function from message to reply, without waiting for
 * anything. That is what lets it be tested without a browser.
 *
 * Messages of the extension: `hello`, `cookies` (a part of the cookies, when exporting), `exportDone`, `imported` (the
 * result for a part of the cookies, when importing) and `error`. Replies of the app: `export`, `import` (with the next
 * part of the cookies), `next` and `done`.
 *
 * @param chunksToImport The cookies to restore, in parts of a size the extension can handle at once. When this is
 *   `null`, the cookies are read instead.
 */
internal class CookieExchange(private val chunksToImport: List<List<JsonObject>>? = null) {
    /** Called once, when the extension has said hello. */
    var onStarted: () -> Unit = {}

    /** Called once, when there is nothing more to do. */
    var onFinished: () -> Unit = {}

    /** The cookies the extension reported, when reading. */
    val exported = ArrayList<JsonObject>()

    /** How many cookies the extension set, when restoring. */
    var imported = 0
        private set

    /** How many cookies the browser had already, when restoring. */
    var existing = 0
        private set

    /** How many cookies the browser did not accept, when restoring. */
    var rejected = 0
        private set

    /** The first reason a cookie was not accepted. */
    var firstRejection: String? = null
        private set

    /** What the extension reported when it could not do its work. */
    var extensionError: String? = null
        private set

    /** Whether there is nothing more to do. */
    var finished = false
        private set

    private var started = false
    private var nextChunk = 0

    @Volatile
    private var lastMessageNanos = System.nanoTime()

    /** How long ago the extension last sent a message. */
    fun idleMillis(): Long = (System.nanoTime() - lastMessageNanos) / NANOS_PER_MILLI

    /** Handles [message] of the extension and returns the reply to it. */
    fun onMessage(message: JsonObject): JsonObject {
        lastMessageNanos = System.nanoTime()
        return when (message.string("type")) {
            "hello" -> hello()
            "cookies" -> {
                (message["cookies"] as? JsonArray)?.forEach { if (it is JsonObject) exported.add(it) }
                reply("next")
            }
            "exportDone" -> finish()
            "imported" -> {
                imported += message.int("set") ?: 0
                existing += message.int("existing") ?: 0
                val failed = message.int("failed") ?: 0
                rejected += failed
                if (failed > 0 && firstRejection == null) firstRejection = message.string("error")
                nextImport()
            }
            "error" -> {
                extensionError = message.string("message") ?: "unknown error"
                finish()
            }
            else -> reply("next")
        }
    }

    private fun hello(): JsonObject {
        // A second hello comes from an extension that did not get the answer to its first one: leave it be.
        if (started) return reply("done")
        started = true
        onStarted()
        return if (chunksToImport == null) reply("export") else nextImport()
    }

    private fun nextImport(): JsonObject {
        val chunks = chunksToImport ?: return finish()
        if (nextChunk >= chunks.size) return finish()
        return buildJsonObject {
            put("cmd", "import")
            put("cookies", JsonArray(chunks[nextChunk++]))
        }
    }

    private fun finish(): JsonObject {
        if (!finished) {
            finished = true
            onFinished()
        }
        return reply("done")
    }

    private fun reply(command: String) = buildJsonObject { put("cmd", command) }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Hands the messages of the browser engine to a [CookieExchange]. */
internal class CookieBridge(private val exchange: CookieExchange) : MessageHandler {
    override fun onMessage(message: Any, source: EngineSession?): Any? {
        val json = try {
            Json.parseToJsonElement(message.toString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        // The engine needs an answer to every message, also to one that cannot be understood.
        return JSONObject(exchange.onMessage(json ?: JsonObject(emptyMap())).toString())
    }
}

/** Installs the cookie extension, lets a [CookieExchange] run, and removes the extension again. */
internal object BrowserCookies {
    /** Must be the same as `browser_specific_settings.gecko.id` in the manifest of the extension. */
    const val EXTENSION_ID = "backup-cookies@fenixfork.local"
    const val EXTENSION_URL = "resource://android/assets/extensions/backup_cookies/"

    /** Must be the same as `APP` in the background script of the extension. */
    const val MESSAGE_NAME = "fenixForkBackupCookies"

    private const val LIST_TIMEOUT_MILLIS = 10_000L
    private const val INSTALL_TIMEOUT_MILLIS = 30_000L
    private const val START_TIMEOUT_MILLIS = 20_000L
    private const val UNINSTALL_TIMEOUT_MILLIS = 15_000L
    private const val POLL_MILLIS = 1_000L

    /** How long the extension may stay silent in the middle of its work. */
    private const val IDLE_TIMEOUT_MILLIS = 60_000L

    /** Runs [exchange] against the browser. Throws an exception when the extension cannot be used. */
    suspend fun run(context: Context, exchange: CookieExchange) {
        val engine = context.components.core.engine
        val installed = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        exchange.onStarted = { started.complete(Unit) }
        exchange.onFinished = { finished.complete(Unit) }

        val extension = AtomicReference<WebExtension?>(null)
        val installation = AtomicReference<CancellableOperation?>(null)
        val over = AtomicBoolean(false)

        try {
            removeLeftovers(engine)
            // The engine is used from the main thread, like everywhere else in the app.
            withContext(Dispatchers.Main) {
                installation.set(
                    engine.installBuiltInWebExtension(
                        id = EXTENSION_ID,
                        url = EXTENSION_URL,
                        onSuccess = { installedExtension ->
                            extension.set(installedExtension)
                            if (over.get()) {
                                // This took so long that nobody is waiting for it any more: do not leave it behind.
                                engine.uninstallWebExtension(installedExtension)
                            } else {
                                // What the extension sent before this is kept by the engine until it is registered.
                                installedExtension.registerBackgroundMessageHandler(
                                    MESSAGE_NAME,
                                    CookieBridge(exchange),
                                )
                                installed.complete(Unit)
                            }
                        },
                        onError = { throwable -> installed.completeExceptionally(throwable) },
                    ),
                )
            }

            await(installed, INSTALL_TIMEOUT_MILLIS, "The browser did not install the extension that handles cookies.")
            await(started, START_TIMEOUT_MILLIS, "The extension that handles cookies did not start.")
            while (!finished.isCompleted) {
                withTimeoutOrNull(POLL_MILLIS) { finished.await() }
                if (!finished.isCompleted && exchange.idleMillis() > IDLE_TIMEOUT_MILLIS) {
                    throw IOException("The browser stopped answering while it handled the cookies.")
                }
            }
        } finally {
            over.set(true)
            withContext(NonCancellable) {
                installation.get()?.let { if (!installed.isCompleted) it.cancel() }
                extension.get()?.let { uninstall(engine, it) }
            }
        }
    }

    private suspend fun await(step: CompletableDeferred<Unit>, timeoutMillis: Long, failure: String) {
        val done = try {
            withTimeoutOrNull(timeoutMillis) {
                step.await()
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("$failure (${e.javaClass.simpleName}: ${e.message})", e)
        }
        if (done == null) throw IOException(failure)
    }

    /** A copy that an earlier run left in the browser (the app was closed in the middle of it) would never start. */
    private suspend fun removeLeftovers(engine: Engine) {
        val listed = CompletableDeferred<List<WebExtension>>()
        withContext(Dispatchers.Main) {
            engine.listInstalledWebExtensions(
                onSuccess = { listed.complete(it) },
                onError = { listed.complete(emptyList()) },
            )
        }
        val leftovers = withTimeoutOrNull(LIST_TIMEOUT_MILLIS) { listed.await() }.orEmpty()
        for (extension in leftovers) {
            if (extension.id == EXTENSION_ID) uninstall(engine, extension)
        }
    }

    private suspend fun uninstall(engine: Engine, extension: WebExtension) {
        val removed = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            engine.uninstallWebExtension(
                extension,
                onSuccess = { removed.complete(Unit) },
                onError = { _, _ -> removed.complete(Unit) },
            )
        }
        withTimeoutOrNull(UNINSTALL_TIMEOUT_MILLIS) { removed.await() }
    }
}

/**
 * The cookies of the browser, including the ones that keep the person logged in to websites.
 *
 * They are read and written by the extension of [BrowserCookies]. Restoring adds the cookies the browser does not have
 * and leaves the others as they are. Cookies that expired are not restored. A cookie that has no expiry time (it would
 * be gone when the browser closes) gets one of two weeks, because otherwise it would not survive the restart that the
 * restored data needs. Cookies of private tabs are not part of this.
 *
 * The cookies are marked as [sensitive], because whoever has them can act as the person on the websites they belong
 * to: the screen that makes backups asks for a passphrase to protect the file.
 */
internal class CookiesSection(private val context: Context) : BackupSection {
    override val id = "cookies"
    override val version = 1
    override val titleRes = R.string.backup_section_cookies
    override val sensitive = true

    override suspend fun export(out: SectionWriter) {
        val exchange = CookieExchange()
        BrowserCookies.run(context, exchange)
        exchange.extensionError?.let { throw IOException("The browser could not read its cookies: $it") }
        for (cookie in exchange.exported) {
            out.write(JsonObject(cookie.filterKeys { it in COOKIE_FIELDS }))
        }
    }

    override suspend fun import(input: SectionReader) {
        val now = System.currentTimeMillis() / MILLIS_PER_SECOND
        val cookies = ArrayList<JsonObject>()
        for (line in input.entries()) {
            val usable = !line.string("name").isNullOrEmpty() &&
                line.string("value") != null &&
                !line.string("domain").isNullOrEmpty()
            val expired = line.bool("session") != true && (line.double("expirationDate") ?: Double.MAX_VALUE) < now
            if (!usable || expired) {
                input.skipped()
            } else {
                cookies.add(JsonObject(line.filterKeys { it in COOKIE_FIELDS }))
            }
        }
        if (cookies.isEmpty()) return

        val exchange = CookieExchange(cookies.chunked(COOKIES_PER_MESSAGE))
        try {
            BrowserCookies.run(context, exchange)
        } finally {
            // Whatever was done before a problem is reported as done.
            input.restored(exchange.imported)
            input.skipped(exchange.existing + exchange.rejected)
            exchange.firstRejection?.let { input.warn("${exchange.rejected} cookies were not accepted: $it") }
        }
        exchange.extensionError?.let { throw IOException("The browser could not restore its cookies: $it") }
    }

    private companion object {
        const val COOKIES_PER_MESSAGE = 100
        const val MILLIS_PER_SECOND = 1000.0

        /**
         * The parts of a cookie that are saved. They are the ones the extension sends (see `toBackupCookie` in its
         * background script); a backup never holds anything else the browser may add to a cookie.
         */
        val COOKIE_FIELDS = setOf(
            "name",
            "value",
            "domain",
            "hostOnly",
            "path",
            "secure",
            "httpOnly",
            "sameSite",
            "session",
            "expirationDate",
            "firstPartyDomain",
            "partitionKey",
        )
    }
}

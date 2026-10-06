/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray.repository.uistate

import org.json.JSONException
import org.json.JSONObject

/**
 * Encodes this map as a JSON object string so it can be stored in a single Preferences DataStore entry, which has no
 * native map type.
 */
internal fun Map<String, String>.toJsonString(): String {
    val json = JSONObject()
    forEach { (key, value) -> json.put(key, value) }
    return json.toString()
}

/**
 * Decodes a string written by [toJsonString] back into a map. A missing, blank, or unreadable value yields an empty
 * map rather than an error, so corrupt stored data can never prevent the Tab Manager from loading.
 */
internal fun String?.toStringMap(): Map<String, String> {
    val raw = this ?: return emptyMap()
    if (raw.isBlank()) return emptyMap()
    return try {
        val json = JSONObject(raw)
        val result = mutableMapOf<String, String>()
        json.keys().forEach { key -> result[key] = json.getString(key) }
        result
    } catch (e: JSONException) {
        emptyMap()
    }
}

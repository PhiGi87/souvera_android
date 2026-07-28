/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Minimal in-memory cookie jar scoped to one [OcsApi] instance. The Talk "join room"
 * (participants/active) request establishes a server-side session whose cookie must be carried into
 * the subsequent "join call" request on the same host; without it the server sees no active
 * participant session and the call join returns 404.
 */
class SessionCookieJar : CookieJar {

    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = store.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { host[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host]?.values?.toList().orEmpty()
}

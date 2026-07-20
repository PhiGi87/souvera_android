/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Restricts account creation to Souvera Workspaces: the user enters only a
 * workspace slug and the full server URL is derived as https://<slug>.<domain>.
 */
package com.souvera.workspace.login

object SouveraServerUrl {
    private val SLUG_REGEX = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
    private const val HTTPS_PREFIX = "https://"

    /**
     * Reduces any user input (bare slug, host or full URL) to a validated workspace
     * slug, or returns an empty string when it is not a valid Souvera Workspace.
     */
    fun extractSlug(rawInput: String, domain: String): String {
        var value = rawInput.trim().lowercase()
        if (value.isEmpty()) return ""
        value = value.removePrefix(HTTPS_PREFIX).removePrefix("http://")
        value = value.substringBefore('/').substringBefore('?')
        val dotDomain = ".${domain.trim().lowercase()}"
        if (value.endsWith(dotDomain)) {
            value = value.substring(0, value.length - dotDomain.length)
        }
        return if (SLUG_REGEX.matches(value)) value else ""
    }

    fun buildUrl(slug: String, domain: String): String = "$HTTPS_PREFIX$slug.${domain.trim().lowercase()}"
}

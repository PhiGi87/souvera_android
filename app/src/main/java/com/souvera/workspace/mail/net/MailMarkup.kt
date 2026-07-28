/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

/**
 * Converts the composer's lightweight markup (`**bold**`, `*italic*`, `__underline__`) into the
 * HTML alternative part of an outgoing message. The plain-text part keeps the markers, which stay
 * readable as classic e-mail emphasis.
 */
object MailMarkup {

    fun toHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(boldPattern) { "<b>${it.groupValues[1]}</b>" }
        .replace(underlinePattern) { "<u>${it.groupValues[1]}</u>" }
        .replace(italicPattern) { "<i>${it.groupValues[1]}</i>" }
        .replace("\n", "<br>")

    private val boldPattern = Regex("""\*\*(.+?)\*\*""")
    private val underlinePattern = Regex("""__(.+?)__""")
    private val italicPattern = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")
}

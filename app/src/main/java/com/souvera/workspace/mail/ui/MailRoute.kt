/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.ui

import com.souvera.workspace.mail.db.entity.MessageEntity

/** In-activity navigation target for the native mail client. */
sealed interface MailRoute {
    data object Home : MailRoute
    data object Search : MailRoute
    data class Detail(val message: MessageEntity) : MailRoute
    data class Compose(
        val to: String = "",
        val subject: String = "",
        val inReplyTo: String? = null,
        val quoted: String = ""
    ) : MailRoute
}

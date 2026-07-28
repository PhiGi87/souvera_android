/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.db

import androidx.room.TypeConverter
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.NamespaceType

class MailTypeConverters {
    @TypeConverter
    fun kindToString(kind: MailboxKind): String = kind.name

    @TypeConverter
    fun stringToKind(value: String): MailboxKind = MailboxKind.valueOf(value)

    @TypeConverter
    fun namespaceToString(type: NamespaceType): String = type.name

    @TypeConverter
    fun stringToNamespace(value: String): NamespaceType = NamespaceType.valueOf(value)
}

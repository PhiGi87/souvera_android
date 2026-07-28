/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.contacts

/** The email addresses and phone numbers of one contact, shown in [ContactDetailActivity]. */
data class ContactDetail(val emails: List<String>, val phones: List<String>)

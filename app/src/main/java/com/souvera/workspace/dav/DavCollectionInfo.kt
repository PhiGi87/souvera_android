/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.dav

/** One discovered CalDAV calendar or CardDAV address book, identified by its server href. */
data class DavCollectionInfo(val href: String, val displayName: String)

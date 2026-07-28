/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail

/**
 * Result of [SouveraMailLoginFlow.fetchCombinedAppPassword]: one secret that Nextcloud (DAV) and
 * Stalwart (IMAP/SMTP/Sieve) both accept.
 */
data class CombinedAppPassword(val loginName: String, val appPassword: String, val stalwartId: String)

/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.calendar

/** The editable state of an event in [EventEditActivity]; [id] is null for a new event. */
data class EventDraft(
    val id: Long?,
    val title: String,
    val location: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean
)

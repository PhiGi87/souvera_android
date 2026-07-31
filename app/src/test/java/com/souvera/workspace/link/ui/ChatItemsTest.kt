/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.ui

import com.souvera.workspace.link.net.LinkChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatItemsTest {

    private val me = "me"

    private fun message(id: Long, ts: Long, actor: String = me) = LinkChatMessage(
        id = id,
        actorId = actor,
        actorDisplayName = actor,
        timestamp = ts,
        message = "msg $id"
    )

    private fun failed(localId: Long, ts: Long, token: String = "tok") =
        FailedChatMessage(localId = localId, token = token, text = "failed $localId", timestamp = ts)

    @Test
    fun failedMessageIsMergedAtCorrectPosition() {
        val messages = listOf(message(1, 100), message(2, 300))
        val failedList = listOf(failed(9, 200))

        val items = buildChatItems(messages, me, readUpTo = null, failed = failedList, token = "tok")
        val withoutSeparators = items.filterNot { it is ChatItem.Separator }

        assertEquals(3, withoutSeparators.size)
        assertTrue(withoutSeparators[0] is ChatItem.Message)
        assertTrue(withoutSeparators[1] is ChatItem.Failed)
        assertTrue(withoutSeparators[2] is ChatItem.Message)
        assertEquals(9L, (withoutSeparators[1] as ChatItem.Failed).localId)
    }

    @Test
    fun failedMessageBeforeFirstMessageAndAfterLast() {
        val messages = listOf(message(1, 200))
        val failedList = listOf(failed(1, 100), failed(2, 300))

        val withoutSeparators = buildChatItems(messages, me, readUpTo = null, failed = failedList, token = "tok")
            .filterNot { it is ChatItem.Separator }

        assertEquals(3, withoutSeparators.size)
        assertEquals(1L, (withoutSeparators[0] as ChatItem.Failed).localId)
        assertTrue(withoutSeparators[1] is ChatItem.Message)
        assertEquals(2L, (withoutSeparators[2] as ChatItem.Failed).localId)
    }

    @Test
    fun failedMessagesAreFilteredByToken() {
        val failedList = listOf(failed(1, 100, token = "other"), failed(2, 100, token = "tok"))

        val withoutSeparators = buildChatItems(emptyList(), me, readUpTo = null, failed = failedList, token = "tok")
            .filterNot { it is ChatItem.Separator }

        assertEquals(1, withoutSeparators.size)
        assertEquals(2L, (withoutSeparators[0] as ChatItem.Failed).localId)
    }

    @Test
    fun tiesKeepServerMessagesFirst() {
        val messages = listOf(message(1, 100))
        val failedList = listOf(failed(7, 100))

        val withoutSeparators = buildChatItems(messages, me, readUpTo = null, failed = failedList, token = "tok")
            .filterNot { it is ChatItem.Separator }

        assertTrue(withoutSeparators[0] is ChatItem.Message)
        assertTrue(withoutSeparators[1] is ChatItem.Failed)
    }

    @Test
    fun dateSeparatorIsEmittedBetweenEventsOfDifferentDays() {
        val today = System.currentTimeMillis() / 1000
        val yesterday = today - 86400
        val messages = listOf(message(1, today))
        val failedList = listOf(failed(7, yesterday))

        val items = buildChatItems(messages, me, readUpTo = null, failed = failedList, token = "tok")

        val separators = items.filterIsInstance<ChatItem.Separator>()
        assertEquals(2, separators.size)
        val withoutSeparators = items.filterNot { it is ChatItem.Separator }
        assertTrue(withoutSeparators[0] is ChatItem.Failed)
        assertTrue(withoutSeparators[1] is ChatItem.Message)
    }

    @Test
    fun readFlagIsSetForOwnMessagesUpToReadMarker() {
        val messages = listOf(message(1, 100), message(2, 200, actor = "other"))
        val withoutSeparators = buildChatItems(messages, me, readUpTo = 1L, failed = emptyList(), token = "tok")
            .filterNot { it is ChatItem.Separator }

        assertEquals(true, (withoutSeparators[0] as ChatItem.Message).read)
        assertEquals(false, (withoutSeparators[1] as ChatItem.Message).read)
    }
}

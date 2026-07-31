/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.net

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

class OcsModelsTest {

    private val gson = Gson()

    @Test
    fun parsesLastCommonReadMessage_fromRoomV4() {
        val json = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},
             "data":[{"token":"abc","displayName":"Room","type":1,"lastCommonReadMessage":42}]}}
        """.trimIndent()
        val type = object : TypeToken<OcsEnvelope<List<LinkConversation>>>() {}.type
        val result = gson.fromJson<OcsEnvelope<List<LinkConversation>>>(json, type)

        assertEquals(42L, result.ocs.data!!.first().lastCommonReadMessage)
    }

    @Test
    fun defaultsLastCommonReadMessageToZero_whenFieldMissing() {
        val json = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},
             "data":[{"token":"abc","displayName":"Room","type":1}]}}
        """.trimIndent()
        val type = object : TypeToken<OcsEnvelope<List<LinkConversation>>>() {}.type
        val result = gson.fromJson<OcsEnvelope<List<LinkConversation>>>(json, type)

        assertEquals(0L, result.ocs.data!!.first().lastCommonReadMessage)
    }

    @Test
    fun parsesMessageFields() {
        val json = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},
             "data":[{"id":7,"token":"abc","actorId":"alice","actorType":"users",
                      "timestamp":1700000000,"message":"Hallo","systemMessage":""}]}}
        """.trimIndent()
        val type = object : TypeToken<OcsEnvelope<List<LinkChatMessage>>>() {}.type
        val result = gson.fromJson<OcsEnvelope<List<LinkChatMessage>>>(json, type)
        val message = result.ocs.data!!.first()

        assertEquals(7L, message.id)
        assertEquals("alice", message.actorId)
        assertEquals("Hallo", message.message)
    }
}

/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusApiTest {

    @Test
    fun parsesOnlineStatusWithLastActivity() {
        val body = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},
             "data":{"userId":"alice","status":"online","lastActivity":1712345678}}}
        """.trimIndent()

        val result = StatusApi.parseStatusBody(body)

        assertEquals(UserStatusType.ONLINE, result?.status)
        assertEquals(1712345678L, result?.lastActivity)
    }

    @Test
    fun mapsUnknownOrOfflineStatusToInvisible() {
        val body = """
            {"ocs":{"meta":{"status":"ok","statuscode":200},
             "data":{"userId":"alice","status":"offline"}}}
        """.trimIndent()

        val result = StatusApi.parseStatusBody(body)

        assertEquals(UserStatusType.INVISIBLE, result?.status)
        assertEquals(0L, result?.lastActivity)
    }

    @Test
    fun returnsNullWhenDataAbsent() {
        val body = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":null}}"""

        assertNull(StatusApi.parseStatusBody(body))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(StatusApi.parseStatusBody("not-json"))
    }
}

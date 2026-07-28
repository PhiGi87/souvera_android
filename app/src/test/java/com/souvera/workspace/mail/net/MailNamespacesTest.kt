/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.mail.net

import com.souvera.workspace.mail.db.entity.MailboxEntity
import com.souvera.workspace.mail.db.entity.MailboxKind
import com.souvera.workspace.mail.db.entity.NamespaceType
import io.mockk.every
import io.mockk.mockk
import org.eclipse.angus.mail.imap.IMAPFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailNamespacesTest {

    // Namespace under test
    // NAMESPACE: (("" "/")) (("Shared Folders" "/")) NIL
    private val namespaces = MailNamespaces(
        personal = listOf(NamespacePrefix("", '/')),
        otherUsers = listOf(NamespacePrefix("Shared Folders", '/')),
        shared = emptyList()
    )

    @Test
    fun testScenario1_SharedFoldersInbox_IsOtherUsersWithOwner() {
        // Scenario 1: Path "Shared Folders/admins@host-on.de/INBOX" → must be OTHER_USERS → ownerIdentity must be "admins@host-on.de"
        val fullName = "Shared Folders/admins@host-on.de/INBOX"
        
        val type = namespaces.classify(fullName, "info@host-on.de")
        assertEquals(NamespaceType.OTHER_USERS, type)
        
        val folder = mockk<IMAPFolder>(relaxed = true) {
            every { this@mockk.fullName } returns fullName
            every { this@mockk.name } returns "INBOX"
            every { attributes } returns arrayOf("\Inbox")
        }
        
        val entity = ImapFolderMapper.toEntity(
            accountName = "test@host-on.de",
            folder = folder,
            namespaces = namespaces,
            userIdentity = "info@host-on.de",
            previous = null
        )
        
        assertEquals(NamespaceType.OTHER_USERS, entity.namespaceType)
        assertEquals("admins@host-on.de", entity.ownerIdentity)
        assertEquals(MailboxKind.INBOX, entity.kind)
    }

    @Test
    fun testScenario2_PersonalInbox_IsPersonal() {
        // Scenario 2: Path "info@host-on.de/INBOX" → must be PERSONAL
        val fullName = "info@host-on.de/INBOX"
        
        val type = namespaces.classify(fullName, "info@host-on.de")
        assertEquals(NamespaceType.PERSONAL, type)
        
        val folder = mockk<IMAPFolder>(relaxed = true) {
            every { this@mockk.fullName } returns fullName
            every { this@mockk.name } returns "INBOX"
        }
        
        val entity = ImapFolderMapper.toEntity(
            accountName = "test@host-on.de",
            folder = folder,
            namespaces = namespaces,
            userIdentity = "info@host-on.de",
            previous = null
        )
        
        assertEquals(NamespaceType.PERSONAL, entity.namespaceType)
        assertNull(entity.ownerIdentity)
    }

    @Test
    fun testScenario3_PersonalSubfolder_IsPersonal() {
        // Scenario 3: Path "INBOX/Administration" → must be PERSONAL
        val fullName = "INBOX/Administration"
        
        val type = namespaces.classify(fullName, "info@host-on.de")
        assertEquals(NamespaceType.PERSONAL, type)
        
        val folder = mockk<IMAPFolder>(relaxed = true) {
            every { this@mockk.fullName } returns fullName
            every { this@mockk.name } returns "Administration"
        }
        
        val entity = ImapFolderMapper.toEntity(
            accountName = "test@host-on.de",
            folder = folder,
            namespaces = namespaces,
            userIdentity = "info@host-on.de",
            previous = null
        )
        
        assertEquals(NamespaceType.PERSONAL, entity.namespaceType)
        assertNull(entity.ownerIdentity)
    }

    @Test
    fun testScenario4_InboxRoot_IsPersonal() {
        // Scenario 4: Path "INBOX" → must be PERSONAL
        val fullName = "INBOX"
        
        val type = namespaces.classify(fullName, "info@host-on.de")
        assertEquals(NamespaceType.PERSONAL, type)
        
        val folder = mockk<IMAPFolder>(relaxed = true) {
            every { this@mockk.fullName } returns fullName
            every { this@mockk.name } returns "INBOX"
        }
        
        val entity = ImapFolderMapper.toEntity(
            accountName = "test@host-on.de",
            folder = folder,
            namespaces = namespaces,
            userIdentity = "info@host-on.de",
            previous = null
        )
        
        assertEquals(NamespaceType.PERSONAL, entity.namespaceType)
        assertNull(entity.ownerIdentity)
        assertEquals(MailboxKind.INBOX, entity.kind)
    }

    @Test
    fun testScenario5_NoNamespace_SharedByPattern() {
        // Scenario 5: Server WITHOUT NAMESPACE (EMPTY): "other@domain/INBOX" → must be SHARED (Heuristic-Fallback)
        val fullName = "other@domain/INBOX"
        val namespacesEmpty = MailNamespaces.EMPTY
        
        val type = namespacesEmpty.classify(fullName, "info@host-on.de")
        assertEquals(NamespaceType.SHARED, type)
        
        val folder = mockk<IMAPFolder>(relaxed = true) {
            every { this@mockk.fullName } returns fullName
            every { this@mockk.name } returns "INBOX"
        }
        
        val entity = ImapFolderMapper.toEntity(
            accountName = "test@host-on.de",
            folder = folder,
            namespaces = namespacesEmpty,
            userIdentity = "info@host-on.de",
            previous = null
        )
        
        assertEquals(NamespaceType.SHARED, entity.namespaceType)
        assertEquals("other@domain", entity.ownerIdentity)
        assertEquals(MailboxKind.INBOX, entity.kind)
    }

    @Test
    fun testScenario6_NoNamespace_InboxSubfolder_IsPersonal() {
        // Scenario 6: Server WITHOUT NAMESPACE (EMPTY): "INBOX/Sub" → must be PERSONAL
        val fullName = "INBOX/Sub"
        val namespacesEmpty = MailNamespaces.EMPTY
        
        val type = namespacesEmpty.classify(fullName, "info@host-on.de")
        assertEquals(NamespaceType.PERSONAL, type)
        
        val folder = mockk<IMAPFolder>(relaxed = true) {
            every { this@mockk.fullName } returns fullName
            every { this@mockk.name } returns "Sub"
        }
        
        val entity = ImapFolderMapper.toEntity(
            accountName = "test@host-on.de",
            folder = folder,
            namespaces = namespacesEmpty,
            userIdentity = "info@host-on.de",
            previous = null
        )
        
        assertEquals(NamespaceType.PERSONAL, entity.namespaceType)
        assertNull(entity.ownerIdentity)
    }
}

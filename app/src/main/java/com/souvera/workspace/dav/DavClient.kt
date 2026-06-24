/*
 * Souvera Workspace - Android Client
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Lightweight WebDAV client used for CardDAV / CalDAV synchronisation.
 * Built on the commons-httpclient + jackrabbit-webdav stack that already ships
 * with the app, so no additional dependency is required.
 */
package com.souvera.workspace.dav

import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpStatus
import org.apache.commons.httpclient.UsernamePasswordCredentials
import org.apache.commons.httpclient.auth.AuthScope
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.jackrabbit.webdav.DavConstants
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod
import org.apache.jackrabbit.webdav.property.DavPropertyName
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet
import java.net.URL

/**
 * A single resource returned by a PROPFIND request.
 */
data class DavResource(
    val href: String,
    val displayName: String?,
    val contentType: String?,
    val isCollection: Boolean
)

class DavClient(
    private val baseUrl: String,
    username: String,
    password: String
) {
    private val origin: String = URL(baseUrl).let { url ->
        val port = if (url.port == -1) "" else ":${url.port}"
        "${url.protocol}://${url.host}$port"
    }

    private val client: HttpClient = HttpClient().apply {
        params.isAuthenticationPreemptive = true
        state.setCredentials(AuthScope.ANY, UsernamePasswordCredentials(username, password))
    }

    /**
     * Lists the children of the given collection URL (Depth: 1).
     * The collection itself is excluded from the result.
     */
    fun list(collectionUrl: String): List<DavResource> {
        val names = DavPropertyNameSet().apply {
            add(DavPropertyName.DISPLAYNAME)
            add(DavPropertyName.GETCONTENTTYPE)
            add(DavPropertyName.RESOURCETYPE)
            add(DavPropertyName.GETETAG)
        }

        val method = PropFindMethod(collectionUrl, names, DavConstants.DEPTH_1)
        return try {
            val status = client.executeMethod(method)
            if (status != HttpStatus.SC_MULTI_STATUS) {
                emptyList()
            } else {
                val responses = method.responseBodyAsMultiStatus.responses
                responses.mapNotNull { response ->
                    val href = response.href ?: return@mapNotNull null
                    val props = response.getProperties(HttpStatus.SC_OK)
                    val displayName = props.get(DavPropertyName.DISPLAYNAME)?.value?.toString()
                    val contentType = props.get(DavPropertyName.GETCONTENTTYPE)?.value?.toString()
                    val isCollection = href.endsWith("/")
                    DavResource(href, displayName, contentType, isCollection)
                }.filter { !isSamePath(it.href, collectionUrl) }
            }
        } finally {
            method.releaseConnection()
        }
    }

    /**
     * Downloads a single resource (e.g. an .ics or .vcf object) as text.
     */
    fun get(href: String): String? {
        val method = GetMethod(toAbsolute(href))
        return try {
            val status = client.executeMethod(method)
            if (status == HttpStatus.SC_OK) method.responseBodyAsString else null
        } finally {
            method.releaseConnection()
        }
    }

    fun toAbsolute(href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://")) href else origin + href

    private fun isSamePath(href: String, collectionUrl: String): Boolean {
        val a = href.trimEnd('/')
        val b = (if (collectionUrl.startsWith("http")) URL(collectionUrl).path else collectionUrl).trimEnd('/')
        return a == b
    }
}

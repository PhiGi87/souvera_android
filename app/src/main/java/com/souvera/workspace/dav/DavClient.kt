/*
 * Souvera Workspace - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Lightweight WebDAV/CalDAV/CardDAV client built on the commons-httpclient +
 * jackrabbit-webdav stack that already ships with the app. Supports HTTP Basic
 * (app-password) and Bearer (OAuth2) authentication with automatic detection.
 */
package com.souvera.workspace.dav

import android.util.Base64
import android.util.Log
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.HttpStatus
import org.apache.commons.httpclient.methods.DeleteMethod
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.PutMethod
import org.apache.commons.httpclient.methods.StringRequestEntity
import org.apache.jackrabbit.webdav.DavConstants
import org.apache.jackrabbit.webdav.client.methods.MkColMethod
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod
import org.apache.jackrabbit.webdav.property.DavPropertyName
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet
import org.apache.jackrabbit.webdav.xml.Namespace
import java.net.URL

data class DavResource(
    val href: String,
    val displayName: String?,
    val contentType: String?,
    val etag: String?,
    val isCollection: Boolean
)

class DavClient(private val baseUrl: String, private val username: String, private val secret: String) {
    private enum class AuthMode { UNKNOWN, BASIC, BEARER }

    private val origin: String = URL(baseUrl).let { url ->
        val port = if (url.port == -1) "" else ":${url.port}"
        "${url.protocol}://${url.host}$port"
    }
    private val davRoot = "$baseUrl/remote.php/dav/"
    private val client = HttpClient()
    private var authMode = AuthMode.UNKNOWN

    private fun applyAuth(method: HttpMethod, mode: AuthMode = authMode) {
        when (mode) {
            AuthMode.BEARER -> method.setRequestHeader("Authorization", "Bearer $secret")

            else -> {
                val token = Base64.encodeToString(
                    "$username:$secret".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                method.setRequestHeader("Authorization", "Basic $token")
            }
        }
    }

    private fun ensureAuth() {
        if (authMode != AuthMode.UNKNOWN) return
        authMode = if (probe(AuthMode.BASIC)) {
            AuthMode.BASIC
        } else if (probe(AuthMode.BEARER)) {
            AuthMode.BEARER
        } else {
            AuthMode.BASIC // sensible default
        }
        Log.d(TAG, "Authentication mode resolved to $authMode")
    }

    private fun probe(mode: AuthMode): Boolean {
        val names = DavPropertyNameSet().apply { add(DavPropertyName.DISPLAYNAME) }
        val method = PropFindMethod(davRoot, names, DavConstants.DEPTH_0)
        applyAuth(method, mode)
        return try {
            val status = client.executeMethod(method)
            status == HttpStatus.SC_MULTI_STATUS || status == HttpStatus.SC_OK
        } catch (e: Exception) {
            Log.w(TAG, "Auth probe ($mode) failed", e)
            false
        } finally {
            method.releaseConnection()
        }
    }

    fun list(collectionUrl: String): List<DavResource> {
        ensureAuth()
        val names = DavPropertyNameSet().apply {
            add(DavPropertyName.DISPLAYNAME)
            add(DavPropertyName.GETCONTENTTYPE)
            add(DavPropertyName.RESOURCETYPE)
            add(DavPropertyName.GETETAG)
        }
        val method = PropFindMethod(collectionUrl, names, DavConstants.DEPTH_1)
        applyAuth(method)
        return try {
            if (client.executeMethod(method) != HttpStatus.SC_MULTI_STATUS) {
                emptyList()
            } else {
                method.responseBodyAsMultiStatus.responses.mapNotNull { response ->
                    val href = response.href ?: return@mapNotNull null
                    val props = response.getProperties(HttpStatus.SC_OK)
                    DavResource(
                        href = href,
                        displayName = props.get(DavPropertyName.DISPLAYNAME)?.value?.toString(),
                        contentType = props.get(DavPropertyName.GETCONTENTTYPE)?.value?.toString(),
                        etag = props.get(DavPropertyName.GETETAG)?.value?.toString(),
                        isCollection = href.endsWith("/")
                    )
                }.filter { !isSamePath(it.href, collectionUrl) }
            }
        } finally {
            method.releaseConnection()
        }
    }

    /** Returns the CalDAV/CardDAV collection sync token (CTag), if available. */
    fun cTag(collectionUrl: String): String? {
        ensureAuth()
        val ctagName = DavPropertyName.create("getctag", Namespace.getNamespace("http://calendarserver.org/ns/"))
        val names = DavPropertyNameSet().apply { add(ctagName) }
        val method = PropFindMethod(collectionUrl, names, DavConstants.DEPTH_0)
        applyAuth(method)
        return try {
            if (client.executeMethod(method) != HttpStatus.SC_MULTI_STATUS) {
                null
            } else {
                method.responseBodyAsMultiStatus.responses.firstOrNull()
                    ?.getProperties(HttpStatus.SC_OK)?.get(ctagName)?.value?.toString()
            }
        } finally {
            method.releaseConnection()
        }
    }

    fun get(href: String): String? {
        ensureAuth()
        val method = GetMethod(toAbsolute(href))
        applyAuth(method)
        return try {
            if (client.executeMethod(method) == HttpStatus.SC_OK) method.responseBodyAsString else null
        } finally {
            method.releaseConnection()
        }
    }

    /** Uploads an object. Returns the new ETag (may be null) on success, or null on failure. */
    fun put(
        href: String,
        body: String,
        contentType: String,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false
    ): PutResult {
        ensureAuth()
        val method = PutMethod(toAbsolute(href))
        applyAuth(method)
        if (ifNoneMatch) method.setRequestHeader("If-None-Match", "*")
        if (ifMatch != null) method.setRequestHeader("If-Match", ifMatch)
        method.requestEntity = StringRequestEntity(body, contentType, "UTF-8")
        return try {
            val status = client.executeMethod(method)
            val ok = status == HttpStatus.SC_CREATED ||
                status == HttpStatus.SC_NO_CONTENT ||
                status == HttpStatus.SC_OK
            PutResult(ok, method.getResponseHeader("ETag")?.value)
        } catch (e: Exception) {
            Log.w(TAG, "PUT failed for $href", e)
            PutResult(false, null)
        } finally {
            method.releaseConnection()
        }
    }

    /** Creates a collection (folder). Treats "already exists" (405) as success. */
    fun mkcol(collectionUrl: String): Boolean {
        ensureAuth()
        val method = MkColMethod(toAbsolute(collectionUrl))
        applyAuth(method)
        return try {
            val status = client.executeMethod(method)
            status == HttpStatus.SC_CREATED ||
                status == HttpStatus.SC_OK ||
                status == HttpStatus.SC_METHOD_NOT_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "MKCOL failed for $collectionUrl", e)
            false
        } finally {
            method.releaseConnection()
        }
    }

    fun delete(href: String, ifMatch: String? = null): Boolean {
        ensureAuth()
        val method = DeleteMethod(toAbsolute(href))
        applyAuth(method)
        if (ifMatch != null) method.setRequestHeader("If-Match", ifMatch)
        return try {
            val status = client.executeMethod(method)
            status == HttpStatus.SC_NO_CONTENT || status == HttpStatus.SC_OK ||
                status == HttpStatus.SC_NOT_FOUND
        } catch (e: Exception) {
            Log.w(TAG, "DELETE failed for $href", e)
            false
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

    data class PutResult(val success: Boolean, val etag: String?)

    companion object {
        const val TAG = "DavClient"
    }
}

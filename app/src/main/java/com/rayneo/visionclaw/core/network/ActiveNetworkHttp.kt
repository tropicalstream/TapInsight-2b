package com.rayneo.visionclaw.core.network

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.TimeUnit

internal object ActiveNetworkHttp {
    private const val TAG = "ActiveNetworkHttp"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    private const val DEFAULT_READ_TIMEOUT_MS = 20_000

    data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Headers
    )

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    private val baseClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .dns(Dns.SYSTEM)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): HttpResponse {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return execute(builder.get().build(), connectTimeoutMs, readTimeoutMs, readTimeoutMs)
    }

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): HttpResponse {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
        headers.forEach { (key, value) -> builder.header(key, value) }
        return execute(builder.build(), connectTimeoutMs, readTimeoutMs, readTimeoutMs)
    }

    fun postForm(
        url: String,
        encodedFormBody: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): HttpResponse {
        val builder = Request.Builder()
            .url(url)
            .post(encodedFormBody.toRequestBody(formMediaType))
        headers.forEach { (key, value) -> builder.header(key, value) }
        return execute(builder.build(), connectTimeoutMs, readTimeoutMs, readTimeoutMs)
    }

    fun execute(
        request: Request,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        writeTimeoutMs: Int = readTimeoutMs,
        callTimeoutMs: Int = (connectTimeoutMs + readTimeoutMs).coerceAtLeast(DEFAULT_CONNECT_TIMEOUT_MS)
    ): HttpResponse {
        val client = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "HTTP ${request.method} ${request.url} -> ${response.code}")
                return HttpResponse(
                    code = response.code,
                    body = body,
                    headers = response.headers
                )
            }
        } catch (e: UnknownHostException) {
            throw UnknownHostException(
                "Unable to resolve host \"${request.url.host}\". Check whether the glasses have working internet."
            )
        } catch (e: SocketTimeoutException) {
            throw SocketTimeoutException(
                "Timed out reaching ${request.url.host} over HTTPS. This network may be blocking outbound internet."
            )
        } catch (e: ConnectException) {
            throw ConnectException(
                "Failed to connect to ${request.url.host} over HTTPS. This network may be blocking outbound internet."
            )
        }
    }

    /**
     * Follow redirects for [url] and return the FINAL landing URL. Used to
     * turn opaque redirectors — notably Google grounding redirects
     * (vertexaisearch.cloud.google.com/grounding-api-redirect/…) — into the
     * real publisher URL so a list entry opens the page it claims to.
     * Tries HEAD first (no body); falls back to GET when a host rejects HEAD.
     * Returns null on failure so callers can fall back to the original URL.
     */
    fun resolveFinalUrl(
        url: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): String? {
        val client = baseClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((connectTimeoutMs + readTimeoutMs).toLong(), TimeUnit.MILLISECONDS)
            .build()
        return try {
            val headReq = Request.Builder().url(url).head()
                .header("User-Agent", REDIRECT_RESOLVE_UA).build()
            client.newCall(headReq).execute().use { resp ->
                // resp.request.url is the URL of the LAST hop after redirects.
                val finalUrl = resp.request.url.toString()
                if (resp.code in 200..399 && finalUrl.isNotBlank()) return finalUrl
            }
            val getReq = Request.Builder().url(url).get()
                .header("User-Agent", REDIRECT_RESOLVE_UA).build()
            client.newCall(getReq).execute().use { resp ->
                resp.request.url.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveFinalUrl failed for $url: ${e.message}")
            null
        }
    }

    private const val REDIRECT_RESOLVE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun openConnection(context: android.content.Context?, urlStr: String): HttpURLConnection {
        if (context != null) {
            Log.d(TAG, "Legacy openConnection used for $urlStr")
        }
        return (URL(urlStr).openConnection() as HttpURLConnection)
    }
}

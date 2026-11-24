package com.androx.app.platform

import android.annotation.SuppressLint
import com.androx.app.core.net.HttpOptions
import com.androx.app.core.net.HttpPort
import com.androx.app.core.net.HttpRequestSpec
import com.androx.app.core.net.HttpResponse
import com.androx.app.core.net.NetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * The only place Androx opens a network connection for requests. It does what the gate decided: the response is cut at
 * the size cap, plain http and a self-signed certificate are accepted only when [HttpOptions] says this host is a local
 * device the person listed, and every failure becomes a [NetException] in plain words.
 */
@SuppressLint("TrustAllX509TrustManager", "BadHostnameVerifier") // only ever used for a local device the person added by hand
class OkHttpPort : HttpPort {
    private val base = OkHttpClient.Builder().build()

    /** Trusts any certificate. Used for one request at a time, and only for a host the person added under local devices. */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    override suspend fun execute(request: HttpRequestSpec, options: HttpOptions): HttpResponse = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val builder = base.newBuilder()
            .connectTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .followRedirects(request.followRedirects)
            .followSslRedirects(request.followRedirects && !request.url.startsWith("http://", ignoreCase = true))
        if (options.trustSelfSigned) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            builder.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
        }
        val http = builder.build()

        val okRequest = try {
            val method = request.method.uppercase()
            val contentType = request.headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
            val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
            val body = when {
                request.body != null && method != "GET" && method != "HEAD" -> request.body.toRequestBody(contentType?.toMediaTypeOrNull())
                needsBody -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            Request.Builder().url(request.url).method(method, body).apply {
                header("User-Agent", "Androx/0.0.2")
                for ((n, v) in request.headers) header(n, v)
            }.build()
        } catch (e: IllegalArgumentException) {
            throw NetException("That isn't a valid web address.")
        }

        try {
            http.newCall(okRequest).execute().use { response ->
                val out = ByteArrayOutputStream()
                var truncated = false
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > options.maxBytes) {
                            out.write(buffer, 0, options.maxBytes - out.size())
                            truncated = true
                            break
                        }
                        out.write(buffer, 0, n)
                    }
                }
                val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                HttpResponse(
                    status = response.code,
                    headers = response.headers.map { it.first to it.second },
                    body = String(out.toByteArray(), charset),
                    truncated = truncated,
                    millis = System.currentTimeMillis() - started,
                )
            }
        } catch (e: SocketTimeoutException) {
            throw NetException("The server took too long to answer.")
        } catch (e: UnknownHostException) {
            throw NetException("Couldn't find that server. Check the address and your connection.")
        } catch (e: ConnectException) {
            throw NetException("Couldn't connect to the server.")
        } catch (e: SSLException) {
            throw NetException("The secure connection failed (${e.message?.take(80) ?: "certificate problem"}).")
        } catch (e: IOException) {
            throw NetException("The connection failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

package com.waltermart.mobileshelftag

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class ApiException(message: String) : IOException(message)

data class ApiResult(val code: Int, val body: String)

/** Talks to the WM App PC. Every failure becomes an ApiException with a message a store user can act on. */
class ApiClient(private val prefs: Prefs) {

    private fun url(path: String) = URL("http://${prefs.host}:${prefs.port}$path")

    private fun open(path: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val c = url(path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("X-Api-Token", prefs.token)
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: ApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ApiException("PC address '${prefs.host}' was not found. Check the address in Settings.")
        } catch (e: ConnectException) {
            throw ApiException("Cannot connect to the PC. Is WM App open, and is the port allowed through the firewall?")
        } catch (e: NoRouteToHostException) {
            throw ApiException("No route to the PC. Check the Wi-Fi connection.")
        } catch (e: SocketTimeoutException) {
            throw ApiException("The PC did not answer in time.")
        } catch (e: IOException) {
            throw ApiException("Network problem: ${e.message ?: "connection lost"}")
        }
    }

    fun get(path: String, readTimeoutMs: Int = 15000): ApiResult = guard {
        val c = open(path, "GET", readTimeoutMs)
        try {
            val code = c.responseCode
            ApiResult(code, readBody(c, code))
        } finally {
            c.disconnect()
        }
    }

    fun postJson(path: String, json: String, readTimeoutMs: Int = 20000): ApiResult = guard {
        val c = open(path, "POST", readTimeoutMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            ApiResult(code, readBody(c, code))
        } finally {
            c.disconnect()
        }
    }

    /** Streams the response body to [sink]; returns the HTTP code. Used for the masterfile download. */
    fun download(path: String, sink: (InputStream) -> Unit): Int = guard {
        val c = open(path, "GET", 60000)
        try {
            val code = c.responseCode
            if (code != 200) throw ApiException(describe(code, readBody(c, code)))
            c.inputStream.use(sink)
            code
        } finally {
            c.disconnect()
        }
    }

    private fun readBody(c: HttpURLConnection, code: Int): String {
        val s = if (code >= 400) c.errorStream else c.inputStream
        return s?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    companion object {
        fun describe(code: Int, body: String): String = when (code) {
            401 -> "The PC rejected the access token. Check the token in Settings."
            404 -> "The PC has no masterfile (DC_UPDATE.txt) or the item was not found."
            503 -> "The PC is not accepting print jobs right now (problem with its print history, or it cannot record jobs). " +
                "Nothing was printed. Tell whoever looks after WM App. Lookups and sync still work."
            else -> "The PC answered with error $code. $body"
        }
    }
}

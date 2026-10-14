package com.doxigo.tv30

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import androidx.annotation.StringRes
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A key sent from a phone, for ask's sheet: while it is open, a one-page form on the TV's own
 * address in the home network, at a path nobody can guess ([url], the sheet's QR code). The page
 * links to [link], where the key is found, and takes it back pasted; [onKey] gets it on the main
 * thread, once it is a key ([KeyRequest.read]). Plain HTTP, so only on the TV's Wi-Fi or Ethernet
 * address and only until [close]; the key is never logged.
 */
class KeyForm private constructor(
    activity: Activity, private val server: ServerSocket, service: CharSequence, link: String, private val onKey: (String) -> Unit,
) {
    private val token = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    val url = "http://${server.inetAddress.hostAddress}:${server.localPort}/$token"
    private val main = Handler(Looper.getMainLooper())
    // The pages' words now, in the app's language, so the server's threads never reach the activity.
    private val form = page(activity, service, link)
    private val again = page(activity, service, link, bad = true)
    private val sent = page(activity, service, link, sent = true)

    init {
        thread(isDaemon = true, name = "key-form") {
            while (true) {
                val client = try { server.accept() } catch (e: IOException) { break } // closed
                thread(isDaemon = true) { runCatching { client.use(::answer) } }
            }
        }
    }

    fun close() = runCatching { server.close() }

    private fun answer(client: Socket) {
        client.soTimeout = 10_000
        val request = read(client.getInputStream()) ?: return
        val (status, body) = when (request) {
            KeyRequest.Page -> 200 to form
            is KeyRequest.Key -> 200 to sent.also { main.post { onKey(request.key) } }
            is KeyRequest.Refused -> request.status to if (request.status == 400) again else ""
        }
        val bytes = body.toByteArray()
        client.getOutputStream().apply {
            write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Refused"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes)
            flush()
        }
    }

    /** The request once whole (or refused), read as it comes; null if the phone hangs up first. */
    private fun read(input: InputStream): KeyRequest? {
        val raw = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return null
            raw.write(chunk, 0, n)
            KeyRequest.read(raw.toString("ISO-8859-1"), token)?.let { return it }
        }
    }

    companion object {
        /** No 0/o or 1/l to mistake when the address is typed from the screen: 50 bits in ten letters. */
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        /** A form on the TV's address in the home network, or null when it has none (Wi-Fi off, no cable). */
        fun open(activity: Activity, service: CharSequence, link: String, onKey: (String) -> Unit): KeyForm? {
            val address = runCatching {
                // A VPN's tun is point to point, and its address no phone at home can reach; Wi-Fi and
                // Ethernet first, ahead of anything else up (the emulator's radio).
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                    .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            }.getOrNull() ?: return null
            val server = runCatching { ServerSocket(0, 8, address) }.getOrNull() ?: return null
            return KeyForm(activity, server, service, link, onKey)
        }

        /**
         * The form, or once a key is in, the word that it went: dark as the app, one column a thumb
         * wide, the field and button large, nothing fetched from anywhere. Persian reads right to left.
         */
        private fun page(activity: Activity, service: CharSequence, link: String, bad: Boolean = false, sent: Boolean = false): String {
            val res = activity.resources
            val rtl = res.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            fun say(@StringRes id: Int) = TextUtils.htmlEncode(res.getString(id))
            val title = TextUtils.htmlEncode(res.getString(R.string.key_page_title, service))
            val shown = TextUtils.htmlEncode(link.removePrefix("https://").removePrefix("www."))
            val body = if (sent) "<h1>${say(R.string.key_page_sent)}</h1><p>${say(R.string.key_page_sent_note)}</p>" else """
                <h1>${title}</h1>
                <ol>
                <li>${say(R.string.key_page_get)} <a href="${TextUtils.htmlEncode(link)}" rel="noreferrer" target="_blank" dir="ltr">$shown</a></li>
                <li>${say(R.string.key_page_paste)}</li>
                </ol>
                <form method="post">
                <input name="key" dir="ltr" required autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" aria-label="$title">
                ${if (bad) "<p class=\"bad\">${say(R.string.key_page_bad)}</p>" else ""}
                <button>${say(R.string.key_page_send)}</button>
                </form>
            """.trimIndent()
            return """
                <!doctype html><html lang="${if (rtl) "fa" else "en"}" dir="${if (rtl) "rtl" else "ltr"}"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
                <style>
                body{margin:0;background:#141414;color:#e5e5e5;font:18px/1.6 system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}
                main{max-width:28rem;margin:0 auto;padding:40px 24px}
                h1{color:#fff;font-size:26px;line-height:1.3;margin:0 0 20px}
                ol{padding-inline-start:1.4em;margin:0 0 28px}li{margin:0 0 8px}:lang(fa) ol{list-style-type:persian}
                a{color:#fff;text-underline-offset:3px;overflow-wrap:anywhere}
                input,button{box-sizing:border-box;width:100%;height:56px;border-radius:28px;font:inherit;border:3px solid transparent}
                input{background:#2e2e2e;color:#fff;padding:0 20px;text-align:left;outline:none}input:focus{border-color:#fff}
                button{margin-top:16px;background:#fff;color:#141414;font-weight:600}button:active{background:#e5e5e5}
                .bad{color:#fff;margin:12px 4px 0}
                </style></head><body><main>$body</main></body></html>
            """.trimIndent()
        }
    }
}

/** One request to the key form, read as far as it has come. */
sealed class KeyRequest {
    object Page : KeyRequest()
    data class Key(val key: String) : KeyRequest()
    /** Turned away with this HTTP status: 400 not a key, 404 not the form, 405, 411, 413 too long. */
    data class Refused(val status: Int) : KeyRequest()

    companion object {
        /** Larger than any key's request by far; anything past it is not a phone sending one. */
        const val MAX = 8 * 1024

        /**
         * [raw] is the request so far, a char a byte (ISO-8859-1): null while it is still coming.
         * Only "/[token]" is served, GET for the page and POST for its form; the key is the form's
         * "key", trimmed, and must be printable ASCII without spaces, as SettingsActivity.save keeps.
         */
        fun read(raw: String, token: String): KeyRequest? {
            if (raw.length > MAX) return Refused(413)
            val end = raw.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: return null
            val lines = raw.substring(0, end).split("\r\n")
            val start = lines[0].split(' ')
            if (start.getOrNull(1)?.substringBefore('?') != "/$token") return Refused(404)
            when (start[0]) {
                "GET" -> return Page
                "POST" -> Unit
                else -> return Refused(405)
            }
            val length = lines.drop(1).firstOrNull { it.substringBefore(':').trim().equals("Content-Length", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return Refused(411)
            val body = raw.substring(end + 4)
            if (body.length < length) return null // more to come, or past MAX on the next read
            val field = body.take(length).split('&').firstOrNull { it.startsWith("key=") }?.removePrefix("key=") ?: return Refused(400)
            val key = runCatching { URLDecoder.decode(field, "UTF-8") }.getOrNull()?.trim() ?: return Refused(400)
            return if (key.isNotEmpty() && key.all { it in '!'..'~' }) Key(key) else Refused(400)
        }
    }
}

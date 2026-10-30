// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The URL fence between a dictionary page and the app's network stack.
//
// A dictionary page is third-party markup running in a WebView with JavaScript enabled, and the
// renderer shell exposes `WordAudioBridge` to it. So `playAudio` / `playCandidates` / `prefetchAudio`
// receive attacker-controlled strings, and whatever they accept the app fetches natively
// ([AudioCache.getOrDownload]) — the page must not be able to turn the app into a proxy for
// loopback, link-local, private-range or cloud-metadata requests (SSRF), nor smuggle a blocked host
// past the check via userinfo or an alternative IP spelling.
//
// So: an explicit scheme + authority, a strict WHATWG parse (OkHttp), no userinfo, and a host that
// is neither an internal name nor an IPv4/IPv6 literal outside the global unicast space — including
// the non-canonical IPv4 spellings (`127.1`, `2130706433`, `0x7f.0.0.1`) OkHttp leaves untouched.
package mihon.core.dictionary.audio

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Hard ceiling for one word-audio response body (4 MB). A pronunciation clip is tens of KB, so
 * anything near this is a mistake or an attempt to make the app inflate an unbounded body into RAM
 * before [AudioCache]'s own 24 MB trim ever runs.
 */
internal const val MAX_WORD_AUDIO_BYTES = 4L * 1024 * 1024

internal fun isAllowedWordAudioUrl(url: String): Boolean {
    // The resolver's own TTS pseudo-URL (`tts://speak?text=…`) never leaves the device.
    if (url.startsWith("tts:", ignoreCase = true)) return true

    // Require an explicit authority: `https:example.com` and `//example.com` are not shapes the
    // renderer legitimately produces, and OkHttp would resolve the former to a plain abs URL.
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        return false
    }
    val parsed = url.toHttpUrlOrNull() ?: return false

    // `user@host` / `user:pass@host`: the string a human reads and the host actually fetched differ.
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false

    val host = parsed.host.removeSurrounding("[", "]").removeSuffix(".")
    if (host.isEmpty()) return false

    if (host in BLOCKED_HOST_NAMES || BLOCKED_HOST_SUFFIXES.any { host.endsWith(it) }) return false

    val ipv4 = parseIpv4Literal(host)
    if (ipv4 != null) return ipv4

    val ipv6 = parseIpv6Literal(host)
    if (ipv6 != null) return isAllowedAudioIpv6(ipv6)

    // A DNS name can never contain ':', so anything left that does is an IPv6 literal this parser
    // did not understand — refuse it instead of letting it through as a name.
    if (host.contains(':')) return false

    // A DNS name: *this* check cannot tell what it points at, because only a resolver can — so the
    // fetch itself re-checks every address the name resolves to (`globalOnlyDns`, installed on
    // `AudioCache.audioHttpClient`). The name is accepted here, the answer is not: a name that
    // resolves to loopback, LAN, link-local or a metadata endpoint is refused at fetch time, and so
    // is a rebinding answer that mixes public and internal addresses.
    return true
}

/**
 * Resolves names for the word-audio fetch and refuses any answer that is not entirely global
 * unicast, so a DNS name (which [isAllowedWordAudioUrl] can only check textually) cannot reach
 * loopback, a LAN host, link-local metadata (`169.254.169.254`), a unique-local IPv6 range or an
 * IPv4-mapped internal address — including the classic rebinding answer that is public on the
 * lookup the policy would have done and internal by the time the connection is made: the fence
 * runs on the same lookup OkHttp connects with, and a mixed answer is refused as a whole.
 */
internal fun globalOnlyDns(upstream: Dns = Dns.SYSTEM): Dns = GlobalOnlyDns(upstream)

private class GlobalOnlyDns(private val upstream: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = upstream.lookup(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("$hostname: no addresses")
        val cleanHost = hostname.removeSurrounding("[", "]").removeSuffix(".").lowercase()
        val isExplicitLoopbackHost = cleanHost == "localhost" || cleanHost.endsWith(".localhost") ||
            cleanHost == "127.0.0.1" || cleanHost == "::1"

        if (addresses.any { addr ->
                if (addr.isLoopbackAddress) {
                    !isExplicitLoopbackHost
                } else {
                    !isGlobalAddress(addr)
                }
            }
        ) {
            throw UnknownHostException("$hostname: refused, resolves outside the allowed audio space")
        }
        return addresses
    }
}

/**
 * Whether [address] is in the global unicast space — the [InetAddress] form of [isGlobalIpv4] /
 * [isGlobalIpv6], which the textual URL checks use. IPv4-mapped IPv6 addresses keep their IPv4
 * classification, so `::ffff:127.0.0.1` is refused like `127.0.0.1`.
 */
internal fun isGlobalAddress(address: InetAddress): Boolean {
    val bytes = address.address
    return when (bytes.size) {
        4 -> isGlobalIpv4(
            ((bytes[0].toInt() and 0xff) shl 24) or
                ((bytes[1].toInt() and 0xff) shl 16) or
                ((bytes[2].toInt() and 0xff) shl 8) or
                (bytes[3].toInt() and 0xff),
        )
        16 -> isGlobalIpv6(
            IntArray(8) { group ->
                ((bytes[group * 2].toInt() and 0xff) shl 8) or (bytes[group * 2 + 1].toInt() and 0xff)
            },
        )
        else -> false
    }
}

private val BLOCKED_HOST_NAMES = emptySet<String>()

private val BLOCKED_HOST_SUFFIXES = listOf(".local", ".internal", ".home.arpa")

/**
 * Interprets [host] as an IPv4 address in any of the spellings a URL parser may accept — dotted
 * quad, short form (`127.1`), single integer (`2130706433`) or hex (`0x7f.0.0.1`) — and reports
 * whether it is an allowed word audio address (global unicast or loopback for local audio servers).
 * Returns null when [host] is not numeric at all (i.e. a DNS name).
 *
 * OkHttp does not expand the non-dotted-quad spellings, so they are refused outright rather than
 * guessed at: no audio CDN uses them and their meaning depends on whichever stack resolves them.
 */
private fun parseIpv4Literal(host: String): Boolean? {
    val labels = host.removeSuffix(".").split('.')
    if (labels.size > 4) return null
    val octets = ArrayList<Long>(4)
    for (label in labels) {
        octets += parseIpv4Number(label) ?: return null
    }
    if (labels.size != 4) return false
    if (octets.any { it !in 0..255 }) return false
    val packed = (octets[0].toInt() shl 24) or (octets[1].toInt() shl 16) or
        (octets[2].toInt() shl 8) or octets[3].toInt()
    return isAllowedAudioIpv4(packed)
}

/** One IPv4 label: decimal, `0x`-hex or leading-zero octal, per the WHATWG URL spec. */
private fun parseIpv4Number(label: String): Long? {
    if (label.isEmpty()) return null
    val (radix, digits) = when {
        label.length >= 2 && label.startsWith("0x", ignoreCase = true) -> 16 to label.substring(2)
        label.length >= 2 && label.startsWith("0") -> 8 to label.substring(1)
        else -> 10 to label
    }
    if (digits.isEmpty()) return 0L
    var value = 0L
    for (char in digits) {
        val digit = char.digitToIntOrNull(radix) ?: return null
        value = value * radix + digit
        if (value > 0xffffffffL) return null
    }
    return value
}

/** Parses an embedded canonical dotted-quad (IPv4-in-IPv6 tails) into a packed `Int`, or null. */
private fun parseEmbeddedIpv4(host: String): Int? {
    val labels = host.split('.')
    if (labels.size != 4) return null
    var packed = 0
    for (label in labels) {
        val value = parseIpv4Number(label) ?: return null
        if (value !in 0..255) return null
        packed = (packed shl 8) or value.toInt()
    }
    return packed
}

private fun isAllowedAudioIpv4(packed: Int): Boolean {
    val a = (packed ushr 24) and 0xff
    val b = (packed ushr 16) and 0xff
    return when {
        a == 0 -> false // 0.0.0.0/8 unspecified / "this network"
        a == 10 -> false // RFC 1918
        a == 127 -> true // loopback is permitted for local audio servers (e.g. AnkiConnect Android)
        a == 169 && b == 254 -> false // link-local (169.254.169.254 metadata)
        a == 172 && b in 16..31 -> false // RFC 1918
        a == 192 && b == 168 -> false // RFC 1918
        a == 100 && b in 64..127 -> false // CGNAT 100.64/10
        a == 198 && (b == 18 || b == 19) -> false // benchmarking 198.18/15
        a >= 224 -> false // multicast, reserved, broadcast
        else -> true
    }
}

private fun isGlobalIpv4(packed: Int): Boolean {
    val a = (packed ushr 24) and 0xff
    val b = (packed ushr 16) and 0xff
    return when {
        a == 0 -> false // 0.0.0.0/8 unspecified / "this network"
        a == 10 -> false // RFC 1918
        a == 127 -> false // loopback
        a == 169 && b == 254 -> false // link-local (169.254.169.254 metadata)
        a == 172 && b in 16..31 -> false // RFC 1918
        a == 192 && b == 168 -> false // RFC 1918
        a == 100 && b in 64..127 -> false // CGNAT 100.64/10
        a == 198 && (b == 18 || b == 19) -> false // benchmarking 198.18/15
        a >= 224 -> false // multicast, reserved, broadcast
        else -> true
    }
}

/** Parses a canonical IPv6 literal (no brackets) into eight 16-bit groups, or null. */
private fun parseIpv6Literal(host: String): IntArray? {
    if (!host.contains(':')) return null
    val literal = host.substringBefore('%')

    val compression = literal.indexOf("::")
    if (compression >= 0 && literal.indexOf("::", compression + 1) >= 0) return null

    if (compression < 0) {
        val groups = parseIpv6Groups(literal) ?: return null
        return if (groups.size == 8) groups.toIntArray() else null
    }
    val head = parseIpv6Groups(literal.substring(0, compression)) ?: return null
    val tail = parseIpv6Groups(literal.substring(compression + 2)) ?: return null
    if (head.size + tail.size > 7) return null
    val groups = head + List(8 - head.size - tail.size) { 0 } + tail
    return groups.toIntArray()
}

private fun parseIpv6Groups(part: String): List<Int>? {
    if (part.isEmpty()) return emptyList()
    val groups = ArrayList<Int>(8)
    for (group in part.split(':')) {
        val embeddedIpv4 = parseEmbeddedIpv4(group)
        if (embeddedIpv4 != null) {
            groups.add((embeddedIpv4 ushr 16) and 0xffff)
            groups.add(embeddedIpv4 and 0xffff)
            continue
        }
        if (group.isEmpty() || group.length > 4) return null
        val value = group.toIntOrNull(16) ?: return null
        groups.add(value)
    }
    return groups
}

private fun isAllowedAudioIpv6(groups: IntArray): Boolean {
    val first = groups[0]
    return when {
        groups.all { it == 0 } -> false // ::
        groups.take(6).all { it == 0 } && groups[6] == 0 && groups[7] == 1 -> true // ::1 loopback
        (first and 0xfe00) == 0xfc00 -> false // fc00::/7 unique-local
        (first and 0xffc0) == 0xfe80 -> false // fe80::/10 link-local
        (first and 0xffc0) == 0xfec0 -> false // fec0::/10 site-local (deprecated)
        (first and 0xff00) == 0xff00 -> false // ff00::/8 multicast
        first == 0x0064 && groups[1] == 0xff9b -> false // 64:ff9b::/96 NAT64
        // ::ffff:a.b.c.d (IPv4-mapped) and ::a.b.c.d (IPv4-compatible) keep an IPv4 address low.
        groups.take(5).all { it == 0 } && (groups[5] == 0xffff || groups[5] == 0) ->
            isAllowedAudioIpv4((groups[6] shl 16) or groups[7])
        else -> true
    }
}

private fun isGlobalIpv6(groups: IntArray): Boolean {
    val first = groups[0]
    return when {
        groups.all { it == 0 } -> false // ::
        groups.take(6).all { it == 0 } && groups[6] == 0 && groups[7] == 1 -> false // ::1
        (first and 0xfe00) == 0xfc00 -> false // fc00::/7 unique-local
        (first and 0xffc0) == 0xfe80 -> false // fe80::/10 link-local
        (first and 0xffc0) == 0xfec0 -> false // fec0::/10 site-local (deprecated)
        (first and 0xff00) == 0xff00 -> false // ff00::/8 multicast
        first == 0x0064 && groups[1] == 0xff9b -> false // 64:ff9b::/96 NAT64
        // ::ffff:a.b.c.d (IPv4-mapped) and ::a.b.c.d (IPv4-compatible) keep an IPv4 address low.
        groups.take(5).all { it == 0 } && (groups[5] == 0xffff || groups[5] == 0) ->
            isGlobalIpv4((groups[6] shl 16) or groups[7])
        else -> true
    }
}
// KUN <--

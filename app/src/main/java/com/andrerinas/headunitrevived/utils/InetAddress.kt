package com.andrerinas.headunitrevived.utils

import android.os.Build
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Convert a IPv4 address from an integer to an InetAddress.
 * @param hostAddress an int corresponding to the IPv4 address in network byte order
 */
fun Int.toInetAddress(): InetAddress {
    val hostAddress = this
    val addressBytes = byteArrayOf((0xff and hostAddress).toByte(),
            (0xff and (hostAddress shr 8)).toByte(),
            (0xff and (hostAddress shr 16)).toByte(),
            (0xff and (hostAddress shr 24)).toByte())
    return try {
        InetAddress.getByAddress(addressBytes)
    } catch (e: UnknownHostException) {
        AppLog.e(e)
        throw e
    }
}

fun InetAddress.changeLastBit(byte: Byte): InetAddress {
    return InetAddress.getByAddress(byteArrayOf(address[0], address[1], address[2], byte))
}

// Loose IPv6 literal check (hex digits/colons, optionally an embedded dotted-quad tail, at
// least two colons) — not a full RFC 4291 grammar, but this only needs to rule hostnames out,
// and on a local head-unit-server network the address is in practice always IPv4 anyway.
private val IPV6_LITERAL_REGEX = Regex("^\\[?[0-9a-fA-F:]+(:\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})?]?$")

/**
 * True if [this] is a numeric IPv4/IPv6 address literal in canonical dotted/colon form (e.g.
 * "192.168.1.1"), not a hostname and not an alternate/shorthand encoding (octal, decimal-integer,
 * "127.1", etc. are all rejected, same as any other ambiguous form an attacker could use to get
 * a different address parsed than what a human reviewing it would assume).
 *
 * Used at trust-boundary checks (deep links, discovered-host approval) that must not trigger DNS
 * resolution: approving/remembering a *hostname* would let its resolution change between approval
 * time and connect time (DNS rebinding — e.g. resolving to loopback or an internal host later),
 * silently pointing an "approved" entry at a different target than the user saw.
 */
fun String.isNumericIpAddress(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Official replacement for Patterns.IP_ADDRESS below — covers IPv4 and IPv6.
        if (android.net.InetAddresses.isNumericAddress(this)) return true
    } else {
        @Suppress("DEPRECATION")
        if (android.util.Patterns.IP_ADDRESS.matcher(this).matches()) return true
    }
    if (this.count { it == ':' } >= 2 && IPV6_LITERAL_REGEX.matches(this)) return true
    return false
}

/**
 * Canonical form of a numeric IP address literal, so the same address can't be
 * stored/approved/compared as two different-looking strings. Only call after confirming
 * [isNumericIpAddress] — this does not itself validate, and per [InetAddress.getByName]'s
 * contract, calling it on a string that's already a numeric literal never performs a DNS lookup
 * (that only happens for a hostname, which this is guaranteed not to be at this point).
 */
fun String.normalizedIpAddress(): String = InetAddress.getByName(this).hostAddress!!
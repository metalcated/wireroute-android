/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import android.net.Uri
import com.wireguard.config.InetEndpoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

data class WireRouteEndpointLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String?,
    val region: String?,
    val country: String?
) {
    val displayName: String
        get() = listOfNotNull(city, region, country).filter(String::isNotBlank).distinct().joinToString(", ")
}

object WireRouteEndpointLocator {
    fun locate(endpoint: InetEndpoint, serviceBaseURL: String): WireRouteEndpointLocation {
        val resolved = endpoint.resolved.orElseThrow {
            IllegalArgumentException("The endpoint hostname could not be resolved.")
        }
        val address = InetAddress.getByName(resolved.host)
        if (!isPublic(address)) {
            throw IllegalArgumentException("Private and local endpoint addresses cannot be located on a public map.")
        }
        val url = Uri.parse(serviceBaseURL).buildUpon().appendPath(address.hostAddress).build().toString()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) throw IllegalArgumentException("Endpoint location lookup failed.")
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(payload)
            if (!json.optBoolean("success", false)) throw IllegalArgumentException("Endpoint location lookup failed.")
            val latitude = json.optDouble("latitude", Double.NaN)
            val longitude = json.optDouble("longitude", Double.NaN)
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                throw IllegalArgumentException("Endpoint location lookup returned an invalid position.")
            }
            return WireRouteEndpointLocation(
                latitude,
                longitude,
                json.optString("city").takeIf(String::isNotBlank),
                json.optString("region").takeIf(String::isNotBlank),
                json.optString("country").takeIf(String::isNotBlank)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                !(bytes[0] == 100 && bytes[1] in 64..127) &&
                    !(bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 2) &&
                    !(bytes[0] == 198 && bytes[1] in 18..19) &&
                    !(bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100) &&
                    !(bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113)
            }
            is Inet6Address -> !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
            else -> false
        }
    }
}

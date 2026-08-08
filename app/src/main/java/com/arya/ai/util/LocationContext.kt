package com.arya.ai.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.arya.ai.tools.InfoApiTools

/**
 * Like [DateTimeContext] but for location — folds the phone's last-known GPS/network
 * location into every system prompt so Arya can answer
 * "kahan hoon main" / weather-near-me / nearby-places style questions correctly without
 * the user having to explicitly invoke the `get_location` tool first.
 *
 * Reverse-geocoding needs a network call (or the on-device Geocoder, which itself can be
 * slow), so the resolved line is cached for [CACHE_TTL_MS] instead of being recomputed on
 * every single chat message — location doesn't change fast enough for that to matter, and
 * doing it fresh every turn would add latency to every reply.
 */
object LocationContext {

    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    @Volatile private var cachedLine: String = ""
    @Volatile private var cachedAtMillis: Long = 0L

    /** Empty string if location isn't available (no permission, no cached fix yet, or
     *  geocoding failed) — callers just append this into a prompt, so empty is silently fine. */
    fun currentLocationLine(context: Context): String {
        val now = System.currentTimeMillis()
        if (now - cachedAtMillis < CACHE_TTL_MS) return cachedLine
        val line = computeLine(context)
        cachedLine = line
        cachedAtMillis = now
        return line
    }

    private fun computeLine(context: Context): String {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return ""
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = lm.getProviders(true).asSequence()
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?: return ""
            val readable = InfoApiTools.reverseGeocode(context, location.latitude, location.longitude)
            if (readable.startsWith("❌")) "" else
                "User ka current location (phone GPS se, approx): $readable. " +
                    "Isko sirf tab use karo jab location-dependent sawaal ho (mausam, nearby, 'kahan hoon'), " +
                    "har jawaab me isko mat dohrao."
        } catch (e: Exception) {
            ""
        }
    }
}

package com.arya.ai.tools

import android.content.Context
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Free/keyless public-API tools ported from the original assistant's `tools.py`. No Termux, no server —
 * every call goes straight from the phone to the public API over OkHttp.
 */
object InfoApiTools {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Phase 3 (see chat history): tries Open-Meteo first, falls back to wttr.in (independent
     *  provider — covers Open-Meteo being down/rate-limited, not just this city being unknown
     *  to it) before giving up. */
    fun getWeather(city: String): String {
        tryOpenMeteoWeather(city)?.let { return it }
        tryWttrInWeather(city)?.let { return it }
        return "❌ '$city' ka weather nahi mil paaya (dono sources fail)"
    }

    private fun tryOpenMeteoWeather(city: String): String? {
        // Open-Meteo: free, no API key. First geocode the city, then fetch current weather.
        val geo = NetTools.getJson("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1")
        val result = geo?.optJSONArray("results")?.optJSONObject(0) ?: return null
        val lat = result.getDouble("latitude")
        val lon = result.getDouble("longitude")
        val name = result.optString("name", city)
        val weather = NetTools.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
        )?.optJSONObject("current") ?: return null
        val temp = weather.optDouble("temperature_2m")
        val humidity = weather.optInt("relative_humidity_2m")
        val wind = weather.optDouble("wind_speed_10m")
        return "🌤️ $name: ${temp}°C, humidity ${humidity}%, wind ${wind} km/h"
    }

    private fun tryWttrInWeather(city: String): String? {
        val json = NetTools.getJson("https://wttr.in/${enc(city)}?format=j1") ?: return null
        val current = json.optJSONArray("current_condition")?.optJSONObject(0) ?: return null
        val temp = current.optString("temp_C")
        val humidity = current.optString("humidity")
        val wind = current.optString("windspeedKmph")
        if (temp.isBlank()) return null
        return "🌤️ $city: ${temp}°C, humidity ${humidity}%, wind ${wind} km/h (fallback source)"
    }

    /** Common coin-name → Binance ticker symbol mapping, for [tryBinancePrice]'s fallback.
     *  CoinGecko takes free-form names ("bitcoin"); Binance only takes tickers, so this only
     *  covers coins popular enough to bother mapping — anything else just skips the fallback
     *  and surfaces the original CoinGecko error. */
    private val BINANCE_SYMBOLS = mapOf(
        "bitcoin" to "BTC", "ethereum" to "ETH", "dogecoin" to "DOGE", "litecoin" to "LTC",
        "cardano" to "ADA", "solana" to "SOL", "ripple" to "XRP", "polkadot" to "DOT",
        "binancecoin" to "BNB", "tron" to "TRX", "matic-network" to "MATIC", "shiba-inu" to "SHIB"
    )

    /** Phase 3 (see chat history): CoinGecko first (has both USD+INR), Binance fallback for
     *  popular coins (USD only — Binance doesn't quote INR pairs) if CoinGecko is down/rate-limited. */
    fun getCryptoPrice(coin: String): String {
        val id = coin.trim().lowercase().ifBlank { "bitcoin" }
        tryCoinGeckoPrice(id)?.let { return it }
        tryBinancePrice(id)?.let { return it }
        return "❌ '$coin' coin nahi mila (dono sources fail)"
    }

    private fun tryCoinGeckoPrice(id: String): String? {
        val json = NetTools.getJson("https://api.coingecko.com/api/v3/simple/price?ids=$id&vs_currencies=usd,inr")
            ?.optJSONObject(id) ?: return null
        val usd = json.optDouble("usd")
        val inr = json.optDouble("inr")
        if (usd.isNaN() && inr.isNaN()) return null
        return "💰 ${id.uppercase()}: \$$usd USD / ₹$inr INR"
    }

    private fun tryBinancePrice(id: String): String? {
        val symbol = BINANCE_SYMBOLS[id] ?: return null
        val price = NetTools.getJson("https://api.binance.com/api/v3/ticker/price?symbol=${symbol}USDT")
            ?.optString("price")?.toDoubleOrNull() ?: return null
        return "💰 ${id.uppercase()}: \$$price USD (fallback source, INR unavailable)"
    }

    /** Phase 3 (see chat history): open.er-api.com first, frankfurter.app fallback if it's
     *  down/rate-limited — independent providers, no coin-style symbol-mapping gap since both
     *  use plain ISO currency codes. */
    fun convertCurrency(amount: Double, from: String, to: String): String {
        tryOerConvert(amount, from, to)?.let { return it }
        tryFrankfurterConvert(amount, from, to)?.let { return it }
        return "❌ Currency convert nahi ho paaya (dono sources fail)"
    }

    private fun tryOerConvert(amount: Double, from: String, to: String): String? {
        val json = NetTools.getJson("https://open.er-api.com/v6/latest/${from.uppercase()}") ?: return null
        val rate = json.optJSONObject("rates")?.optDouble(to.uppercase()) ?: return null
        if (rate.isNaN()) return null
        val converted = amount * rate
        return "💱 $amount ${from.uppercase()} = ${"%.2f".format(converted)} ${to.uppercase()}"
    }

    private fun tryFrankfurterConvert(amount: Double, from: String, to: String): String? {
        val json = NetTools.getJson(
            "https://api.frankfurter.app/latest?amount=$amount&from=${from.uppercase()}&to=${to.uppercase()}"
        ) ?: return null
        val converted = json.optJSONObject("rates")?.optDouble(to.uppercase()) ?: return null
        if (converted.isNaN()) return null
        return "💱 $amount ${from.uppercase()} = ${"%.2f".format(converted)} ${to.uppercase()} (fallback source)"
    }

    fun getCountryInfo(country: String): String {
        val arr = NetTools.getText("https://restcountries.com/v3.1/name/${enc(country)}")
        if (arr.isBlank()) return "❌ '$country' nahi mila"
        val json = try { JSONArray(arr).optJSONObject(0) } catch (e: Exception) { null }
            ?: return "❌ '$country' nahi mila"
        val name = json.optJSONObject("name")?.optString("common", country) ?: country
        val capital = json.optJSONArray("capital")?.optString(0) ?: "?"
        val population = json.optLong("population")
        val region = json.optString("region", "?")
        return "🌍 $name — capital: $capital, region: $region, population: ${"%,d".format(population)}"
    }

    fun getIpInfo(ip: String): String {
        val url = if (ip.isBlank()) "https://ipapi.co/json/" else "https://ipapi.co/${enc(ip)}/json/"
        val json = NetTools.getJson(url) ?: return "❌ IP info fetch nahi ho paaya"
        val city = json.optString("city", "?")
        val region = json.optString("region", "?")
        val country = json.optString("country_name", "?")
        val org = json.optString("org", "?")
        return "📡 ${json.optString("ip", ip)} → $city, $region, $country ($org)"
    }

    fun getDictionary(word: String): String {
        val arr = NetTools.getText("https://api.dictionaryapi.dev/api/v2/entries/en/${enc(word)}")
        if (arr.isBlank()) return "❌ '$word' dictionary me nahi mila"
        return try {
            val entry = JSONArray(arr).optJSONObject(0)
            val meaning = entry.optJSONArray("meanings")?.optJSONObject(0)
            val partOfSpeech = meaning?.optString("partOfSpeech", "") ?: ""
            val definition = meaning?.optJSONArray("definitions")?.optJSONObject(0)?.optString("definition", "")
            "📖 $word ($partOfSpeech): $definition"
        } catch (e: Exception) {
            "❌ '$word' parse nahi ho paaya"
        }
    }

    fun translateText(text: String, targetLang: String): String {
        val json = NetTools.getJson(
            "https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=en|${targetLang.ifBlank { "hi" }}"
        ) ?: return "❌ Translation fail hui"
        val translated = json.optJSONObject("responseData")?.optString("translatedText")
        return if (translated.isNullOrBlank()) "❌ Translation fail hui" else "🌐 $translated"
    }

    fun getWikipediaSummary(query: String): String {
        val json = NetTools.getJson("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(query)}")
            ?: return "❌ Wikipedia par '$query' nahi mila"
        val extract = json.optString("extract")
        return if (extract.isBlank()) "❌ Wikipedia par '$query' nahi mila" else "📚 $extract"
    }

    fun getSunriseSunset(city: String): String {
        val geo = NetTools.getJson("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1")
            ?.optJSONArray("results")?.optJSONObject(0) ?: return "❌ '$city' nahi mila"
        val lat = geo.getDouble("latitude")
        val lon = geo.getDouble("longitude")
        val json = NetTools.getJson("https://api.sunrise-sunset.org/json?lat=$lat&lng=$lon&formatted=0")
            ?.optJSONObject("results") ?: return "❌ Sunrise/sunset data nahi mila"
        return "🌅 $city — sunrise: ${json.optString("sunrise")}, sunset: ${json.optString("sunset")} (UTC)"
    }

    fun getPublicHolidays(countryCode: String, year: Int): String {
        val arr = NetTools.getText("https://date.nager.at/api/v3/PublicHolidays/$year/${countryCode.uppercase()}")
        if (arr.isBlank()) return "❌ $countryCode ke liye holidays nahi mile"
        return try {
            val list = JSONArray(arr)
            val lines = (0 until minOf(5, list.length())).map {
                val h = list.getJSONObject(it)
                "• ${h.optString("date")} — ${h.optString("localName")}"
            }
            "🎉 $countryCode $year ke holidays (pehle 5):\n" + lines.joinToString("\n")
        } catch (e: Exception) {
            "❌ Holidays parse nahi ho paaye"
        }
    }

    fun getSpacexLaunches(upcoming: Boolean): String {
        val path = if (upcoming) "upcoming" else "latest"
        val text = NetTools.getText("https://api.spacexdata.com/v5/launches/$path")
        if (text.isBlank()) return "❌ SpaceX data fetch nahi hua"
        return try {
            if (upcoming) {
                val arr = JSONArray(text)
                val next = arr.optJSONObject(0) ?: return "❌ Koi upcoming launch nahi mila"
                "🚀 Next launch: ${next.optString("name")} — ${next.optString("date_utc")}"
            } else {
                val json = org.json.JSONObject(text)
                "🚀 Latest launch: ${json.optString("name")} — ${json.optString("date_utc")}"
            }
        } catch (e: Exception) {
            "❌ SpaceX data parse nahi hua"
        }
    }

    // ---- NASA (via Arya Relay — key lives server-side, DEMO_KEY used if none configured there) ----

    fun getNasaApod(context: Context): String {
        val json = relayGet(context, "/v1/nasa?type=apod") ?: return "❌ NASA APOD fetch nahi hua"
        return "🛰️ ${json.optString("title")}: ${json.optString("explanation").take(300)}...\n${json.optString("url")}"
    }

    fun getNasaIssLocation(): String {
        val json = NetTools.getJson("http://api.open-notify.org/iss-now.json") ?: return "❌ ISS location fetch nahi hui"
        val pos = json.optJSONObject("iss_position") ?: return "❌ ISS location fetch nahi hui"
        return "🛰️ ISS abhi: lat ${pos.optString("latitude")}, lon ${pos.optString("longitude")}"
    }

    fun getNasaAsteroids(context: Context): String {
        val json = relayGet(context, "/v1/nasa?type=asteroids") ?: return "❌ Asteroid data fetch nahi hua"
        val neo = json.optJSONObject("near_earth_objects") ?: return "❌ Asteroid data nahi mila"
        val todayKey = neo.keys().asSequence().firstOrNull() ?: return "🛰️ Aaj koi tracked asteroid nahi"
        val count = neo.optJSONArray(todayKey)?.length() ?: 0
        return "☄️ Aaj $count near-Earth asteroid track ho rahe hain"
    }

    fun getNasaMarsPhotos(context: Context): String {
        val json = relayGet(context, "/v1/nasa?type=mars") ?: return "❌ Mars photos fetch nahi hue"
        val photos = json.optJSONArray("latest_photos")
        if (photos == null || photos.length() == 0) return "❌ Koi recent Mars photo nahi mila"
        return "📷 Curiosity rover latest photo: ${photos.getJSONObject(0).optString("img_src")}"
    }

    // ---- Wolfram Alpha (via Arya Relay — AppID lives server-side only) ----

    fun askWolfram(context: Context, question: String): String {
        val json = relayGet(context, "/v1/wolfram?q=${enc(question)}") ?: return "❌ Wolfram Alpha ne jawab nahi diya"
        val text = json.optString("text")
        return if (text.isBlank() || text == "null") "❌ Wolfram Alpha ne jawab nahi diya" else "🧠 $text"
    }

    /** GET against Arya Relay (see arya-relay/app.py) with the same X-App-Secret auth the online
     * chat calls use ([com.arya.ai.util.OnlineChatHelper]) — null on any failure (missing relay
     * config, network error, non-2xx) so callers just show their existing "❌ ... nahi hua" text. */
    private fun relayGet(context: Context, path: String): org.json.JSONObject? {
        val relayUrl = com.arya.ai.BuildConfig.RELAY_URL
        if (relayUrl.isBlank()) return null
        // RELAY_URL points at .../v1/relay (the chat endpoint) — this strips that suffix to get
        // the relay's base origin, then appends whichever path (/v1/nasa, /v1/wolfram) is needed.
        val base = relayUrl.removeSuffix("/v1/relay").removeSuffix("/")
        return try {
            val connection = (java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-App-Secret", com.arya.ai.BuildConfig.RELAY_APP_SECRET)
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            if (connection.responseCode !in 200..299) return null
            org.json.JSONObject(connection.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            null
        }
    }

    // ---- location (OpenStreetMap/Nominatim — free, no key, same as the original's OSM integration) ----

    /** Uses Android's on-device [android.location.Geocoder] first (works offline on many devices); falls back to BigDataCloud, then Nominatim. */
    fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String {
        return try {
            val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(latitude, longitude, 1)
            val address = results?.firstOrNull()
            if (address != null) {
                "📍 ${address.getAddressLine(0) ?: "${address.locality}, ${address.countryName}"}"
            } else {
                reverseGeocodeViaBigDataCloud(latitude, longitude) ?: reverseGeocodeViaNominatim(latitude, longitude)
            }
        } catch (e: Exception) {
            reverseGeocodeViaBigDataCloud(latitude, longitude) ?: reverseGeocodeViaNominatim(latitude, longitude)
        }
    }

    /** BigDataCloud's free reverse-geocode endpoint — no API key needed, no rate-limit headaches like Nominatim's usage policy. Returns null on failure so the caller can fall through to Nominatim. */
    private fun reverseGeocodeViaBigDataCloud(latitude: Double, longitude: Double): String? {
        val json = NetTools.getJson(
            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$latitude&longitude=$longitude&localityLanguage=en"
        ) ?: return null
        val locality = json.optString("locality", "")
        val city = json.optString("city", locality)
        val country = json.optString("countryName", "")
        if (city.isBlank() && country.isBlank()) return null
        return "📍 ${listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")}"
    }

    private fun reverseGeocodeViaNominatim(latitude: Double, longitude: Double): String {
        val json = NetTools.getJson(
            "https://nominatim.openstreetmap.org/reverse?lat=$latitude&lon=$longitude&format=json",
            headers = mapOf("User-Agent" to "AryaApp/1.1")
        ) ?: return "❌ Location resolve nahi ho paayi"
        return "📍 ${json.optString("display_name", "unknown location")}"
    }

    fun searchPlaceOsm(query: String): String {
        val json = NetTools.getText(
            "https://nominatim.openstreetmap.org/search?q=${enc(query)}&format=json&limit=3",
            headers = mapOf("User-Agent" to "AryaApp/1.1")
        )
        if (json.isBlank()) return "❌ Place search fail hui (network issue)"
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return "❌ '$query' nahi mila"
            "📍 Places for '$query':\n" + (0 until arr.length()).joinToString("\n") { i ->
                val p = arr.getJSONObject(i)
                val lat = p.optString("lat")
                val lon = p.optString("lon")
                "• ${p.optString("display_name")} ($lat, $lon)\n  🗺️ https://www.google.com/maps/search/?api=1&query=$lat,$lon"
            }
        } catch (e: Exception) {
            "❌ Place results parse nahi ho paaye"
        }
    }
}

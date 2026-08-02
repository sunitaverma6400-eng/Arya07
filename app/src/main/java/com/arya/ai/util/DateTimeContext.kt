package com.arya.ai.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * LLMs only know whatever date their training data happened to stop at (e.g. they might
 * think it's still 2024) — they have no built-in sense of "today". Left alone, Arya can
 * confidently state a wrong current date/year, get relative dates ("kal", "is hafte") wrong,
 * and misjudge how old other information is.
 *
 * The fix isn't to ask the model — it's to tell it, every single turn, using the phone's
 * own system clock ([System.currentTimeMillis], always correct as long as the device's
 * clock is). [currentDateTimeLine] is meant to be folded into every system prompt sent to
 * the online (Groq/Gemini/OpenRouter) model, so Arya's sense of "now" is always the real
 * "now" — not a training-data guess.
 */
object DateTimeContext {

    private val formatter = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)

    fun currentDateTimeLine(): String {
        formatter.timeZone = TimeZone.getDefault()
        val now = formatter.format(Date())
        return "Aaj ki real date/time (phone ki system clock se, hamesha accurate hai): $now. " +
            "Apne training data ke andaze se koi bhi date/year mat batana — hamesha yahi asli " +
            "current date/time use karo, chahe tumhara training data kisi purani date par khatam hua ho."
    }
}

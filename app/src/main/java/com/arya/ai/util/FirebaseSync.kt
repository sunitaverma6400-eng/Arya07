package com.arya.ai.util

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Community/usage stats + optional chat sync, all through Firebase — chosen specifically
 * because it needs **no Python/Termux server and no separate machine to keep running**: it's
 * just a Google-hosted Realtime Database + Analytics, reachable straight from the phone over
 * plain HTTPS/websocket, the same way the app already talks to Groq/Gemini/weather/etc.
 *
 * Two separate things happen here, deliberately gated differently:
 *
 * 1. **Anonymous presence + total-install counting** — always on, no consent dialog needed,
 *    same as how virtually every app quietly counts installs/DAU. Answers "kitne log jude hue
 *    hain" (total) and "kitne abhi online hain" (live) — shown in [com.arya.ai.ui.CommunityScreen].
 *    "Kaha kaha se log jude hain" (geography) doesn't need any custom code at all — Firebase
 *    Analytics already shows a country/city breakdown automatically in the Firebase Console's
 *    Analytics -> Demographics tab, purely from the Analytics SDK being present.
 * 2. **Chat content sync** — what people are actually typing to Arya, so real conversations can
 *    guide what to fix/improve next. This is genuinely personal (people may type names, health
 *    stuff, anything), so it's strictly gated behind [PreferencesManager.dataConsentGiven] —
 *    the "Do you want to chat or share personal data to further improve AI?" dialog shown
 *    alongside the runtime-permission prompt on first launch (see MainActivity). If the user
 *    taps "Nahi", [logChatExchange] is a no-op — nothing about their conversations ever leaves
 *    the phone, only the anonymous online/total counts from #1 still do.
 *
 * **Requires a real `app/google-services.json`** from your own Firebase project (Firebase
 * Console -> Add app -> Android, package `com.arya.ai`) — this sandbox has no network access to
 * generate one for you. Every function below fails silently (logs a warning, does nothing) if
 * Firebase was never initialized, so a build without that file still compiles and runs exactly
 * as before, it just won't have community stats. See README.md's new "Firebase setup" section.
 */
object FirebaseSync {

    private const val TAG = "FirebaseSync"

    /** True once `app/google-services.json` was present at build time AND FirebaseApp actually
     *  initialized at runtime — checked before every operation below so a missing/broken setup
     *  degrades to "feature quietly does nothing" instead of a crash. */
    private fun isAvailable(context: Context): Boolean {
        return try {
            FirebaseApp.initializeApp(context) != null
        } catch (e: Exception) {
            Log.w(TAG, "Firebase not configured (${e.message}) — community stats/chat sync disabled. " +
                "Add app/google-services.json from your own Firebase project to enable this.")
            false
        }
    }

    private fun db(context: Context) = FirebaseDatabase.getInstance(FirebaseApp.initializeApp(context)!!)

    /**
     * Call once, early (MainActivity's startup LaunchedEffect) — every launch, not just the
     * first. Does three things:
     * 1. Logs an Analytics `app_open` event (Analytics also auto-logs its own `first_open` /
     *    session events regardless, this just adds one more explicit signal).
     * 2. If this [PreferencesManager.installId] has never been seen before, records it under
     *    `/users/{installId}` and bumps `/meta/totalUsers` by 1 — this is the "kitne log app se
     *    jude hue hain" (all-time) number.
     * 3. Sets up live presence: writes `/presence/{installId}` and bumps `/meta/onlineCount`
     *    the moment the socket connects, with `onDisconnect()` cleanup registered *before* that
     *    write so the count self-corrects (decrements/removes) the moment the app closes, the
     *    phone loses network, or the process dies — no explicit "closing" call needed anywhere
     *    else in the app. This is the "kitne log abhi online hain" (live) number.
     *
     * Known trade-off, stated plainly: if `.info/connected` fires more than once in a single
     * app session (e.g. phone briefly loses and regains network), this re-registers the same
     * onDisconnect + re-increments `/meta/onlineCount` — Firebase's own presence-pattern docs
     * flag this as the standard, accepted behavior of this pattern (an old, stale `onDisconnect`
     * still fires but only removes/decrements once), not something specific to this code.
     */
    fun trackUser(context: Context, prefs: PreferencesManager) {
        if (!isAvailable(context)) return
        val installId = prefs.installId

        try {
            FirebaseAnalytics.getInstance(context).logEvent("app_open", null)
        } catch (e: Exception) {
            Log.w(TAG, "Analytics app_open log failed: ${e.message}")
        }

        val database = db(context)

        // Total unique installs — only bumped the first time this installId is ever seen.
        val userRef = database.getReference("users/$installId")
        userRef.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                userRef.setValue(mapOf("firstSeen" to ServerValue.TIMESTAMP))
                database.getReference("meta/totalUsers").setValue(ServerValue.increment(1))
            }
        }.addOnFailureListener { e -> Log.w(TAG, "totalUsers check failed: ${e.message}") }

        // Live online presence.
        val connectedRef = database.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (!connected) return
                val presenceRef = database.getReference("presence/$installId")
                val onlineCountRef = database.getReference("meta/onlineCount")
                // Register cleanup BEFORE the write, so a disconnect mid-write still cleans up.
                presenceRef.onDisconnect().removeValue()
                onlineCountRef.onDisconnect().setValue(ServerValue.increment(-1))
                presenceRef.setValue(mapOf("lastSeen" to ServerValue.TIMESTAMP))
                onlineCountRef.setValue(ServerValue.increment(1))
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence listener cancelled: ${error.message}")
            }
        })
    }

    /**
     * Pushes one user+model exchange to `/chats/{installId}/{pushId}` for later review in the
     * Firebase Console — no-op unless [PreferencesManager.dataConsentGiven] is true. This is
     * the actual conversation content, so it's the one thing gated behind the consent dialog;
     * everything in [trackUser] above is anonymous counting and runs regardless.
     */
    fun logChatExchange(context: Context, prefs: PreferencesManager, userText: String, modelText: String) {
        if (!prefs.dataConsentGiven) return
        if (!isAvailable(context)) return
        try {
            val ref = db(context).getReference("chats/${prefs.installId}").push()
            ref.setValue(
                mapOf(
                    "user" to userText,
                    "model" to modelText,
                    "timestamp" to ServerValue.TIMESTAMP
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Chat sync failed: ${e.message}")
        }
    }

    /** Fire-and-forget Analytics event for tool usage (e.g. which of Arya's ~110 tools get
     *  used most in practice) — anonymous, no consent gate, same as [trackUser]. */
    fun logToolUsed(context: Context, toolName: String) {
        if (!isAvailable(context)) return
        try {
            val bundle = android.os.Bundle().apply { putString("tool_name", toolName) }
            FirebaseAnalytics.getInstance(context).logEvent("tool_used", bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Analytics tool_used log failed: ${e.message}")
        }
    }

    /**
     * One-shot read of the two community numbers, for [com.arya.ai.ui.CommunityScreen].
     * [onResult] is called with (totalUsers, onlineCount), each null if unavailable/unconfigured.
     */
    fun readCommunityStats(context: Context, onResult: (totalUsers: Long?, onlineCount: Long?) -> Unit) {
        if (!isAvailable(context)) {
            onResult(null, null)
            return
        }
        val metaRef = db(context).getReference("meta")
        metaRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val total = snapshot.child("totalUsers").getValue(Long::class.java)
                val online = snapshot.child("onlineCount").getValue(Long::class.java)
                onResult(total, online?.coerceAtLeast(0))
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "readCommunityStats failed: ${error.message}")
                onResult(null, null)
            }
        })
    }

    /** Live version of [readCommunityStats] — keeps calling [onUpdate] as the numbers change,
     *  so [com.arya.ai.ui.CommunityScreen] updates in real time while it's open. */
    fun observeCommunityStats(context: Context, onUpdate: (totalUsers: Long?, onlineCount: Long?) -> Unit) {
        if (!isAvailable(context)) {
            onUpdate(null, null)
            return
        }
        val metaRef = db(context).getReference("meta")
        metaRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val total = snapshot.child("totalUsers").getValue(Long::class.java)
                val online = snapshot.child("onlineCount").getValue(Long::class.java)
                onUpdate(total, online?.coerceAtLeast(0))
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeCommunityStats failed: ${error.message}")
            }
        })
    }
}

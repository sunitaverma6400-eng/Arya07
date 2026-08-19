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

    // ========================================================================================
    // Community Intelligence — hyperlocal updates ("traffic on X road", "paani nahi aa raha",
    // "sasta sabzi wala kahan hai"), scoped to a named area/mohalla and shared across everyone
    // in that area, on the exact same Firebase RTDB already wired up above.
    //
    // Deliberately keyed by a NAMED AREA STRING the person types/confirms (e.g. "Sector 15,
    // Noida"), not by raw GPS coordinates — [com.arya.ai.util.LocationContext] can suggest one
    // via reverse-geocoding to save typing, but nothing here ever writes a live lat/lng to the
    // database. A named locality is precise enough to be useful for "log kya keh rahe hain
    // yahan" without turning this into a live location-tracking feed of who's currently where.
    //
    // This only becomes genuinely useful once several people in the same area use it — a
    // single person posting to their own empty area sees nothing back. That's an inherent
    // property of any hyperlocal/community feature, not a bug in this code.
    // ========================================================================================

    /** Firebase RTDB keys can't contain '.', '#', '$', '[', ']', or '/' — collapse an area name
     *  like "Sector 15, Noida" into a safe key ("sector_15_noida") so any locality name works. */
    private fun areaKey(area: String): String =
        area.trim().lowercase()
            .replace(Regex("[.#$\\[\\]/]"), "")
            .replace(Regex("\\s+"), "_")
            .take(100)
            .ifBlank { "unknown_area" }

    /** [key] is the Firebase push-key (see [CommunityUpdate.key]) — needed so
     *  [markItemResolved] can find and update the exact right node. [installId] is whoever
     *  posted it, so only the poster's own device shows a "मिल गया" button for it — anyone can
     *  READ community posts, but only the poster can mark their own item resolved. */
    data class CommunityUpdate(
        val key: String, val text: String, val category: String, val timestampMs: Long,
        val installId: String = "", val resolved: Boolean = false
    )

    /** Posts one update, visible to everyone reading this same [area]. No consent gate (unlike
     *  [logChatExchange]) because this is content the person is deliberately choosing to
     *  publish to their neighbours, not private conversation content. */
    fun postCommunityUpdate(context: Context, area: String, category: String, text: String, installId: String) {
        if (!isAvailable(context)) return
        if (area.isBlank() || text.isBlank()) return
        try {
            val ref = db(context).getReference("community/${areaKey(area)}").push()
            ref.setValue(
                mapOf(
                    "text" to text.trim().take(500),
                    "category" to category,
                    "timestamp" to ServerValue.TIMESTAMP,
                    "installId" to installId,
                    "resolved" to false
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "postCommunityUpdate failed: ${e.message}")
        }
    }

    /** Neighborhood Sharing — lets the ORIGINAL POSTER (checked against [installId], not
     *  enforced server-side since this has no auth backend, but the UI only ever offers this
     *  button on the poster's own posts) mark a lending/sharing post as fulfilled, so it stops
     *  showing as "available" without deleting the post's history entirely. */
    fun markItemResolved(context: Context, area: String, key: String) {
        if (!isAvailable(context) || area.isBlank() || key.isBlank()) return
        try {
            db(context).getReference("community/${areaKey(area)}/$key/resolved").setValue(true)
        } catch (e: Exception) {
            Log.w(TAG, "markItemResolved failed: ${e.message}")
        }
    }

    /** Live feed of the most recent updates for [area] (newest first), for
     *  [com.arya.ai.ui.CommunityScreen]'s hyperlocal section. */
    fun observeCommunityUpdates(context: Context, area: String, onUpdate: (List<CommunityUpdate>) -> Unit) {
        if (!isAvailable(context) || area.isBlank()) {
            onUpdate(emptyList())
            return
        }
        val ref = db(context).getReference("community/${areaKey(area)}")
            .orderByChild("timestamp")
            .limitToLast(50)
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val items = snapshot.children.mapNotNull { child ->
                    val text = child.child("text").getValue(String::class.java) ?: return@mapNotNull null
                    val category = child.child("category").getValue(String::class.java) ?: "अन्य"
                    val ts = child.child("timestamp").getValue(Long::class.java) ?: 0L
                    val installId = child.child("installId").getValue(String::class.java) ?: ""
                    val resolved = child.child("resolved").getValue(Boolean::class.java) ?: false
                    CommunityUpdate(child.key ?: "", text, category, ts, installId, resolved)
                }.sortedByDescending { it.timestampMs }
                onUpdate(items)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeCommunityUpdates failed: ${error.message}")
                onUpdate(emptyList())
            }
        })
    }

    // ========================================================================================
    // Family Circle — a small shared "code" (like a room code) that links a few installIds
    // together, so features that need MORE than one person's own device (Family Pulse, Family
    // Vision Board) have something to read from. Reuses this same Firebase RTDB, no new backend.
    //
    // Deliberately minimal what gets written per member: a nickname, last-check-in date,
    // last-memory-added date, and their #1 stated priority NAME. Not raw daily notes, not
    // location, not chat content — those stay exactly as gated as everywhere else in this file.
    // ========================================================================================

    data class FamilyCircleMember(
        val installId: String,
        val nickname: String,
        val lastCheckinDate: String,
        val lastMemoryDate: String,
        val topPriority: String,
        // Family Mediator Mode — DELIBERATELY separate from topPriority/lastCheckinDate above.
        // Those auto-push every time Family Pulse/Vision Board opens; this only gets written
        // when the person explicitly taps "share" on FamilyMediatorScreen (see
        // shareMediatorSummary's doc comment) — a fuller priority-mismatch report is more
        // revealing than a single priority name, so it needs its own deliberate consent, not
        // the same passive auto-push as the other signals.
        val mediatorSummary: String = "",
        // Sudden-Change Detection — a single COUNT (checkins in the last 14 days), not raw
        // dates, so this stays consistent with the "minimal signal, not a detailed log" stance
        // in this class's doc comment. Lets Family Pulse tell apart "was checking in almost
        // daily and abruptly stopped" from "was already sporadic" — those deserve different
        // levels of concern, and a single lastCheckinDate can't distinguish them.
        val recentCheckinCount14d: Int = -1 // -1 = not yet reported by that device
    )

    private fun circleKey(code: String): String =
        code.trim().uppercase().replace(Regex("[.#$\\[\\]/\\s]"), "").take(20).ifBlank { "FAMILY" }

    /** Joins (or re-joins) a family circle — just writes this installId's nickname under the
     *  code. Safe to call again if the nickname changes. */
    fun joinFamilyCircle(context: Context, code: String, installId: String, nickname: String) {
        if (!isAvailable(context)) return
        try {
            db(context).getReference("family_circle/${circleKey(code)}/members/$installId/nickname")
                .setValue(nickname.trim().take(40))
        } catch (e: Exception) {
            Log.w(TAG, "joinFamilyCircle failed: ${e.message}")
        }
    }

    /** Pushes just this device's own current signals — call this whenever Family Pulse or
     *  Family Vision Board opens, so the circle always reflects each member's latest local
     *  data next time they open the app (this is a snapshot push, not continuous background
     *  syncing — nothing runs when the app isn't open). */
    fun updateMyCircleSignal(
        context: Context, code: String, installId: String,
        lastCheckinDate: String?, lastMemoryDate: String?, topPriority: String?,
        recentCheckinCount14d: Int? = null
    ) {
        if (!isAvailable(context)) return
        try {
            val ref = db(context).getReference("family_circle/${circleKey(code)}/members/$installId")
            val updates = mutableMapOf<String, Any>()
            if (lastCheckinDate != null) updates["lastCheckinDate"] = lastCheckinDate
            if (lastMemoryDate != null) updates["lastMemoryDate"] = lastMemoryDate
            if (topPriority != null) updates["topPriority"] = topPriority
            if (recentCheckinCount14d != null) updates["recentCheckinCount14d"] = recentCheckinCount14d
            if (updates.isNotEmpty()) ref.updateChildren(updates)
        } catch (e: Exception) {
            Log.w(TAG, "updateMyCircleSignal failed: ${e.message}")
        }
    }

    /** Explicit, opt-in share for Family Mediator Mode — the person taps a button on
     *  FamilyMediatorScreen, sees exactly what will be shared, and only then is this called.
     *  Overwrites any previous share (a stale summary would just confuse a future mediation). */
    fun shareMediatorSummary(context: Context, code: String, installId: String, summary: String) {
        if (!isAvailable(context)) return
        try {
            db(context).getReference("family_circle/${circleKey(code)}/members/$installId/mediatorSummary")
                .setValue(summary.take(2000))
        } catch (e: Exception) {
            Log.w(TAG, "shareMediatorSummary failed: ${e.message}")
        }
    }

    /** Live list of everyone in this circle (including this device), for Family Pulse /
     *  Family Vision Board. */
    fun observeFamilyCircle(context: Context, code: String, onUpdate: (List<FamilyCircleMember>) -> Unit) {
        if (!isAvailable(context) || code.isBlank()) {
            onUpdate(emptyList())
            return
        }
        val ref = db(context).getReference("family_circle/${circleKey(code)}/members")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val members = snapshot.children.mapNotNull { child ->
                    val nickname = child.child("nickname").getValue(String::class.java) ?: return@mapNotNull null
                    FamilyCircleMember(
                        installId = child.key ?: "",
                        nickname = nickname,
                        lastCheckinDate = child.child("lastCheckinDate").getValue(String::class.java) ?: "",
                        lastMemoryDate = child.child("lastMemoryDate").getValue(String::class.java) ?: "",
                        topPriority = child.child("topPriority").getValue(String::class.java) ?: "",
                        mediatorSummary = child.child("mediatorSummary").getValue(String::class.java) ?: "",
                        recentCheckinCount14d = child.child("recentCheckinCount14d").getValue(Int::class.java) ?: -1
                    )
                }
                onUpdate(members)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeFamilyCircle failed: ${error.message}")
                onUpdate(emptyList())
            }
        })
    }

    // ========================================================================================
    // Family Time Capsule Vault — a SHARED sealed message the whole Family Circle contributes
    // to, unlike Future Self Letter (which is personal/local-only). Lives on this same
    // Firebase RTDB, scoped under the circle's own code, so everyone in the circle sees the
    // same capsules. Contribution text is visible to circle members once written (there's no
    // "sealed until unlock" for WRITING — only the unlock DATE itself gates when family reads
    // the finished capsule, per the [addContribution] doc comment below).
    // ========================================================================================

    data class CapsuleContribution(val installId: String, val nickname: String, val text: String, val timestampMs: Long)
    data class TimeCapsule(val id: String, val title: String, val sealDateMs: Long, val contributions: List<CapsuleContribution>)

    fun createTimeCapsule(context: Context, code: String, title: String, sealDateMs: Long): String? {
        if (!isAvailable(context)) return null
        return try {
            val ref = db(context).getReference("family_circle/${circleKey(code)}/capsules").push()
            ref.setValue(mapOf("title" to title.take(200), "sealDateMs" to sealDateMs, "createdAt" to ServerValue.TIMESTAMP))
            ref.key
        } catch (e: Exception) {
            Log.w(TAG, "createTimeCapsule failed: ${e.message}")
            null
        }
    }

    /** Anyone in the circle can add their own contribution any time before OR after the seal
     *  date — the seal date controls when the capsule is presented as "ready to open" in the
     *  UI ([ui.TimeCapsuleScreen]), it isn't a write-lock enforced here. Simpler and more
     *  forgiving than a hard lock: a family member who's late doesn't get silently blocked. */
    fun addCapsuleContribution(context: Context, code: String, capsuleId: String, installId: String, nickname: String, text: String) {
        if (!isAvailable(context)) return
        try {
            db(context).getReference("family_circle/${circleKey(code)}/capsules/$capsuleId/contributions/$installId")
                .setValue(mapOf("nickname" to nickname, "text" to text.take(1000), "timestampMs" to ServerValue.TIMESTAMP))
        } catch (e: Exception) {
            Log.w(TAG, "addCapsuleContribution failed: ${e.message}")
        }
    }

    fun observeTimeCapsules(context: Context, code: String, onUpdate: (List<TimeCapsule>) -> Unit) {
        if (!isAvailable(context) || code.isBlank()) {
            onUpdate(emptyList())
            return
        }
        val ref = db(context).getReference("family_circle/${circleKey(code)}/capsules")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val capsules = snapshot.children.mapNotNull { child ->
                    val title = child.child("title").getValue(String::class.java) ?: return@mapNotNull null
                    val sealDateMs = child.child("sealDateMs").getValue(Long::class.java) ?: return@mapNotNull null
                    val contributions = child.child("contributions").children.mapNotNull { c ->
                        val nickname = c.child("nickname").getValue(String::class.java) ?: return@mapNotNull null
                        val text = c.child("text").getValue(String::class.java) ?: return@mapNotNull null
                        val ts = c.child("timestampMs").getValue(Long::class.java) ?: 0L
                        CapsuleContribution(c.key ?: "", nickname, text, ts)
                    }
                    TimeCapsule(child.key ?: "", title, sealDateMs, contributions)
                }.sortedBy { it.sealDateMs }
                onUpdate(capsules)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "observeTimeCapsules failed: ${error.message}")
                onUpdate(emptyList())
            }
        })
    }
}

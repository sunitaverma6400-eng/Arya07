package com.arya.ai.util

import android.content.Context
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Backup/Restore — everything built across Antargati/Skill Mirror/Memory Continuity/Life
 * Simulator/Family Circle/Legacy Space lives ONLY in local SharedPreferences today (see each
 * store's own `prefs(context)` function). That's fine for privacy, but it also means losing
 * or resetting the phone loses years of family memories, decisions, and voice-clone IDs with
 * no way back. This treats each store as an opaque key-value bag (doesn't need to know their
 * internal JSON schemas) and round-trips every key through one backup file.
 *
 * Deliberately does NOT touch [com.arya.ai.util.FirebaseSync]'s Family Circle data — that
 * already lives on Firebase's servers, re-fetched fresh on next login, not local-only state
 * that would otherwise be lost.
 */
object BackupManager {

    const val BACKUP_VERSION = 1

    /** (prefs file name, isEncrypted) — isEncrypted tells us whether to read/write it via
     *  [SecurePrefs] or plain [Context.getSharedPreferences]; get this wrong for one store and
     *  that store silently backs up empty, so it's listed explicitly per-store rather than
     *  guessed. */
    private val STORES = listOf(
        "arya_priority_tracker" to false,
        "arya_family_memories" to true,
        "arya_decision_log" to true,
        "arya_family_circle" to true,
        "arya_future_letters" to true,
        "arya_legacy_mode" to true,
        "arya_skill_coach_log" to true,
        "arya_tool_memory" to true
    )

    private fun prefsFor(context: Context, name: String, encrypted: Boolean) =
        if (encrypted) SecurePrefs.get(context, name) else context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Where [com.arya.ai.tools.FamilyMemoryStore.savePhoto] puts memory photos — kept as one
     *  named constant here too so export/restore and the store itself can never drift apart on
     *  the folder name. */
    private fun photosDir(context: Context) = File(context.filesDir, "family_memory_photos")

    fun exportAll(context: Context): JSONObject {
        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("exportedAtMs", System.currentTimeMillis())
        val stores = JSONObject()
        STORES.forEach { (name, encrypted) ->
            val storeJson = JSONObject()
            prefsFor(context, name, encrypted).all.forEach { (key, value) ->
                when (value) {
                    is String -> storeJson.put(key, value)
                    is Boolean -> storeJson.put(key, value)
                    is Int -> storeJson.put(key, value)
                    is Long -> storeJson.put(key, value)
                    is Float -> storeJson.put(key, value.toDouble())
                    // Sets aren't used by any of these stores today, but skip rather than
                    // crash if one shows up later — a missing key on restore beats a failed
                    // backup of everything else.
                    else -> {}
                }
            }
            stores.put(name, storeJson)
        }
        root.put("stores", stores)

        // Bug fix (see chat history — memory photos weren't included, so restoring on a new
        // phone brought back the memory TEXT but broken/missing photos): each photo file gets
        // base64-encoded into "photos", keyed by its absolute path exactly as stored in that
        // memory's `photoPath` field, so restore can write it back to the same path
        // FamilyMemory records already point to — no change needed to how photos are read
        // elsewhere in the app. Known limitation, stated plainly: this assumes the restore
        // happens into the same app package's files dir layout (true for "same phone after a
        // reset" and "reinstall", not guaranteed across every possible device/Android version
        // combination) — if a path genuinely doesn't resolve after restore, that one photo is
        // skipped (see [importAll]) rather than corrupting the rest of the restore.
        val dir = photosDir(context)
        if (dir.exists()) {
            val photos = JSONObject()
            dir.listFiles()?.forEach { file ->
                try {
                    val bytes = file.readBytes()
                    photos.put(file.absolutePath, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                } catch (e: Exception) {
                    // skip this one photo, keep backing up the rest
                }
            }
            root.put("photos", photos)
        }
        return root
    }

    /** Writes the backup JSON to a shareable file (same cache/exports + FileProvider pattern
     *  as [ExportHelper]), so the person can save it to Drive/Files/WhatsApp-to-self via the
     *  normal Android share sheet — no new permissions needed. */
    fun createBackupFile(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "arya_backup_${System.currentTimeMillis()}.json")
        file.writeText(exportAll(context).toString(2))
        return file
    }

    fun shareBackupFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Backup save karo"))
    }

    /** Restores every store found in [json]. Existing local data for a store is fully
     *  overwritten by the backup's copy of that same store (not merged) — a backup is a
     *  snapshot from a specific moment, and merging risks silently reviving something the
     *  person deliberately deleted since. Returns a human-readable summary for the screen to
     *  show; never throws — a store that fails to parse is skipped, not fatal to the rest. */
    fun importAll(context: Context, json: JSONObject): String {
        val stores = json.optJSONObject("stores")
            ?: return "❌ Ye backup file Arya ki nahi lagti (format samajh nahi aaya)"
        var restoredStores = 0
        var restoredKeys = 0
        STORES.forEach { (name, encrypted) ->
            val storeJson = stores.optJSONObject(name) ?: return@forEach
            try {
                val prefs = prefsFor(context, name, encrypted)
                val editor = prefs.edit().clear()
                storeJson.keys().forEach { key ->
                    when (val value = storeJson.get(key)) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                    }
                    restoredKeys++
                }
                editor.apply()
                restoredStores++
            } catch (e: Exception) {
                // skip this one store, keep restoring the rest
            }
        }
        val whenBackedUp = json.optLong("exportedAtMs", 0L)

        // Restore photos back to their original absolute paths (see exportAll's doc comment
        // on this approach and its stated limitation). Done after the stores loop so a photo
        // failure never blocks the text data — memories/decisions/etc — from restoring.
        var restoredPhotos = 0
        json.optJSONObject("photos")?.let { photos ->
            photos.keys().forEach { path ->
                try {
                    val bytes = android.util.Base64.decode(photos.getString(path), android.util.Base64.NO_WRAP)
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    restoredPhotos++
                } catch (e: Exception) {
                    // skip this one photo, keep restoring the rest
                }
            }
        }

        val dateLine = if (whenBackedUp > 0) {
            val fmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.US)
            " ($restoredKeys cheezein, $restoredPhotos photos, ${fmt.format(java.util.Date(whenBackedUp))} ki backup se)"
        } else ""
        return "✅ $restoredStores stores restore ho gaye$dateLine"
    }
}

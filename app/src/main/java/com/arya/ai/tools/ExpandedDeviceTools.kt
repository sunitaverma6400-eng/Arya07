package com.arya.ai.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Second batch of device tools, added on top of the original ~50-tool port
 * ([UtilityTools]/[InfoApiTools]/[WebTools]/[DeviceExtraTools]/[MemoryStore]/[PersonaStore]).
 *
 * Same safety stance as the rest of `tools/`: nothing here silently sends data anywhere
 * or acts on a contact without the user seeing a system UI first. Contacts lookup needs
 * `READ_CONTACTS` (declared in the manifest, requested at runtime same as the other
 * dangerous permissions) — if it's not granted, the tool just says so instead of crashing.
 */
object ExpandedDeviceTools {

    // -- contacts --------------------------------------------------------

    private fun hasContactsPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Looks up a contact's phone number by (partial, case-insensitive) display name. */
    fun findContactNumber(context: Context, name: String): String {
        if (!hasContactsPermission(context)) return "❌ Contacts permission nahi di gayi."
        if (name.isBlank()) return "❌ Contact ka naam do."

        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")

        resolver.query(uri, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val foundName = cursor.getString(0)
                val number = cursor.getString(1)
                return "📇 $foundName: $number"
            }
        }
        return "❌ '$name' naam ka koi contact nahi mila."
    }

    /** Calls a saved contact by name — resolves the number, then opens the dialer pre-filled
     *  (same CALL_PHONE-avoidance stance as [DeviceExtraTools.makeCall]). */
    fun callContactByName(context: Context, name: String): String {
        if (!hasContactsPermission(context)) return "❌ Contacts permission nahi di gayi."
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        resolver.query(uri, projection, selection, arrayOf("%$name%"), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val number = cursor.getString(0)
                val foundName = cursor.getString(1)
                return DeviceExtraTools.makeCall(context, number).let {
                    "$it (contact: $foundName)"
                }
            }
        }
        return "❌ '$name' naam ka koi contact nahi mila."
    }

    // -- clipboard ---------------------------------------------------------

    fun readClipboard(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return "📋 Clipboard khaali hai."
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        return if (text.isNullOrBlank()) "📋 Clipboard khaali hai." else "📋 $text"
    }

    fun writeClipboard(context: Context, text: String): String {
        if (text.isBlank()) return "❌ Clipboard me likhne ke liye text do."
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Arya", text))
        return "📋 Clipboard me copy kar diya."
    }

    // -- calendar ------------------------------------------------------

    /**
     * Drafts a calendar event via [CalendarContract]'s `ACTION_INSERT` intent — opens the
     * user's calendar app pre-filled for them to confirm and save, exactly the same
     * "open a system UI, don't act silently" pattern as `make_call`/`send_sms`. This means
     * no `WRITE_CALENDAR` runtime permission is needed at all.
     *
     * @param startMillis event start time, epoch millis
     * @param durationMinutes defaults to 60
     */
    fun createCalendarEvent(
        context: Context,
        title: String,
        startMillis: Long,
        durationMinutes: Int = 60,
        description: String = ""
    ): String {
        if (title.isBlank()) return "❌ Event ka title do."
        if (startMillis <= 0) return "❌ Event ka start time (epoch millis) do."

        val endMillis = startMillis + durationMinutes * 60_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            "📅 Calendar app khol di '$title' pre-filled ke saath — confirm karke save karo."
        } catch (e: Exception) {
            "⚠️ Calendar app nahi khul payi: ${e.message}"
        }
    }
}

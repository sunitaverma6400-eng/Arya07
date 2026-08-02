package com.arya.ai.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.arya.ai.MainActivity

/**
 * Transparent, no-UI activity that the home-screen widget taps into. Simply routes to the
 * app's single (Compose) entry point and finishes itself — the Chat tab is already one swipe
 * away from the Gallery tab there, and every session (including ones started from the widget)
 * now shows up in Menu -> Chat History regardless of how it was opened.
 *
 * (Previously this queried the Room DB directly to decide between opening the classic
 * `ChatActivity` or `HomeActivity` — both of which are gone now that the app is Compose-only,
 * so this got a lot simpler.)
 */
class WidgetLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        finish()
    }
}

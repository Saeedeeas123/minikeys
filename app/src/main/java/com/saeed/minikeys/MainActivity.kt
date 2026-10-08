package com.saeed.minikeys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.saeed.minikeys.ui.MiniKeysSettingsScreen
import com.saeed.minikeys.ui.SettingsScreenState

/**
 * Settings surface for the keyboard module. Also the target declared in the IME descriptor
 * (res/xml/method.xml), so the system gear icon next to the keyboard opens this activity.
 */
class MainActivity : ComponentActivity() {

    private lateinit var state: SettingsScreenState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = SettingsScreenState(this)

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MiniKeysSettingsScreen(state = state)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system keyboard settings may have changed enable/selected state, and the
        // stored values are the truth — re-read both.
        state.refreshImeStatus()
        state.refreshFromDisk()
    }
}
package io.github.thegingerbehindu.rehabtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import io.github.thegingerbehindu.rehabtracker.data.RehabDatabase
import io.github.thegingerbehindu.rehabtracker.ui.AppRoot
import io.github.thegingerbehindu.rehabtracker.ui.RehabTheme
import io.github.thegingerbehindu.rehabtracker.ui.SetupTestScreen

class MainActivity : ComponentActivity() {
    private val resumeCount = mutableIntStateOf(0)
    private val openCheckInToken = mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val dao = RehabDatabase.get(this).dao()
        setContent {
            RehabTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(
                        dao = dao,
                        resumeCount = resumeCount.intValue,
                        openCheckInToken = openCheckInToken.intValue,
                        setupTests = { onBack -> SetupTestScreen(resumeCount.intValue, onBack) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue += 1
    }

    private fun readIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Alarms.EXTRA_OPEN_CHECK_IN, false) != true) return
        intent.removeExtra(Alarms.EXTRA_OPEN_CHECK_IN)
        openCheckInToken.intValue += 1
        Diagnostics.log(this, "Notification tap opened the check-in")
    }
}

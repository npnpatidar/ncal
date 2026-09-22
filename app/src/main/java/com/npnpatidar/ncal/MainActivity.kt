package com.npnpatidar.ncal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.ui.TapeScreen
import com.npnpatidar.ncal.ui.TapeViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: the documented prerequisite for reliable IME insets,
        // so the pinned strip can ride exactly above the system keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Crash handler first: any fatal after this point lands in
        // Download/ncal/crash-<ts>.log with the full stack trace.
        NcalLogger.installCrashHandler()
        NcalLogger.init(this)
        NcalLogger.i("App", "onCreate")
        try {
            setContent { TapeScreen() }
        } catch (t: Throwable) {
            NcalLogger.e("App", "setContent failed", t)
            throw t
        }
    }

    override fun onResume() {
        super.onResume()
        NcalLogger.d("App", "onResume")
    }

    override fun onPause() {
        NcalLogger.d("App", "onPause")
        try {
            ViewModelProvider(this)[TapeViewModel::class.java].flushNow()
        } catch (t: Throwable) {
            NcalLogger.e("App", "flush on pause failed", t)
        }
        super.onPause()
    }
}

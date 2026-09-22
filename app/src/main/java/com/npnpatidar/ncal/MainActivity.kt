package com.npnpatidar.ncal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.ui.TapeScreen

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
        @Suppress("DEPRECATION")
        val pkg = packageManager.getPackageInfo(packageName, 0)
        NcalLogger.i("App", "v${pkg.versionName} (${pkg.versionCode}) onCreate")
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
        super.onPause()
    }
}

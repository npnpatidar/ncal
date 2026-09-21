package com.npnpatidar.ncal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.ui.TapeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NcalLogger.init(this)
        NcalLogger.i("App", "onCreate")
        setContent { TapeScreen() }
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

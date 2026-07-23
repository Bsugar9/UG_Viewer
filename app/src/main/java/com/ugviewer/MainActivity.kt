package com.ugviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ugviewer.ui.AdaptiveMainScreen
import com.ugviewer.ui.theme.UGViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UGViewerTheme {
                AdaptiveMainScreen()
            }
        }
    }
}

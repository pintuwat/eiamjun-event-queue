package com.eiamjun.event

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.eiamjun.event.ui.nav.AppNav
import com.eiamjun.event.ui.theme.EiamJunEventTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EiamJunEventTheme {
                Surface(Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }
}

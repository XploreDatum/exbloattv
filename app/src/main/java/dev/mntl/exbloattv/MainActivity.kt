package dev.mntl.exbloattv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mntl.exbloattv.ui.nav.ExBloatNavGraph
import dev.mntl.exbloattv.ui.theme.ExBloatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ExBloatApp).graph
        setContent {
            ExBloatTheme {
                ExBloatNavGraph(graph)
            }
        }
    }
}

package io.github.takusan23.androidbleanduwbsample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControleeScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControllerScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.HomeScreen
import io.github.takusan23.androidbleanduwbsample.ui.theme.AndroidBleAndUwbSampleTheme

class MainActivity : ComponentActivity() {

    // 画面間で共有するためCompanion Objectで保持（簡易的な実装）
    companion object {
        lateinit var bleManager: BleManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // BleManagerの初期化 (通信ロジック本体)
        bleManager = BleManager(this)

        enableEdgeToEdge()
        setContent {
            AndroidBleAndUwbSampleTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.release()
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onControllerClick = { navController.navigate("controller") },
                onControleeClick = { navController.navigate("controlee") }
            )
        }
        composable("controller") {
            ControllerScreen()
        }
        composable("controlee") {
            ControleeScreen()
        }
    }
}
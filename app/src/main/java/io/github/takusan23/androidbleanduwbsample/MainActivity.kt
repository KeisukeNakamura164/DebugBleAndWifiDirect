package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControleeScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControllerScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.HomeScreen
import io.github.takusan23.androidbleanduwbsample.ui.theme.AndroidBleAndUwbSampleTheme

class MainActivity : ComponentActivity() {
    // aware追加分
    companion object {
        lateinit var awareManager: AwareManager
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // aware追加分
        awareManager = AwareManager(this)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidBleAndUwbSampleTheme {
                MainScreen(awareManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        awareManager.close()
    }
}

@Composable
private fun MainScreen(awareManager: AwareManager) {
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
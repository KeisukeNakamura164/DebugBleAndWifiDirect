package io.github.takusan23.androidbleanduwbsample.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.takusan23.androidbleanduwbsample.UwbControllerParams
import io.github.takusan23.androidbleanduwbsample.ble.BleCentral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControleeScreen(
    onGetLocalAddress: suspend (Boolean) -> ByteArray, // ★追加
    onStartUwb: (UwbControllerParams, ByteArray?, Boolean) -> Unit,
    currentDistance: String,
    onSwitchToP2p: () -> Unit
) {
    val context = LocalContext.current
    var uwbControllerParams by remember { mutableStateOf<UwbControllerParams?>(null) }

    LaunchedEffect(key1 = Unit) {
        // ★修正: MainActivityからアドレスをもらう (false = Controlee)
        val myAddress = onGetLocalAddress(false)

        val bleCentral = BleCentral(context)
        bleCentral.connectGattServer()
        val uwbControllerParamsByteArray = bleCentral.readCharacteristic()

        if (uwbControllerParamsByteArray.isNotEmpty()) {
            val params = UwbControllerParams.decode(uwbControllerParamsByteArray)
            uwbControllerParams = params

            // ★修正: 正しいアドレスを親機に送信
            bleCentral.writeCharacteristic(myAddress)
            bleCentral.destroy()

            // 親にUWB開始を依頼
            onStartUwb(params, null, false)
        } else {
            bleCentral.destroy()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("UWB Controlee") }) }) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {

            Text(text = "距離 = $currentDistance", modifier = Modifier.padding(16.dp))

            Button(
                onClick = {
                    onSwitchToP2p()
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Text("Wi-Fi Direct を起動する")
            }
        }
    }
}
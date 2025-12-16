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
import io.github.takusan23.androidbleanduwbsample.ble.BlePeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(
    onGetLocalAddress: suspend (Boolean) -> ByteArray, // ★追加: アドレス請求権
    onStartUwb: (UwbControllerParams, ByteArray?, Boolean) -> Unit,
    currentDistance: String,
    onSwitchToP2p: () -> Unit
) {
    val context = LocalContext.current

    var uwbControllerParams by remember { mutableStateOf<UwbControllerParams?>(null) }
    var controleeAddress by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(key1 = Unit) {
        // ★修正: 自分でマネージャを作らず、MainActivityからアドレスをもらう！
        // これにより MainActivity 側のセッションと同一のアドレスが保証される
        val myAddress = onGetLocalAddress(true) // true = Controller

        val sessionId = Random.nextInt()
        val sessionKeyInfo = Random.nextBytes(8)
        val params = UwbControllerParams(
            address = myAddress, // ★ここが重要！
            channel = 9, // チャンネル等は固定または定数から
            preambleIndex = 10,
            sessionId = sessionId,
            sessionKeyInfo = sessionKeyInfo
        )
        uwbControllerParams = params

        val encodeHostParameter = UwbControllerParams.encode(params)
        val controleeAddressFlow = MutableStateFlow<ByteArray?>(null)

        val peripheralJob = launch {
            BlePeripheral.startPeripheralAndAdvertising(
                context = context,
                onCharacteristicReadRequest = { encodeHostParameter },
                onCharacteristicWriteRequest = { controleeAddressFlow.value = it }
            )
        }

        val address = controleeAddressFlow.filterNotNull().first()
        controleeAddress = address
        peripheralJob.cancel()

        // 親にUWB開始を依頼
        onStartUwb(params, address, true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("UWB Controller") }) }) { innerPadding ->
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
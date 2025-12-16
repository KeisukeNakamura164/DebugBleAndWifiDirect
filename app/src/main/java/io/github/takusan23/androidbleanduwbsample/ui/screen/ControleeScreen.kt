package io.github.takusan23.androidbleanduwbsample.ui.screen

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingPosition
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import io.github.takusan23.androidbleanduwbsample.UwbControllerParams
import io.github.takusan23.androidbleanduwbsample.ble.BleCentral
import kotlinx.coroutines.launch

// Wifi aware
import io.github.takusan23.androidbleanduwbsample.MainActivity
import io.github.takusan23.androidbleanduwbsample.MessageCard

private val REQUIRED_PERMISSION = listOf(
    android.Manifest.permission.BLUETOOTH,
    android.Manifest.permission.BLUETOOTH_CONNECT,
    android.Manifest.permission.BLUETOOTH_SCAN,
    android.Manifest.permission.BLUETOOTH_ADVERTISE,
    android.Manifest.permission.ACCESS_COARSE_LOCATION,
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.UWB_RANGING,
    android.Manifest.permission.NEARBY_WIFI_DEVICES
)

/** Controlee(Guest) 側の画面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControleeScreen() {
    val context = LocalContext.current

    val isGranted = remember {
        mutableStateOf(REQUIRED_PERMISSION.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }

    val awareManager = MainActivity.awareManager

    // controller の位置
    val uwbPosition = remember { mutableStateOf<RangingPosition?>(null) }

    LaunchedEffect(key1 = Unit) {
        val uwbManager = UwbManager.createInstance(context)
        val controleeSession = uwbManager.controleeSessionScope()

        // controller に送る
        val addressByteArray = controleeSession.localAddress.address

        // BLE GATT サーバーへ接続し、UWB ホストと接続に必要なパラメーターを送受信する
        val bleCentral = BleCentral(context)
        bleCentral.connectGattServer()
        val uwbControllerParamsByteArray = bleCentral.readCharacteristic()

        if (uwbControllerParamsByteArray.isEmpty()) {
            println("エラー: BLEからデータを受け取れませんでした")
            bleCentral.destroy()
            return@LaunchedEffect
        }

        val uwbControllerParams = UwbControllerParams.decode(uwbControllerParamsByteArray)
        bleCentral.writeCharacteristic(addressByteArray)
        bleCentral.destroy()

        // RangingParameters を作り UWB 接続を開始する
        val rangingParameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            complexChannel = UwbComplexChannel(uwbControllerParams.channel, uwbControllerParams.preambleIndex),
            peerDevices = listOf(UwbDevice.createForAddress(uwbControllerParams.address)),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
            sessionId = uwbControllerParams.sessionId,
            sessionKeyInfo = uwbControllerParams.sessionKeyInfo,
            subSessionId = 0,
            subSessionKeyInfo = null
        )

        launch {
            controleeSession.prepareSession(rangingParameters).collect { rangingResult ->
                when (rangingResult) {
                    is RangingResult.RangingResultPosition -> {
                        uwbPosition.value = rangingResult.position
                    }

                    is RangingResult.RangingResultPeerDisconnected -> {
                        uwbPosition.value = null
                    }
                }
            }
        }
    }

    // --- Wi-Fi Aware UI Logic ---

    // 受信メッセージを保存するリスト
    val messages = remember { mutableStateListOf<String>() }

    // AwareManagerからの通知設定
    LaunchedEffect(Unit) {
        awareManager.onMessageReceivedListener = { message ->
            messages.add(0, message)
        }
    }

    // 権限リクエストランチャー
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            isGranted.value = true
            Toast.makeText(context, "権限が許可されました", Toast.LENGTH_SHORT).show()
            // 権限許可後に自動開始したい場合はここで呼ぶ
            awareManager.connect()
            awareManager.startDiscovery()
            messages.add(0, "システム: 通信を開始しました")
        } else {
            Toast.makeText(context, "権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // ★修正: Action引数を削除し、単一のDiscovery開始処理へ変更
    fun checkPermissionsAndRun() {
        if (isGranted.value) {
            awareManager.connect()
            awareManager.startDiscovery() // ★ここを startDiscovery に変更
            messages.add(0, "システム: 通信を開始しました")
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSION.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "UWB Controlee") })
        }
    ) { innerPadding ->
        // LazyColumn全体でスクロールさせる（ネストスクロール回避のため）
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // --- UWB Section ---
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "距離 = ${uwbPosition.value?.distance?.value} m")
                    val distanceValue = uwbPosition.value?.distance?.value
                    if (distanceValue != null) {
                        if (distanceValue <= 3) Text(text = "範囲内")
                    }
                }
                Divider()
            }

            // --- Wi-Fi Aware Section ---
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Wi-Fi Aware 通信ログ",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // ★修正: ボタンを1つに統合
                    Button(
                        onClick = { checkPermissionsAndRun() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("双方向通信を開始 (Discovery)")
                    }
                }
            }

            // --- Log Section ---
            items(messages) { msg ->
                // 余白をつけるためにBox等でラップしても良いが、シンプルに呼び出し
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    MessageCard(msg)
                }
            }
        }
    }
}
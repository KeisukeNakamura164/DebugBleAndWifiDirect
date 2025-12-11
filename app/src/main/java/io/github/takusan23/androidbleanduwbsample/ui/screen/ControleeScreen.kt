package io.github.takusan23.androidbleanduwbsample.ui.screen

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.uwb.*
import io.github.takusan23.androidbleanduwbsample.MainActivity
import io.github.takusan23.androidbleanduwbsample.MessageCard
import io.github.takusan23.androidbleanduwbsample.UwbControllerParams
import io.github.takusan23.androidbleanduwbsample.ble.BleCentral
import kotlinx.coroutines.launch

private val REQUIRED_PERMISSION = listOf(
    android.Manifest.permission.BLUETOOTH_CONNECT,
    android.Manifest.permission.BLUETOOTH_SCAN,
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.UWB_RANGING
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControleeScreen() {
    val context = LocalContext.current
    val bleManager = MainActivity.bleManager

    // UWBの状態
    var uwbDistanceText by remember { mutableStateOf("未接続") }
    var currentDistanceMeters by remember { mutableStateOf<Float?>(null) }

    // ログ
    val experimentLogs = remember { mutableStateListOf<String>() }

    // BLEの状態監視
    val receivedMessage by bleManager.receivedMessage.collectAsState()
    val isScanning by bleManager.isScanning.collectAsState()

    // 権限
    val isGranted = remember {
        mutableStateOf(REQUIRED_PERMISSION.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        isGranted.value = result.values.all { it }
    }

    // UWB初期化
    LaunchedEffect(key1 = Unit) {
        if (!isGranted.value) return@LaunchedEffect

        try {
            val uwbManager = UwbManager.createInstance(context)
            val controleeSession = uwbManager.controleeSessionScope()

            // Setup用BLE (BleCentral) 開始
            val bleCentral = BleCentral(context)
            bleCentral.connectGattServer()

            // パラメータ取得
            val uwbControllerParamsByteArray = bleCentral.readCharacteristic()
            val uwbControllerParams = UwbControllerParams.decode(uwbControllerParamsByteArray)

            // 自分のアドレスを通知
            bleCentral.writeCharacteristic(controleeSession.localAddress.address)
            bleCentral.destroy() // Setup完了

            // UWB Ranging
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
                controleeSession.prepareSession(rangingParameters).collect { result ->
                    when (result) {
                        is RangingResult.RangingResultPosition -> {
                            result.position.distance?.value?.let { dist ->
                                currentDistanceMeters = dist
                                uwbDistanceText = "%.2fm".format(dist)
                            }
                        }
                        is RangingResult.RangingResultPeerDisconnected -> {
                            uwbDistanceText = "切断"
                            currentDistanceMeters = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            uwbDistanceText = "エラー: ${e.message}"
        }
    }

    // メッセージ受信検知（Controllerからの返信）
    LaunchedEffect(receivedMessage) {
        if (receivedMessage.isNotEmpty()) {
            val distStr = currentDistanceMeters?.let { "%.2fm".format(it) } ?: "不明"
            val log = "返信あり: $receivedMessage\n(距離: $distStr)"

            experimentLogs.add(0, log)

            // ★重要: 受信済みとしてクリアする
            //bleManager.clearMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Controlee (Guest)") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // UWBステータス
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("UWB 距離計測", fontSize = 14.sp)
                    Text(uwbDistanceText, fontSize = 32.sp, style = MaterialTheme.typography.headlineMedium)
                }
            }

            // 操作ボタン
            if (!isGranted.value) {
                Button(onClick = { permissionLauncher.launch(REQUIRED_PERMISSION.toTypedArray()) }) {
                    Text("権限を許可する")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // スキャン開始 -> 見つかれば接続 -> 送信 -> 受信 -> 切断 まで自動
                            bleManager.startScan()
                            //bleManager.setCustomMessage("距離測定テスト: ${uwbDistanceText}")
                            experimentLogs.add(0, "システム: 計測(スキャン)を開始しました...")
                        },
                        enabled = !isScanning
                    ) {
                        Text(if (isScanning) "計測中..." else "計測開始 (Scan)")
                    }

                    if (isScanning) {
                        Button(
                            onClick = {
                                bleManager.stopScan()
                                experimentLogs.add(0, "システム: 中断しました")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("中断")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))
            Text("通信ログ", fontSize = 18.sp, modifier = Modifier.align(Alignment.Start))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp)
            ) {
                items(experimentLogs) { log ->
                    MessageCard(text = log)
                }
            }
        }
    }
}
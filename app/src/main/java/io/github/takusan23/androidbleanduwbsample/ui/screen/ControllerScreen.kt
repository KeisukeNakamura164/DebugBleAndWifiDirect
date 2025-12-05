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
import io.github.takusan23.androidbleanduwbsample.ble.BlePeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

private val REQUIRED_PERMISSION = listOf(
    android.Manifest.permission.BLUETOOTH_CONNECT,
    android.Manifest.permission.BLUETOOTH_SCAN,
    android.Manifest.permission.BLUETOOTH_ADVERTISE,
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.UWB_RANGING
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen() {
    val context = LocalContext.current
    val bleManager = MainActivity.bleManager
    val scope = rememberCoroutineScope()

    // UWBの状態
    var uwbDistanceText by remember { mutableStateOf("未接続") }
    var currentDistanceMeters by remember { mutableStateOf<Float?>(null) } // ログ用に数値を保持

    // ログ表示用 (メッセージ + 受信時の距離)
    val experimentLogs = remember { mutableStateListOf<String>() }

    // BLEの状態監視
    val receivedMessage by bleManager.receivedMessage.collectAsState()
    val isAdvertising by bleManager.isAdvertising.collectAsState()

    // 権限チェック
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

    // UWBの初期化処理
    LaunchedEffect(key1 = Unit) {
        if (!isGranted.value) return@LaunchedEffect

        try {
            val uwbManager = UwbManager.createInstance(context)
            val controllerSession = uwbManager.controllerSessionScope()

            val sessionId = Random.nextInt()
            val sessionKeyInfo = Random.nextBytes(8)
            val uwbControllerParams = UwbControllerParams(
                address = controllerSession.localAddress.address,
                channel = controllerSession.uwbComplexChannel.channel,
                preambleIndex = controllerSession.uwbComplexChannel.preambleIndex,
                sessionId = sessionId,
                sessionKeyInfo = sessionKeyInfo
            )
            val encodeHostParameter = UwbControllerParams.encode(uwbControllerParams)
            val controleeAddressFlow = MutableStateFlow<ByteArray?>(null)

            // Setup用BLE (BlePeripheral) 開始
            val peripheralJob = launch {
                BlePeripheral.startPeripheralAndAdvertising(
                    context = context,
                    onCharacteristicReadRequest = { encodeHostParameter },
                    onCharacteristicWriteRequest = {
                        println("Received Address via Setup BLE")
                        controleeAddressFlow.value = it
                    }
                )
            }

            // 相手のアドレス待ち
            val controleeAddress = controleeAddressFlow.filterNotNull().first()
            peripheralJob.cancel() // Setup完了したらSetup用BLEは止める

            // UWB Ranging開始
            val rangingParameters = RangingParameters(
                uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
                complexChannel = controllerSession.uwbComplexChannel,
                peerDevices = listOf(UwbDevice.createForAddress(controleeAddress)),
                updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                sessionId = sessionId,
                sessionKeyInfo = sessionKeyInfo,
                subSessionId = 0,
                subSessionKeyInfo = null
            )

            launch {
                controllerSession.prepareSession(rangingParameters).collect { result ->
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

        // メッセージ受信検知（ログ追加）
    LaunchedEffect(receivedMessage) {
        if (receivedMessage.isNotEmpty()) {
            val distStr = currentDistanceMeters?.let { "%.2fm".format(it) } ?: "不明"
            val log = "受信: $receivedMessage\n(距離: $distStr)"

            // ログリストの先頭に追加
            experimentLogs.add(0, log)

            // ★重要: 次のメッセージを受け取れるように、Manager側の変数を空にする
            //bleManager.clearMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Controller (Host)") }) }
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
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
                            // 待受開始
                            bleManager.startAdvertising()
                            Toast.makeText(context, "アドバタイズ開始", Toast.LENGTH_SHORT).show()
                            experimentLogs.add(0, "システム: 待受を開始しました")
                        },
                        enabled = !isAdvertising
                    ) {
                        Text(if (isAdvertising) "待受中..." else "待受開始 (Adv)")
                    }

                    Button(
                        onClick = {
                            bleManager.stopAdvertising()
                            Toast.makeText(context, "停止しました", Toast.LENGTH_SHORT).show()
                            experimentLogs.add(0, "システム: 停止しました")
                        },
                        enabled = isAdvertising,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("停止")
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
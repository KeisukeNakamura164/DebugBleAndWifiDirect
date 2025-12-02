package io.github.takusan23.androidbleanduwbsample.ui.screen

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import io.github.takusan23.androidbleanduwbsample.UwbControllerParams
import io.github.takusan23.androidbleanduwbsample.WifiDirect
import io.github.takusan23.androidbleanduwbsample.ble.BlePeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

// Wifi aware 関連はコメントアウト
// import io.github.takusan23.androidbleanduwbsample.AwareManager
// import io.github.takusan23.androidbleanduwbsample.MainActivity
// import io.github.takusan23.androidbleanduwbsample.MessageCard

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

/** Controller(Host) 側の画面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen() {
    val context = LocalContext.current
    val isGranted = remember {
        mutableStateOf(REQUIRED_PERMISSION.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }

    // AwareManager は使わないのでコメントアウト
    // val awareManager = MainActivity.awareManager

    // ★追加: ボタンから参照できるように状態変数として定義
    var uwbControllerParams by remember { mutableStateOf<UwbControllerParams?>(null) }
    var controleeAddress by remember { mutableStateOf<ByteArray?>(null) }

    // controlee の位置
    val uwbPosition = remember { mutableStateOf<RangingPosition?>(null) }

    LaunchedEffect(key1 = Unit) {
        // controller 側として作成
        val uwbManager = UwbManager.createInstance(context)
        val controllerSession = uwbManager.controllerSessionScope()

        // ゲスト側へ送るパラメーターを ByteArray にして送る
        val sessionId = Random.nextInt()
        val sessionKeyInfo = Random.nextBytes(8)

        // Serializable な data class にして ByteArray にエンコードする
        val params = UwbControllerParams(
            address = controllerSession.localAddress.address,
            channel = controllerSession.uwbComplexChannel.channel,
            preambleIndex = controllerSession.uwbComplexChannel.preambleIndex,
            sessionId = sessionId,
            sessionKeyInfo = sessionKeyInfo
        )
        // ★状態変数に保存 (これでボタンから参照できるようになる)
        uwbControllerParams = params

        // バイト配列に
        val encodeHostParameter = UwbControllerParams.encode(params)

        // Controlee 側からアドレスが送られてきたら入れる Flow
        val controleeAddressFlow = MutableStateFlow<ByteArray?>(null)

        // BLE の開始
        val peripheralJob = launch {
            BlePeripheral.startPeripheralAndAdvertising(
                context = context,
                onCharacteristicReadRequest = {
                    // controlee へ送る
                    encodeHostParameter
                },
                onCharacteristicWriteRequest = {
                    // controlee から受け取る
                    println(it)
                    controleeAddressFlow.value = it
                }
            )
        }

        // アドレスが送られてきたらペリフェラル終了
        val address = controleeAddressFlow.filterNotNull().first()
        // ★状態変数に保存
        controleeAddress = address

        peripheralJob.cancel()

        // RangingParameters を作り UWB 接続を開始する
        val rangingParameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            complexChannel = controllerSession.uwbComplexChannel,
            // ★保存した address を使う
            peerDevices = listOf(UwbDevice.createForAddress(address)),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
            sessionId = sessionId,
            sessionKeyInfo = sessionKeyInfo,
            subSessionId = 0, // SUB_SESSION_UNSET
            subSessionKeyInfo = null // 暗号化の何か
        )
        launch {
            controllerSession.prepareSession(rangingParameters).collect { rangingResult ->
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "UWB Controller") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // null になりえるので注意
            Text(text = "距離 = ${uwbPosition.value?.distance?.value} m")
            val distanceValue = uwbPosition.value?.distance?.value
            if (distanceValue != null) {
                if(distanceValue <= 3) Text(text = "${distanceValue} = 範囲内")
            }

            Button(
                onClick = {
                    // ★修正: 状態変数の中身をローカル変数に取り出す
                    // これでスマートキャストのエラーを防ぎます
                    val params = uwbControllerParams
                    val address = controleeAddress

                    // 中身があるかチェック
                    if (params != null && address != null) {
                        // WifiDirect Activity を起動
                        val intent = Intent(context, WifiDirect::class.java).apply {
                            // 1. Controller用の設定
                            putExtra("UWB_PARAMS", params)
                            // 2. 相手のアドレス
                            putExtra("PEER_ADDRESS", address)
                            // 3. 自分の役割 (ホスト)
                            putExtra("IS_CONTROLLER", true)
                        }
                        context.startActivity(intent)
                    } else {
                        // まだ通信が完了していない場合
                        Toast.makeText(context, "UWB接続準備中です...相手と接続してください", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("Wi-Fi Direct を起動する")
            }
        }
    }

    // --- 以下、Wi-Fi Aware 関連のコードは全てコメントアウト ---
    /*
    val messages = remember { mutableStateListOf<String>() }

    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { isGranted.value = it.all { it.value } }
    )

    LaunchedEffect(Unit) {
        awareManager.onMessageReceivedListener = { message ->
            messages.add(0, message)
        }
    }

    var pendingAction by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(context, "権限が許可されました", Toast.LENGTH_SHORT).show()
            when (pendingAction) {
                "Publish" -> {
                    awareManager.connect()
                    awareManager.startPublishing()
                    messages.add(0, "システム: Publishを開始しました")
                }
                "Subscribe" -> {
                    awareManager.connect()
                    awareManager.startSubscribing()
                    messages.add(0, "システム: Subscribeを開始しました")
                }
            }
        } else {
            Toast.makeText(context, "権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    fun checkPermissionsAndRun(action: String) {
        if (isGranted.value) {
            awareManager.connect()
            if (action == "Publish") {
                awareManager.startPublishing()
                messages.add(0, "システム: Publishを開始しました")
            } else {
                awareManager.startSubscribing()
                messages.add(0, "システム: Subscribeを開始しました")
            }
        } else {
            pendingAction = action
            permissionRequest.launch(REQUIRED_PERMISSION.toTypedArray())
            permissionLauncher.launch(REQUIRED_PERMISSION.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Wi-Fi Aware 通信ログ",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 操作ボタンエリア
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { checkPermissionsAndRun("Publish") }) {
                Text("発信 (Pub)")
            }
            Button(onClick = { checkPermissionsAndRun("Subscribe") }) {
                Text("探索 (Sub)")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(8.dp))

        // ★メッセージ表示エリア (スクロール可能)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                MessageCard(msg)
            }
        }
    }
    */
}
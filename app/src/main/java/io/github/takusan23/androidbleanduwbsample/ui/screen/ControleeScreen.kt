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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import io.github.takusan23.androidbleanduwbsample.AwareManager
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

    val awareManager = MainActivity.awareManager//= remember { AwareManager(context)}

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

        // ★追加: データが空なら何もしない（ここで落ちていました）
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
            subSessionId = 0, // SESSION_ID_UNSET ？
            subSessionKeyInfo = null // ？
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "UWB Controlee") })
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
                if (distanceValue <= 3) Text(text = "範囲内")
            }
        }
    }



    // ★受信メッセージを保存するリスト（状態）
    // これに要素が追加されると、UIが自動的に更新されます
    val messages = remember { mutableStateListOf<String>() }

    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { isGranted.value = it.all { it.value } }
    )

    // 画面が表示されたときに、AwareManagerからの通知を受け取る設定をする
    LaunchedEffect(Unit) {
        awareManager.onMessageReceivedListener = { message ->
            // メインスレッド以外から呼ばれる可能性を考慮して念のため
            messages.add(0, message) // 新しいメッセージを上に追加
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
        pendingAction = null
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
            //permissionRequest.launch(REQUIRED_PERMISSION.toTypedArray())
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
}
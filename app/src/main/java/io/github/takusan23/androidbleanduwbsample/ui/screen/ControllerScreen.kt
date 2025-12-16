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
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import io.github.takusan23.androidbleanduwbsample.UwbControllerParams
import io.github.takusan23.androidbleanduwbsample.ble.BlePeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

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

/** Controller(Host) 側の画面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen() {
    val context = LocalContext.current
    val isGranted = remember {
        mutableStateOf(REQUIRED_PERMISSION.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }

    val awareManager = MainActivity.awareManager

    // controlee の位置
    val uwbPosition = remember { mutableStateOf<RangingPosition?>(null) }

    LaunchedEffect(key1 = Unit) {
        // controller 側として作成
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

        val peripheralJob = launch {
            BlePeripheral.startPeripheralAndAdvertising(
                context = context,
                onCharacteristicReadRequest = { encodeHostParameter },
                onCharacteristicWriteRequest = {
                    println(it)
                    controleeAddressFlow.value = it
                }
            )
        }

        val controleeAddress = controleeAddressFlow.filterNotNull().first()
        peripheralJob.cancel()

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

    // --- Wi-Fi Aware UI Logic ---

    val messages = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        awareManager.onMessageReceivedListener = { message ->
            messages.add(0, message)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            isGranted.value = true
            Toast.makeText(context, "権限が許可されました", Toast.LENGTH_SHORT).show()
            awareManager.connect()
            awareManager.startDiscovery()
            messages.add(0, "システム: 通信を開始しました")
        } else {
            Toast.makeText(context, "権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // ★修正: Publish/Subscribeの分岐を削除
    fun checkPermissionsAndRun() {
        if (isGranted.value) {
            awareManager.connect()
            awareManager.startDiscovery() // ★変更
            messages.add(0, "システム: 通信を開始しました")
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSION.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "UWB Controller") })
        }
    ) { innerPadding ->
        // ネストスクロール問題を避けるため LazyColumn に統合
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // --- UWB UI ---
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "距離 = ${uwbPosition.value?.distance?.value} m")
                    val distanceValue = uwbPosition.value?.distance?.value
                    if (distanceValue != null) {
                        if (distanceValue <= 3) Text(text = "${distanceValue} = 範囲内")
                    }
                }
                Divider()
            }

            // --- Wi-Fi Aware UI ---
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

                    // ★修正: ボタン統合
                    Button(
                        onClick = { checkPermissionsAndRun() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("双方向通信を開始 (Discovery)")
                    }
                }
            }

            // --- Logs ---
            items(messages) { msg ->
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    MessageCard(msg)
                }
            }
        }
    }
}
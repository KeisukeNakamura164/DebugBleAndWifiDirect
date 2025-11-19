package io.github.takusan23.androidbleanduwbsample.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

/** Controller(Host) 側の画面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen() {
    val context = LocalContext.current

    // controlee の位置
    val uwbPosition = remember { mutableStateOf<RangingPosition?>(null) }

    LaunchedEffect(key1 = Unit) {
        // controller 側として作成
        val uwbManager = UwbManager.createInstance(context)
        val controllerSession = uwbManager.controllerSessionScope()

        // ゲスト側へ送るパラメーターを ByteArray にして送る
        // sessionId / sessionKeyInfo はサンプルコードでも適当に作ってるので適当に作る
        // https://github.com/android/connectivity-samples/blob/777517eb2898cd48e139446246808a2106d343cc/UwbRanging/uwbranging/src/main/java/com/google/apps/uwbranging/impl/NearbyControllerConnector.kt#L69
        val sessionId = Random.nextInt()
        val sessionKeyInfo = Random.nextBytes(8)
        // Serializable な data class にして ByteArray にエンコードする
        val uwbControllerParams = UwbControllerParams(
            address = controllerSession.localAddress.address,
            channel = controllerSession.uwbComplexChannel.channel,
            preambleIndex = controllerSession.uwbComplexChannel.preambleIndex,
            sessionId = sessionId,
            sessionKeyInfo = sessionKeyInfo
        )
        // バイト配列に
        val encodeHostParameter = UwbControllerParams.encode(uwbControllerParams)

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
        val controleeAddress = controleeAddressFlow.filterNotNull().first()
        peripheralJob.cancel()

        // RangingParameters を作り UWB 接続を開始する
        val rangingParameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            complexChannel = controllerSession.uwbComplexChannel,
            peerDevices = listOf(UwbDevice.createForAddress(controleeAddress)),
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
        }
    }
}
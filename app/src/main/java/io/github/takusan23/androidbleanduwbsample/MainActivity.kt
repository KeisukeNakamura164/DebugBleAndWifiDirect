package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.uwb.*
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControleeScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControllerScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.HomeScreen
import io.github.takusan23.androidbleanduwbsample.ui.theme.AndroidBleAndUwbSampleTheme
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- 定数・データクラス ---
private const val PORT = 8888
private const val SERVICE_TYPE = "_tus-pj-app._tcp"
private const val INSTANCE_NAME = "pj_keijiban"
private const val TAG = "P2P_DEBUG"
data class P2pServerResult(val receivedData: String?, val clientIp: String?)

// --- 通信ロジック (トップレベル関数) ---
suspend fun startP2pServer(context: Context): P2pServerResult = withContext(Dispatchers.IO) {
    var serverSocket: ServerSocket? = null
    var receivedDataString: String? = null
    var clientIp: String? = null
    try {
        serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(PORT))
        serverSocket.soTimeout = 15000 // タイムアウト15秒

        Log.d(TAG, "[Server] ポート $PORT で接続待機中...")
        val client = serverSocket.accept()
        clientIp = client.inetAddress.hostAddress
        Log.d(TAG, "[Server] クライアント接続あり: $clientIp")

        try {
            // データ受信 (5秒タイムアウト)
            withTimeout(5000) {
                receivedDataString = client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            }
            Log.d(TAG, "[Server] 受信データ: $receivedDataString")
        } finally {
            client.close()
        }
    } catch (e: Exception) {
        if (e !is CancellationException) Log.e(TAG, "[Server] エラー: ${e.message}")
    } finally {
        try { serverSocket?.close() } catch (_: Exception) {}
    }
    return@withContext P2pServerResult(receivedDataString, clientIp)
}

suspend fun startP2pClient(hostAddress: String, message: String): Boolean = withContext(Dispatchers.IO) {
    var socket: Socket? = null
    var success = false
    try {
        Log.d(TAG, "[Client] $hostAddress へ接続試行中...")
        socket = Socket()
        // 接続タイムアウト3秒
        socket.connect(InetSocketAddress(InetAddress.getByName(hostAddress), PORT), 3000)

        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        writer.print(message)
        writer.flush()
        success = true
        Log.d(TAG, "[Client] 送信成功: $message")
    } catch (e: Exception) {
        if (e !is CancellationException) Log.e(TAG, "[Client] エラー: ${e.message}")
    } finally {
        try { socket?.close() } catch (_: Exception) {}
    }
    return@withContext success
}

// --- Activity 本体 ---
class MainActivity : ComponentActivity() {

    // Wi-Fi P2P
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private val intentFilter = IntentFilter()

    // UWB
    private lateinit var uwbManager: UwbManager
    private var uwbClientSessionScope: UwbClientSessionScope? = null

    // UI
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var servicePeers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var thisDevice by mutableStateOf<WifiP2pDevice?>(null)
    private var connectionInfo by mutableStateOf<WifiP2pInfo?>(null)
    private var chatMessages by mutableStateOf<List<String>>(emptyList())
    private var uwbDistance by mutableStateOf("待機中...")

    // 画面切り替え
    private var isP2pMode by mutableStateOf(false)
    private var isExchangingData = false // 通信中フラグ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: アプリ起動")

        // UWB初期化
        if (Build.VERSION.SDK_INT >= 31) uwbManager = UwbManager.createInstance(this)

        // Wi-Fi P2P初期化
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        checkAndRequestPermissions()
        setupServiceDiscoveryListeners()
        startLocalService()

        setContent {
            AndroidBleAndUwbSampleTheme {
                if (isP2pMode) {
                    WifiDirectApp(isWifiP2pEnabled, thisDevice, peers, servicePeers,
                        { discoverPeers() },
                        { discoverServices() },
                        { connect(it) },
                        connectionInfo, chatMessages, { disconnect() }, uwbDistance
                    )
                } else {
                    MainScreen(
                        onGetLocalAddress = { isController -> prepareUwbSessionAndGetAddress(isController) },
                        onStartUwb = { params, address, isController -> startUwbRanging(params, address, isController) },
                        currentDistance = uwbDistance,
                        onSwitchToP2p = { isP2pMode = true }
                    )
                }
            }
        }
    }

    // UWB関連
    private suspend fun prepareUwbSessionAndGetAddress(isController: Boolean): ByteArray {
        if (uwbClientSessionScope == null) {
            val uwbManager = UwbManager.createInstance(this)
            uwbClientSessionScope = if (isController) uwbManager.controllerSessionScope() else uwbManager.controleeSessionScope()
        }
        return uwbClientSessionScope!!.localAddress.address
    }

    private fun startUwbRanging(params: UwbControllerParams, peerAddress: ByteArray?, isController: Boolean) {
        val session = uwbClientSessionScope ?: return

        lifecycleScope.launch {
            try {
                val peerUwbAddress = if (isController) {
                    if (peerAddress == null) return@launch
                    UwbDevice.createForAddress(peerAddress)
                } else {
                    UwbDevice.createForAddress(params.address)
                }

                val configType = RangingParameters.CONFIG_UNICAST_DS_TWR
                val complexChannel = UwbComplexChannel(params.channel, params.preambleIndex)
                val rangingParameters = RangingParameters(
                    uwbConfigType = configType,
                    complexChannel = complexChannel,
                    peerDevices = listOf(peerUwbAddress),
                    updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
                    sessionId = params.sessionId,
                    sessionKeyInfo = params.sessionKeyInfo,
                    subSessionId = 0,
                    subSessionKeyInfo = null
                )

                while (isActive) {
                    try {
                        session.prepareSession(rangingParameters).collect { result ->
                            when (result) {
                                is RangingResult.RangingResultPosition -> {
                                    val dist = result.position.distance?.value
                                    uwbDistance = if (dist != null) "距離: %.2f m".format(dist) else "不明"
                                }
                                is RangingResult.RangingResultPeerDisconnected -> uwbDistance = "再接続中..."
                            }
                        }
                    } catch (e: Exception) { Log.e("UWB", "Error: ${e.message}") }
                    delay(1000)
                }
            } catch (e: Exception) { uwbDistance = "エラー" }
        }
    }

    // P2P関連リスナー
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers = peerList.deviceList.toList()
        Log.d(TAG, "通常Peer一覧更新: ${peers.size}件")
    }

    // ★重要: 時差・リトライロジックを組み込んだ接続リスナー
    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        connectionInfo = info
        Log.d(TAG, "接続状態変更: Owner=${info.isGroupOwner}")

        if (info.groupFormed && info.isGroupOwner) {
            // 親機 (Server)
            if (isExchangingData) return@ConnectionInfoListener
            isExchangingData = true

            lifecycleScope.launch {
                try {
                    Log.d(TAG, "親機: 受信待機開始")
                    val res = startP2pServer(this@MainActivity)
                    if (res.receivedData != null) chatMessages = chatMessages + "Recv: ${res.receivedData}"

                    if (res.clientIp != null) {
                        // ★時差ロジック: Clientが受信モードになるのを待つ
                        Log.d(TAG, "親機: 返信のため0.5秒待機...")
                        delay(500)

                        val message = createSampleMessage("Server Reply")
                        val success = startP2pClient(res.clientIp, message)
                        if (success) {
                            chatMessages = chatMessages + "Sent back: $message"
                        }
                    }
                } finally {
                    isExchangingData = false
                    Log.d(TAG, "親機: 処理完了・切断")
                    disconnect()
                }
            }
        } else if (info.groupFormed) {
            // 子機 (Client)
            val goIp = info.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                if (isExchangingData) return@ConnectionInfoListener
                isExchangingData = true

                lifecycleScope.launch {
                    try {
                        // ★時差ロジック: Serverのソケット準備を待つ
                        Log.d(TAG, "子機: サーバー準備待ち(1秒)...")
                        delay(1000)

                        val message = createSampleMessage("Client Hello")
                        var success = false

                        // ★リトライロジック: 最大3回送信を試みる
                        for (i in 1..3) {
                            Log.d(TAG, "子機: 送信試行 $i 回目")
                            success = startP2pClient(goIp, message)
                            if (success) {
                                chatMessages = chatMessages + "Sent: $message"
                                break
                            }
                            delay(1000) // 失敗したら1秒待って再試行
                        }

                        if (success) {
                            Log.d(TAG, "子機: 返信待機開始")
                            val res = startP2pServer(this@MainActivity)
                            if (res.receivedData != null) chatMessages = chatMessages + "Reply: ${res.receivedData}"
                        }
                    } finally {
                        isExchangingData = false
                        Log.d(TAG, "子機: 処理完了・切断")
                        disconnect()
                    }
                }
            }
        }
    }

    inner class WiFiDirectBroadcastReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission") override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    isWifiP2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "P2P State Changed: $isWifiP2pEnabled")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel, peerListListener)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        Log.d(TAG, "接続確立。詳細情報取得中...")
                        manager.requestConnectionInfo(channel, connectionInfoListener)
                    } else {
                        Log.d(TAG, "切断されました")
                        connectionInfo = null
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> thisDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java) else intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Log.d(TAG, "権限リクエスト結果: $it")
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != 0) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != 0) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        Log.d(TAG, "Scan Peers 開始")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "discoverPeers 成功") }
            override fun onFailure(r: Int) { Log.e(TAG, "discoverPeers 失敗: $r") }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices() {
        Log.d(TAG, "Scan Services 開始")
        servicePeers = emptyList()
        manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val req = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
                manager.addServiceRequest(channel, req, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { Log.d(TAG, "discoverServices 成功") }
                            override fun onFailure(r: Int) { Log.e(TAG, "discoverServices 失敗: $r") }
                        })
                    }
                    override fun onFailure(r: Int) { Log.e(TAG, "addServiceRequest 失敗: $r") }
                })
            }
            override fun onFailure(r: Int) { Log.e(TAG, "clearServiceRequests 失敗: $r") }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: WifiP2pDevice) {
        Log.d(TAG, "接続要求: ${device.deviceName}")
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress; groupOwnerIntent = 0 }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "connect 成功") }
            override fun onFailure(r: Int) { Log.e(TAG, "connect 失敗: $r") }
        })
    }
    private fun disconnect() {
        Log.d(TAG, "切断要求")
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { connectionInfo = null }
            override fun onFailure(r: Int) {}
        })
    }

    private fun createSampleMessage(prefix: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val model = Build.MODEL
        return "[$prefix] Device:$model Time:$timestamp"
    }

    @SuppressLint("MissingPermission")
    private fun startLocalService() {
        val record = mapOf("boardName" to "MyBoard", "ownerName" to "User")
        manager.addLocalService(channel, WifiP2pDnsSdServiceInfo.newInstance(INSTANCE_NAME, SERVICE_TYPE, record), null)
    }

    private fun setupServiceDiscoveryListeners() {
        manager.setDnsSdResponseListeners(channel, { _, type, device ->
            if (type.startsWith(SERVICE_TYPE)) {
                if (servicePeers.none { it.deviceAddress == device.deviceAddress }) {
                    Log.d(TAG, "Service発見: ${device.deviceName}")
                    servicePeers = servicePeers + device
                }
            }
        }, null)
    }

    override fun onResume() { super.onResume(); receiver = WiFiDirectBroadcastReceiver(); registerReceiver(receiver, intentFilter) }
    override fun onPause() { super.onPause(); unregisterReceiver(receiver) }
    override fun onDestroy() { super.onDestroy(); disconnect() }
}

// UI Components
@Composable
private fun MainScreen(
    onGetLocalAddress: suspend (Boolean) -> ByteArray,
    onStartUwb: (UwbControllerParams, ByteArray?, Boolean) -> Unit,
    currentDistance: String,
    onSwitchToP2p: () -> Unit
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onControllerClick = { navController.navigate("controller") }, onControleeClick = { navController.navigate("controlee") })
        }
        composable("controller") {
            ControllerScreen(onGetLocalAddress, onStartUwb, currentDistance, onSwitchToP2p)
        }
        composable("controlee") {
            ControleeScreen(onGetLocalAddress, onStartUwb, currentDistance, onSwitchToP2p)
        }
    }
}

@Composable
fun WifiDirectApp(
    isWifiP2pEnabled: Boolean, thisDevice: WifiP2pDevice?, peers: List<WifiP2pDevice>, servicePeers: List<WifiP2pDevice>,
    onDiscoverPeers: () -> Unit, onDiscoverServices: () -> Unit, onConnectPeer: (WifiP2pDevice) -> Unit,
    connectionInfo: WifiP2pInfo?, chatMessages: List<String>, onDisconnect: () -> Unit, uwbDistance: String
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Wi-Fi P2P: ${if (isWifiP2pEnabled) "ON" else "OFF"}")

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F7FA)), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, Color(0xFF006064), RoundedCornerShape(8.dp))) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("UWB Distance", fontSize = 14.sp, color = Color.Gray)
                Text(text = uwbDistance, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF006064))
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onDiscoverPeers) { Text("Scan Peers") }
            Button(onClick = onDiscoverServices) { Text("Scan Services") }
        }
        if (connectionInfo?.groupFormed == true) {
            Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("Disconnect") }
            Text("Connected!", color = Color.Green, fontWeight = FontWeight.Bold)
        }
        Text("Logs:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFEEEEEE)).border(1.dp, Color.Gray)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), reverseLayout = true) {
                items(chatMessages.reversed()) { msg -> Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().padding(2.dp)) { Text(msg, modifier = Modifier.padding(8.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace) } }
            }
        }

        Text("Device List:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(modifier = Modifier.height(200.dp).fillMaxWidth().border(1.dp, Color.Gray)) {
            // セクション1: 通常のPeers
            item {
                Text("--- Normal Peers ---", modifier = Modifier.padding(4.dp).background(Color.LightGray).fillMaxWidth(), fontSize = 12.sp)
            }
            if (peers.isEmpty()) {
                item { Text("No peers found", modifier = Modifier.padding(8.dp), color = Color.Gray) }
            } else {
                items(peers) { device ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onConnectPeer(device) }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(device.deviceName ?: "Unknown", modifier = Modifier.weight(1f))
                        Button(onClick = { onConnectPeer(device) }, modifier = Modifier.height(36.dp)) { Text("Connect", fontSize = 10.sp) }
                    }
                }
            }

            // セクション2: Service Peers
            item {
                Text("--- Service Peers ---", modifier = Modifier.padding(4.dp).background(Color.LightGray).fillMaxWidth(), fontSize = 12.sp)
            }
            if (servicePeers.isEmpty()) {
                item { Text("No services found", modifier = Modifier.padding(8.dp), color = Color.Gray) }
            } else {
                items(servicePeers) { device ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onConnectPeer(device) }.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(device.deviceName ?: "Unknown", modifier = Modifier.weight(1f))
                        Button(onClick = { onConnectPeer(device) }, modifier = Modifier.height(36.dp)) { Text("Connect", fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}
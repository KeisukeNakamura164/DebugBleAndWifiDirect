package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// --- 定数・データクラス ---
private const val PORT = 8888
private const val SERVICE_TYPE = "_tus-pj-app._tcp"
private const val INSTANCE_NAME = "pj_keijiban"
data class P2pServerResult(val receivedData: String?, val clientIp: String?)

// --- 通信ロジック (トップレベル関数) ---
// ※ startP2pServer, startP2pClient は変更なしのため省略せずそのまま使ってください
// (前回のコードと同じものを維持)
suspend fun startP2pServer(context: Context): P2pServerResult = withContext(Dispatchers.IO) {
    var serverSocket: ServerSocket? = null
    var receivedDataString: String? = null
    var clientIp: String? = null
    try {
        serverSocket = ServerSocket(PORT)
        val client = serverSocket.accept()
        clientIp = client.inetAddress.hostAddress
        try {
            receivedDataString = client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        } finally { client.close() }

        if (!receivedDataString.isNullOrBlank()) {
            try {
                val receivedJson = JSONObject(receivedDataString)
                val today = LocalDate.now()
                val storageDir = File(context.filesDir, "OtherAccount/${today.year}")
                if (!storageDir.exists()) storageDir.mkdirs()
                val outputFile = File(storageDir, "oa_${today.format(DateTimeFormatter.ofPattern("yyyyMM"))}.json")
                val monthDataArray = if (outputFile.exists() && outputFile.readText().isNotBlank()) JSONArray(outputFile.readText()) else JSONArray()
                monthDataArray.put(receivedJson)
                outputFile.writeText(monthDataArray.toString(4))
            } catch (e: Exception) { Log.e("P2P_SERVER", "Error saving file", e) }
        }
    } catch (e: Exception) {
        if (e !is CancellationException) Log.e("P2P_SERVER", "Error: ${e.message}")
    } finally {
        try { serverSocket?.close() } catch (_: Exception) {}
    }
    return@withContext P2pServerResult(receivedDataString, clientIp)
}

suspend fun startP2pClient(hostAddress: String, jsonString: String): Boolean = withContext(Dispatchers.IO) {
    var socket: Socket? = null
    var success = false
    try {
        socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getByName(hostAddress), PORT), 5000)
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        writer.println(jsonString)
        success = true
    } catch (e: Exception) {
        if (e !is CancellationException) Log.e("P2P_CLIENT", "Error: ${e.message}")
    } finally {
        try { socket?.close() } catch (_: Exception) {}
    }
    return@withContext success
}

// --- Activity 本体 ---
class MainActivity : ComponentActivity() {
    companion object { lateinit var awareManager: AwareManager }
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private val intentFilter = IntentFilter()

    private var isWifiP2pEnabled by mutableStateOf(false)
    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var servicePeers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var thisDevice by mutableStateOf<WifiP2pDevice?>(null)
    private var connectionInfo by mutableStateOf<WifiP2pInfo?>(null)
    private var chatMessages by mutableStateOf<List<String>>(emptyList())
    private var uwbDistance by mutableStateOf("待機中...")
    private var isP2pMode by mutableStateOf(false)
    private var isServerRunning = false

    // ★重要: UWBセッションを保持する変数
    private var uwbClientSessionScope: UwbClientSessionScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        awareManager = AwareManager(this)
        super.onCreate(savedInstanceState)

        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        createDummyMyAccountFile()
        checkAndRequestPermissions()
        setupServiceDiscoveryListeners()
        startLocalService()

        setContent {
            AndroidBleAndUwbSampleTheme {
                if (isP2pMode) {
                    WifiDirectApp(isWifiP2pEnabled, thisDevice, peers, servicePeers, { discoverPeers() }, { discoverServices() }, { connect(it) }, connectionInfo, chatMessages, { disconnect() }, uwbDistance)
                } else {
                    MainScreen(
                        // ★修正: アドレス取得リクエスト
                        onGetLocalAddress = { isController ->
                            prepareUwbSessionAndGetAddress(isController)
                        },
                        // ★修正: UWB開始リクエスト
                        onStartUwb = { params, address, isController ->
                            startUwbRanging(params, address, isController)
                        },
                        currentDistance = uwbDistance,
                        onSwitchToP2p = { isP2pMode = true }
                    )
                }
            }
        }
    }

    // ★重要: セッションを作成・保持してアドレスを返す関数
    private suspend fun prepareUwbSessionAndGetAddress(isController: Boolean): ByteArray {
        if (uwbClientSessionScope == null) {
            val uwbManager = UwbManager.createInstance(this)
            uwbClientSessionScope = if (isController) {
                uwbManager.controllerSessionScope()
            } else {
                uwbManager.controleeSessionScope()
            }
        }
        // ここで返したアドレス(A)と、後のstartUwbRangingで使うセッションのアドレス(A)が一致する！
        return uwbClientSessionScope!!.localAddress.address
    }

    // ★重要: 保持しているセッションを使って測距開始
    private fun startUwbRanging(params: UwbControllerParams, peerAddress: ByteArray?, isController: Boolean) {
        val session = uwbClientSessionScope ?: return // 作成済みセッションを使う

        lifecycleScope.launch {
            try {
                val peerUwbAddress = if (isController) {
                    if (peerAddress == null) return@launch
                    UwbDevice.createForAddress(peerAddress)
                } else {
                    UwbDevice.createForAddress(params.address)
                }

                // ★UNICASTに変更 (安定性向上)
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
                    } catch (e: Exception) {
                        Log.e("UWB", "Error: ${e.message}")
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                uwbDistance = "エラー"
            }
        }
    }

    // (以下、P2P関連リスナー等は変更なし)
    private val peerListListener = WifiP2pManager.PeerListListener { peerList -> peers = peerList.deviceList.toList() }
    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        connectionInfo = info
        if (info.groupFormed && info.isGroupOwner) {
            if (isServerRunning) return@ConnectionInfoListener
            isServerRunning = true
            lifecycleScope.launch {
                try {
                    val res = startP2pServer(this@MainActivity)
                    if (res.receivedData != null) chatMessages = chatMessages + "Recv: ${res.receivedData}"
                    if (res.clientIp != null) {
                        val myJson = loadAndPrepareMyAccountJson(this@MainActivity)
                        if (myJson != null && startP2pClient(res.clientIp, myJson)) chatMessages = chatMessages + "Sent back."
                    }
                } finally { isServerRunning = false; disconnect() }
            }
        } else if (info.groupFormed) {
            val goIp = info.groupOwnerAddress?.hostAddress
            if (goIp != null) {
                lifecycleScope.launch {
                    val myJson = loadAndPrepareMyAccountJson(this@MainActivity)
                    if (myJson != null && startP2pClient(goIp, myJson)) chatMessages = chatMessages + "Sent."
                    val res = startP2pServer(this@MainActivity)
                    if (res.receivedData != null) chatMessages = chatMessages + "Reply: ${res.receivedData}"
                    disconnect()
                }
            }
        }
    }
    inner class WiFiDirectBroadcastReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission") override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> isWifiP2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel, peerListListener)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) manager.requestConnectionInfo(channel, connectionInfoListener) else connectionInfo = null
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> thisDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java) else intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
            }
        }
    }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != 0) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != 0) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }
    @SuppressLint("MissingPermission") private fun discoverPeers() { manager.discoverPeers(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() {}; override fun onFailure(r: Int) {} }) }
    @SuppressLint("MissingPermission") private fun discoverServices() { servicePeers = emptyList(); manager.discoverServices(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() {}; override fun onFailure(r: Int) {} }) }
    @SuppressLint("MissingPermission") private fun connect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress; groupOwnerIntent = 0 }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener { override fun onSuccess() {}; override fun onFailure(r: Int) {} })
    }
    private fun disconnect() { manager.removeGroup(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() { connectionInfo = null }; override fun onFailure(r: Int) {} }) }
    private fun loadAndPrepareMyAccountJson(context: Context): String? {
        val file = File(context.filesDir, "MyAccount/myaccount.json")
        if (!file.exists()) return null
        return try { JSONObject(file.readText()).apply { put("time", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))) }.toString() } catch (e: Exception) { null }
    }
    private fun createDummyMyAccountFile() {
        val dir = File(filesDir, "MyAccount").apply { if (!exists()) mkdirs() }
        val file = File(dir, "myaccount.json")
        if (!file.exists()) file.writeText("""{"name": "Taro Test", "id": "test_user_01"}""")
    }
    @SuppressLint("MissingPermission") private fun startLocalService() {
        val record = mapOf("boardName" to "MyBoard", "ownerName" to "User")
        manager.addLocalService(channel, WifiP2pDnsSdServiceInfo.newInstance(INSTANCE_NAME, SERVICE_TYPE, record), null)
    }
    private fun setupServiceDiscoveryListeners() {
        manager.setDnsSdResponseListeners(channel, { _, type, device -> if (type.startsWith(SERVICE_TYPE) && !servicePeers.contains(device)) servicePeers = servicePeers + device }, null)
        manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE), null)
    }
    override fun onResume() { super.onResume(); receiver = WiFiDirectBroadcastReceiver(); registerReceiver(receiver, intentFilter) }
    override fun onPause() { super.onPause(); unregisterReceiver(receiver) }
    override fun onDestroy() { super.onDestroy(); disconnect(); awareManager.close() }
}

// --- Navigation ---
@Composable
private fun MainScreen(
    onGetLocalAddress: suspend (Boolean) -> ByteArray, // ★追加
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
            ControllerScreen(onGetLocalAddress, onStartUwb, currentDistance, onSwitchToP2p) // ★渡す
        }
        composable("controlee") {
            ControleeScreen(onGetLocalAddress, onStartUwb, currentDistance, onSwitchToP2p) // ★渡す
        }
    }
}

// --- UI Components (WifiDirectApp) は以前と同じなので省略（そのままでOK）---
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
        Text("Found Peers:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(modifier = Modifier.height(150.dp).fillMaxWidth().border(1.dp, Color.Gray)) {
            items(peers) { device -> Row(modifier = Modifier.fillMaxWidth().clickable { onConnectPeer(device) }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(device.deviceName ?: "Unknown"); Button(onClick = { onConnectPeer(device) }) { Text("Connect") }
            } }
        }
    }
}
package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.WIFI_P2P_SERVICE
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
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import kotlin.collections.plus



// ** 送受信ポート番号 (両デバイスで一致させる) **
private const val PORT = 8888

// この2つは「合言葉」。子機側と完全に一致させる必要がある。(for discoverService)
private const val SERVICE_TYPE = "_tus-pj-app._tcp" // "_サービス名._プロトコル" 形式
private const val INSTANCE_NAME = "pj_keijiban"    // この掲示板の固有名詞

// startServerが「受信データ」と「クライアントIP」の2つを返せるようにデータクラスを定義(for 相互通信)
data class ServerResult(val receivedData: String?, val clientIp: String?)

suspend fun startServer(context: Context): io.github.takusan23.androidbleanduwbsample.ServerResult = withContext(Dispatchers.IO) {
    var serverSocket: ServerSocket? = null
    var receivedDataString: String? = null
    var clientIp: String? = null //クライアントのIPアドレスを保存する

    try {
        serverSocket = ServerSocket(io.github.takusan23.androidbleanduwbsample.PORT)
        Log.d("P2P_SERVER", "Server started. Waiting for connection on port ${io.github.takusan23.androidbleanduwbsample.PORT}...")

        val client = serverSocket.accept()
        clientIp = client.inetAddress.hostAddress //クライアントのIPアドレスを取得
        Log.d("P2P_SERVER", "Client connected! IP: $clientIp")

        // 1. クライアントから送信されたバイトデータをJSON形式の文字列(中カッコで囲まれた形)として読み込む
        try {
            // UTF-8でデコード
            receivedDataString = client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            if (receivedDataString.isBlank()) {
                Log.e("P2P_SERVER", "Received data is blank.")
                return@withContext ServerResult(null, clientIp)
            }
        } catch (e: Exception) {
            Log.e("P2P_SERVER", "Error reading data from client", e)
            return@withContext ServerResult(null, clientIp)
        } finally {
            client.close() // データ読み込み後すぐにクライアントソケットを閉じる
        }

        // 2. 受信した(ただの)文字列をJSONオブジェクト(キーとバリューの意味を持つ形)に変換
        val receivedJson: JSONObject
        try {
            receivedJson = JSONObject(receivedDataString)
        } catch (e: Exception) {
            Log.e("P2P_SERVER", "Failed to parse received data as JSON: $receivedDataString", e)
            return@withContext ServerResult("[JSONのパースに失敗しました]:$receivedDataString", clientIp)
        }

        // 3. 現在の日付から保存先パスを決定 (GradleのminSdkを26に)
        val today = LocalDate.now()
        val year = today.year.toString()
        val yearMonth = today.format(DateTimeFormatter.ofPattern("yyyyMM"))

        // 4. 保存先ディレクトリを構築 ( /files/OtherAccount/2025 )
        // context.filesDir は /data/data/<package_name>/files を指す
        val storageDir = File(context.filesDir, "OtherAccount/$year")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        // 5. 保存先ファイルパスを構築 ( .../oa_202511.json )
        val outputFile = File(storageDir, "oa_$yearMonth.json")
        val savedFilePath = outputFile.absolutePath

        // 6. 既存の月別ファイルを読み込む(あればそこに追記(7へ)，無ければ新規作成(elseへ))
        var monthDataArray: JSONArray
        if (outputFile.exists()) {
            try {
                val existingContent = outputFile.readText()
                //空だったら
                monthDataArray = if (existingContent.isBlank()) JSONArray() else JSONArray(existingContent)
            } catch (e: Exception) {
                Log.e("P2P_SERVER", "Failed to parse existing file ($savedFilePath), creating new one.", e)
                monthDataArray = JSONArray() // パース失敗時は新規作成
            }
        } else {
            monthDataArray = JSONArray() // ファイルが存在しない場合は新規作成
        }

        // 7. データを "JSON配列の末尾" に "追記" する(put)
        monthDataArray.put(receivedJson)

        // 8. ファイルに書き戻す(上書き保存)
        try {
            outputFile.writeText(monthDataArray.toString(4)) // 4はインデント幅
            Log.d("P2P_SERVER", "File updated successfully: $savedFilePath")
        } catch (e: Exception) {
            Log.e("P2P_SERVER", "Error writing file", e)
        }

    } catch (e: Exception) {
        // Coroutine cancellation exceptionを無視
        if (e !is CancellationException) {
            Log.e("P2P_SERVER", "Error in server: ${e.message}")
        } else {
            Log.d("P2P_SERVER", "Server scope cancelled.")
        }
    } finally {
        try {
            serverSocket?.close()
            Log.d("P2P_SERVER", "Server socket closed.")
        } catch (e: Exception) {
            Log.e("P2P_SERVER", "Error closing server socket: ${e.message}")
        }
    }
    return@withContext ServerResult(receivedDataString, clientIp) // 受信したJSON文字列を返す
}

/**
 * クライアント側（送信）の処理をバックグラウンドで行う関数 (suspend関数化)
 * @param hostAddress Group OwnerのIPアドレス
 * @param jsonString 送信するJSON文字列
 */
suspend fun startClient(hostAddress: String, jsonString: String): Boolean = withContext(Dispatchers.IO) {
    var socket: Socket? = null
    var success = false

    if (jsonString.isBlank()) {
        Log.e("P2P_CLIENT", "JSON String to send is blank.")
        return@withContext false
    }

    try {
        // 1.サーバのIPアドレスとポート番号を指定
        val serverAddress = InetAddress.getByName(hostAddress) //サーバのIPアドレス
        Log.d("P2P_CLIENT", "Connecting to GO at $hostAddress:${io.github.takusan23.androidbleanduwbsample.PORT}")

        // 2.接続試行（タイムアウトを設定）
        socket = Socket()
        socket.connect(InetSocketAddress(serverAddress, io.github.takusan23.androidbleanduwbsample.PORT), 5000) // 5秒タイムアウト

        // 3.データを送信
        try {
            // (OutputStreamWriter で UTF-8 を明示)
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            writer.println(jsonString) // 文字列を送信

            success = true
            Log.d("P2P_CLIENT", "JSON String sent successfully.")
        } catch (e: Exception) {
            Log.e("P2P_CLIENT", "Error sending file: ${e.message}")
        }
    } catch (e: Exception) {
        if (e !is CancellationException) {
            Log.e("P2P_CLIENT", "Error in client: ${e.message}")
        } else {
            Log.d("P2P_CLIENT", "Client scope cancelled.")
        }
        success = false
    } finally {
        //4.ソケットを閉じる
        try {
            socket?.close()
            Log.d("P2P_CLIENT", "Client socket closed.")
        } catch (e: Exception) {
            Log.e("P2P_CLIENT", "Error closing client socket: ${e.message}")
        }
    }
    return@withContext success
}

class WifiDirect : ComponentActivity() {
    private lateinit var manager: WifiP2pManager // WifiP2pManagerのインスタンス
    private lateinit var channel: WifiP2pManager.Channel // WifiP2pManagerのチャンネル
    private lateinit var receiver: BroadcastReceiver // BroadcastReceiverのインスタンス
    private val intentFilter = IntentFilter()

    // --- Compose UI のための状態変数 ---
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())

    private var servicePeers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var thisDevice by mutableStateOf<WifiP2pDevice?>(null)
    private var connectionInfo by mutableStateOf<WifiP2pInfo?>(null)
    private var chatMessages by mutableStateOf<List<String>>(emptyList()) // チャット履歴

    // --- P2Pリスナー ---
    // discoverPeers用関数
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers = peerList.deviceList.toList()
        Log.d("MainActivity", "Found ${peers.size} peers.")
    }

    // 1. 親機用サービス情報登録関数
    @SuppressLint("MissingPermission")
    private fun startLocalService() {
        // 1-2. TXTレコード（オプション）を作成
        //      "key=value"形式で、相手に伝えたい追加情報を設定できる
        val record = mapOf(
            "boardName" to "創域理工B棟の掲示板", // 例: 掲示板の名前
            "ownerName" to "Katis",           // 例: オーナー名
            "status" to "available"           // 例: 状態
        )

        // 1-3. サービス情報 (WifiP2pDnsSdServiceInfo) を作成
        val serviceInfo: WifiP2pServiceInfo =
            WifiP2pDnsSdServiceInfo.newInstance(INSTANCE_NAME, SERVICE_TYPE, record)

        // 1-4. サービスをOSに登録
        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("ServiceDiscovery", "ローカルサービスの登録に成功")
            }

            override fun onFailure(reason: Int) {
                Log.e("ServiceDiscovery", "ローカルサービスの登録に失敗: $reason")
            }
        })
    }

    // 2. 子機用
    private fun setupServiceDiscoveryListeners() {
        // 2-1. メインのリスナー (サービス本体 + 端末情報)
        val serviceResponseListener = WifiP2pManager.DnsSdServiceResponseListener {
                instanceName, registrationType, device ->

            // サービスタイプが、探しているものと一致するか確認
            if (registrationType.startsWith(SERVICE_TYPE)) {
                Log.d("ServiceDiscovery", "サービスを発見！ Instance: $instanceName")

                // ★★★
                // ここで「端末 (device)」が手に入る！
                // ★★★

                if (!servicePeers.contains(device)) {
                    servicePeers = servicePeers + device
                }

            } else {
                Log.d("ServiceDiscovery", "無関係なサービスを発見: $registrationType")
            }
        }

        // 2-2. オプションのリスナー (TXTレコード)
        val txtRecordListener = WifiP2pManager.DnsSdTxtRecordListener {
                fullDomainName, record, device ->

            Log.d("ServiceDiscovery", "TXTレコード受信: ${device.deviceName}")
            // 1-2 で設定した情報が Map<String, String> で手に入る
            Log.d("ServiceDiscovery", "  -> boardName: ${record["boardName"]}")
            Log.d("ServiceDiscovery", "  -> ownerName: ${record["ownerName"]}")

            // TODO: この `record` 情報を `device` と紐付けてUIに表示する
        }

        // 2-3. リスナーをOSに登録
        manager.setDnsSdResponseListeners(channel, serviceResponseListener, txtRecordListener)

        // 2-4. 「探したいサービスのリクエスト」を作成
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)

        // 2-5. リクエストをOSに登録
        manager.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d("ServiceDiscovery", "サービスリクエストの登録に成功") }
            override fun onFailure(reason: Int) { Log.e("ServiceDiscovery", "サービスリクエストの登録に失敗: $reason") }
        })
    }

    // --- 権限リクエスター ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] != true &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissions[Manifest.permission.NEARBY_WIFI_DEVICES] != true)
            ) {
                Log.d("MainActivity", "Required permissions not granted.")
                // TODO: ユーザーに権限が必要な理由を説明するUIを表示する
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        setupServiceDiscoveryListeners()

        // Intent Filterの設定
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        createDummyMyAccountFile() // テスト用のダミーファイルを作成
        // 権限チェック
        checkAndRequestPermissions()

        // 1. 探索（子機）のためのリスナーをセットアップ
        setupServiceDiscoveryListeners()

        // 2. 広告（親機）のためのサービスを登録
        startLocalService()

        // UIをJetpack Composeで構築
        setContent {
            WIFIDIRECTTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WifiDirectApp(
                        isWifiP2pEnabled = isWifiP2pEnabled,
                        thisDevice = thisDevice,
                        peers = peers,
                        servicePeers = servicePeers,
                        onDiscoverPeers = { discoverPeers() },
                        onDiscoverServices = { discoverServices() },
                        onConnectPeer = { device -> connect(device) },
                        connectionInfo = connectionInfo,
                        chatMessages = chatMessages,
                        onDisconnect = { disconnect() }, // disconnect() 関数を渡す
                    )
                }
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)
        registerReceiver(receiver, intentFilter)
    }

    public override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver) // onPauseで解除するのが一般的
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        // Android 12 (S) 未満では ACCESS_FINE_LOCATION が必須
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        // Android 12 (S) 以上では NEARBY_WIFI_DEVICES が必須 (TIRAMISU(13)から)
        // ただし、S(12)でも ACCESS_FINE_LOCATION は依然として discoverPeers に必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    // 必要な権限をチェックするヘルパー
    @SuppressLint("MissingPermission")
    private fun hasRequiredPermissions(): Boolean {
        val hasLocation = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        val hasNearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true // 13未満ではこの権限は不要
        }

        return hasLocation && hasNearbyDevices
    }


    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        if (!hasRequiredPermissions()) {
            Log.w("MainActivity", "discoverPeers: Missing permissions.")
            checkAndRequestPermissions()
            return
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "discoverPeers onSuccess")
                // (UIへの通知はBroadcastReceiver経由で行われる)
            }

            override fun onFailure(reasonCode: Int) {
                Log.d("MainActivity", "discoverPeers onFailure: $reasonCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices() {
        if (!hasRequiredPermissions()) {
            Log.w("MainActivity", "discoverServices: Missing permissions.")
            checkAndRequestPermissions()
            return
        }

        servicePeers = emptyList()

        manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "discoverServices onSuccess")
                // (UIへの通知はBroadcastReceiver経由で行われる)
            }

            override fun onFailure(reasonCode: Int) {
                Log.d("MainActivity", "discoverServices onFailure: $reasonCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            Log.w("MainActivity", "connect: Missing permissions.")
            checkAndRequestPermissions()
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 15 // 自身がGroup Ownerになりたい度合い (0-15)
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "Connect call succeeded.")
                // 接続確立は BroadcastReceiver が検知
            }

            override fun onFailure(reasonCode: Int) {
                Log.d("MainActivity", "Connect call failed with reason code: $reasonCode")
            }
        })
    }

    /**
     * 送信用のJSONを準備する
     * 1. /files/MyAccount/myaccount.json を読み込む
     * 2. "time": "yyyy/MM/dd" を追加する
     * @return 送信用のJSON文字列 (失敗時は null)
     */
    private fun loadAndPrepareMyAccountJson(context: Context): String? {
        // 1. myaccount.json ファイルのパスを指定
        val myAccountFile = File(context.filesDir, "MyAccount/myaccount.json")

        if (!myAccountFile.exists()) {
            Log.e("MainActivity", "MyAccount file not found: ${myAccountFile.absolutePath}")
            return null
        }

        try {
            // 2. ファイルを読み込む
            val content = myAccountFile.readText()
            val myAccountJson = JSONObject(content)

            // 3. time を yyyy/MM/dd 形式で追加
            val todayString = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            myAccountJson.put("time", todayString)

            // 4. JSON文字列として返す
            return myAccountJson.toString()

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to read or parse MyAccount file", e)
            return null
        }
    }

    /**
     * 接続が確立した時に呼び出されるリスナー
     * ここで「サーバー(GO)」と「クライアント」の役割を判断し、
     * 相互通信のシーケンス（受信→返信 / 送信→受信）を開始する
     */
    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->

        connectionInfo = info // Compose UIに状態を通知

        //自分がグループオーナー=サーバ側の処理
        if (info.groupFormed && info.isGroupOwner) {
            Log.d("MainActivity", "I am the Group Owner. Starting server...")

            // 🚀 CoroutineScope (lifecycleScope) を使用
            lifecycleScope.launch {

                //１．(サーバ)まずクライアントからのデータを受信する(startServer)．クライアントのIPアドレスも取得する．
                val serverResult = startServer(this@MainActivity)
                val receivedJsonContent = serverResult.receivedData
                val clientIpAddress = serverResult.clientIp //クライアントのIPを取得

                if (receivedJsonContent != null) {
                    Log.d("MainActivity", "Server: Received JSON: '$receivedJsonContent'")
                    // 🚀 Compose の状態変数を更新 (UIスレッドで自動的に行われる)
                    chatMessages = chatMessages + "Peer: $receivedJsonContent"

                } else {
                    Log.w("MainActivity", "Server: No message received or error occurred.")
                    chatMessages = chatMessages + "[ファイルの受信に失敗しました]"
                }

                //２．(サーバ)次に受信した空いてのIP宛に自分の送り返す(startClient)
                if (clientIpAddress == null) {
                    Log.e("MainActivity", "Server: Could not get Client IP. Cannot send back.")
                    chatMessages = chatMessages + "[送信先のIPアドレスが取得できないため返信できません]"
                } else {
                    val jsonStringToSend = loadAndPrepareMyAccountJson(this@MainActivity)

                    if (jsonStringToSend == null) {
                        Log.e("MainActivity", "Server: Failed to load MyAccout JSON.")
                        chatMessages = chatMessages + "[自分のアカウント情報(myaccount.json)が読み込めません]"
                    } else {
                        val success = startClient(clientIpAddress, jsonStringToSend)
                        if(success) {
                            Log.d("MainActivity", "Server: Sent bac data.")
                            chatMessages = chatMessages + "Me:(Sent back):\n$jsonStringToSend"
                        } else {
                            Log.e("MainActivity", "Server: Failed to send back data.")
                            chatMessages = chatMessages + "[返信失敗]"
                        }
                    }
                }

                // ５．(サーバ)処理が終わったら切断する
                Log.d("MainActivity", "Server task finished. Disconnecting.")
                disconnect()
            }

            //自分がクライアント側の処理
        } else if (info.groupFormed) {
            Log.d("MainActivity", "I am the Client.")
            val groupOwnerAddress = info.groupOwnerAddress?.hostAddress

            if (groupOwnerAddress != null) {
                Log.d("MainActivity", "Group Owner IP: $groupOwnerAddress. Starting Client...")

                lifecycleScope.launch {

                    //１．(クライアント)まずサーバのIP宛に自分のデータを送信する(startClient)
                    val jsonStringToSend = loadAndPrepareMyAccountJson(this@MainActivity) // 送信するJSON
                    if (jsonStringToSend == null) {
                        Log.e("MainActivity", "Client: Failed to load or prepare MyAccount JSON.")
                        chatMessages = chatMessages + "[自分のアカウント情報(myaccount.json)が読み込めません]"
                    } else {
                        // startClientでJSON文字列を送信(さっき取得したIPアドレス宛)
                        val success = startClient(groupOwnerAddress, jsonStringToSend)
                        if (success) {
                            Log.d("MainActivity", "Client: MyAccount data.")
                            // Compose の状態変数を更新
                            chatMessages = chatMessages + "Me:(Sent):\n$jsonStringToSend"
                        } else {
                            Log.e("MainActivity", "Client: Failed to send file.")
                            chatMessages = chatMessages + "[送信失敗]"
                        }
                    }

                    //４．(クライアント)次にサーバから受信する(返信待ち)(startServer)
                    Log.d("MainActivity", "Client: Noe waiting for data from server...")

                    val serverResult = startServer(this@MainActivity)
                    val receivedJsonContent = serverResult.receivedData
                    if (receivedJsonContent != null) {
                        Log.d("MainActivity", "Client: Received JSON from Server: '$receivedJsonContent'")
                        chatMessages = chatMessages + "Peer:(Reply):\n$receivedJsonContent"
                    } else {
                        Log.w("MainActivity", "Client: No message received from Server or error occurred.")
                        chatMessages = chatMessages + "[サーバからの返信がありません]"
                    }
                    //５．(クライアント)処理完了後，切断
                    Log.d("MainActivity", "Client task finished. Disconnecting.")
                    disconnect()
                }
            } else {
                Log.e("MainActivity", "Group Owner Address is null!")
            }
        }
    }


    // --- BroadcastReceiver ---
    inner class WiFiDirectBroadcastReceiver(
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
        private val activity: io.github.takusan23.androidbleanduwbsample.MainActivity
    ) : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    activity.isWifiP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d("MainActivity", "Wi-Fi P2P Enabled: ${activity.isWifiP2pEnabled}")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!activity.hasRequiredPermissions()) {
                        Log.w("MainActivity", "Receiver: Missing permissions for requestPeers.")
                        return
                    }
                    manager.requestPeers(channel, activity.peerListListener)
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d("MainActivity", "Connection state changed.")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                    if (networkInfo?.isConnected == true) {
                        Log.d("MainActivity", "Device connected. Requesting connection info...")
                        // 接続が確立したら、connectionInfoListener を呼び出す
                        manager.requestConnectionInfo(channel, activity.connectionInfoListener)
                    } else {
                        Log.d("MainActivity", "Device disconnected.")
                        activity.connectionInfo = null // 接続が切れたら情報をクリア
                        activity.chatMessages += "[接続が切れました]"
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    activity.thisDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Log.d("MainActivity", "Device details changed: ${activity.thisDevice?.deviceName}")
                }
            }
        }
    }

    private fun disconnect() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("MainActivity", "removeGroup onSuccess")
                // 接続情報もクリアする
                connectionInfo = null
            }

            override fun onFailure(reason: Int) {
                Log.d("MainActivity", "removeGroup onFailure: $reason")
            }
        })
    }

    /**
     * テスト用にダミーの myaccount.json を作成する
     */
    private fun createDummyMyAccountFile() {
        val dir = File(filesDir, "MyAccount")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val file = File(dir, "myaccount.json")

        // ファイルがまだ存在しない場合のみ、ダミーデータを作成
        if (!file.exists()) {
            Log.d("MainActivity", "Dummy myaccount.json not found. Creating one...")
            try {
                // ここにテストで送信したいJSONの内容を書く
                val dummyJson = """
                    {
                        "name": "Taro Test",
                        "id": "test_user_01"
                    }
                """.trimIndent()

                file.writeText(dummyJson)
                Log.d("MainActivity", "Dummy myaccount.json created at ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to create dummy file", e)
            }
        } else {
            Log.d("MainActivity", "Dummy myaccount.json already exists.")
        }
    }

    /**
     * Activityが破棄されるときに呼ばれる
     */
    override fun onDestroy() {
        super.onDestroy()
        //グループを削除して終了する
        disconnect()
    }

}
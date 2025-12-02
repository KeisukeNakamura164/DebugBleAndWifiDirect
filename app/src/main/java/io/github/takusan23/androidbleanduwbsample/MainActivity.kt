package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControleeScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.ControllerScreen
import io.github.takusan23.androidbleanduwbsample.ui.screen.HomeScreen
import io.github.takusan23.androidbleanduwbsample.ui.theme.AndroidBleAndUwbSampleTheme



import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily


class MainActivity : ComponentActivity() {
    // aware追加分
    companion object {
        lateinit var awareManager: AwareManager
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // aware追加分
        awareManager = AwareManager(this)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidBleAndUwbSampleTheme {
                MainScreen(awareManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        awareManager.close()
    }
}

@Composable
private fun MainScreen(awareManager: AwareManager) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onControllerClick = { navController.navigate("controller") },
                onControleeClick = { navController.navigate("controlee") }
            )
        }
        composable("controller") {
            ControllerScreen()
        }
        composable("controlee") {
            ControleeScreen()
        }
    }
}


@Composable
fun WifiDirectApp(
    isWifiP2pEnabled: Boolean,
    thisDevice: WifiP2pDevice?,
    peers: List<WifiP2pDevice>,
    servicePeers: List<WifiP2pDevice>,
    onDiscoverPeers: () -> Unit,
    onDiscoverServices: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    connectionInfo: WifiP2pInfo?,
    chatMessages: List<String>,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- 1. ヘッダー情報 ---
        Text(
            text = "Wi-Fi Direct P2P Demo",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 自分の端末情報
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = "Status: ${if (isWifiP2pEnabled) "Enabled" else "Disabled"}")
                Text(text = "My Device: ${thisDevice?.deviceName ?: "Unknown"}")
                Text(text = "Address: ${thisDevice?.deviceAddress ?: "Unknown"}")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. 操作ボタン ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 通常探索（あまり使わないかもですが念のため）
            Button(onClick = onDiscoverPeers) {
                Text("Scan Peers")
            }
            // ★今回の主役：サービス探索
            Button(onClick = onDiscoverServices) {
                Text("Scan Services")
            }
        }

        // 切断ボタン（接続中のみ表示あるいは常時表示）
        if (connectionInfo != null) {
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Disconnect / Stop Group")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ★追加: 通常のスキャンで見つかった相手を表示するリスト
        Text(text = "Found Peers (Normal Scan):", fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp) // 高さを指定
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
        ) {
            if (peers.isEmpty()) {
                item {
                    Text(
                        text = "No peers found via Scan Peers...",
                        modifier = Modifier.padding(8.dp),
                        color = Color.Gray
                    )
                }
            } else {
                items(peers) { device ->
                    // PeerItemを使って表示
                    PeerItem(device = device, onConnect = onConnectPeer)
                    Divider()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 3. 発見したデバイスリスト ---
        Text(text = "Found Service Peers:", fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp) // リストの高さを制限
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
        ) {
            if (servicePeers.isEmpty()) {
                item {
                    Text(
                        text = "No services found yet...",
                        modifier = Modifier.padding(8.dp),
                        color = Color.Gray
                    )
                }
            } else {
                items(servicePeers) { device ->
                    PeerItem(device = device, onConnect = onConnectPeer)
                    Divider()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 4. 接続状態とログ（ここにJSONが出ます） ---
        Text(text = "Connection & Logs:", fontWeight = FontWeight.Bold)

        // 接続ステータス表示
        if (connectionInfo != null) {
            val statusText = if (connectionInfo.groupFormed) {
                if (connectionInfo.isGroupOwner) "Host (Group Owner)" else "Client"
            } else {
                "Disconnected"
            }
            Text(text = "Current State: $statusText", color = MaterialTheme.colorScheme.secondary)
        }

        // ログ表示エリア (チャット形式)
        Box(
            modifier = Modifier
                .weight(1f) // 残りのスペースを全て使う
                .fillMaxWidth()
                .background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                reverseLayout = true // 最新のログを下に表示したい場合はfalse, 上ならtrue (今回はリストの末尾に追加されているのでfalse推奨だが、スクロール挙動による)
                // chatMessagesは末尾に追加されているので、reverseLayout=falseだと上から順、trueだと下から順(ただしリストの順序も逆に見える)。
                // ここでは単純に上から順に表示します。
            ) {
                // リストを逆順にして、新しいものが下に来るようにする、または自動スクロールが必要ですが、
                // 簡易的にリストの最後が一番下に来るように表示します。
                items(chatMessages.reversed()) { msg ->
                    LogItem(message = msg)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun PeerItem(device: WifiP2pDevice, onConnect: (WifiP2pDevice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect(device) } // タップで接続
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = device.deviceName ?: "Unknown Name", fontWeight = FontWeight.Bold)
            Text(text = device.deviceAddress, fontSize = 12.sp, color = Color.Gray)
            Text(text = getDeviceStatus(device.status), fontSize = 12.sp)
        }
        Button(onClick = { onConnect(device) }, modifier = Modifier.height(36.dp)) {
            Text("Connect")
        }
    }
}

@Composable
fun LogItem(message: String) {
    // JSONなどの長いテキストを見やすく表示
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(8.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace // 等幅フォントでJSONを見やすく
        )
    }
}

// ステータスコードを文字列に変換するヘルパー
fun getDeviceStatus(status: Int): String {
    return when (status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}
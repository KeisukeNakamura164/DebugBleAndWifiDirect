package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class AwareManager(private val context: Context) {

    companion object {
        // ヘッダー(3byte)分を引いたデータサイズ
        private const val CHUNK_SIZE = 240
    }

    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    // サービスID
    private val SERVICE_ID = "42a3302d-83ca-44b4-9b5a-e5f369bb673a"

    var onMessageReceivedListener: ((String) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receivedBuffers = ConcurrentHashMap<Int, ConcurrentHashMap<Int, ByteArray>>()
    private var currentMessageIdCounter = 0

    // ★追加: 接続中かどうかを判定するフラグ
    private var isConnecting = false
    // ★追加: 接続完了待ちのタスクリスト
    private val pendingTasks = mutableListOf<(WifiAwareSession) -> Unit>()

    /**
     * セッション確保用メソッド（内部利用）
     * セッションがあれば即実行、なければ接続してから実行
     */
    private fun ensureSession(task: (WifiAwareSession) -> Unit) {
        // 既にセッションがあれば即実行
        if (awareSession != null) {
            task(awareSession!!)
            return
        }

        // タスクを予約リストに追加
        pendingTasks.add(task)

        // 既に接続処理中なら、完了を待つだけなのでここで終了
        if (isConnecting) return

        if (!hasPermissions() || wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            println("Wi-Fi Aware 利用不可または権限不足")
            return
        }

        isConnecting = true
        try {
            wifiAwareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    super.onAttached(session)
                    println("セッション確立成功！")
                    awareSession = session
                    isConnecting = false

                    // 待機していたタスク（startDiscoveryなど）を順次実行
                    pendingTasks.forEach { it(session) }
                    pendingTasks.clear()
                }

                override fun onAttachFailed() {
                    println("セッション確立失敗")
                    isConnecting = false
                    pendingTasks.clear()
                }
            }, null)
        } catch (e: Exception) {
            println("接続エラー: ${e.message}")
            isConnecting = false
        }
    }

    // UI側との互換性のため残していますが、実質 ensureSession への委譲です
    fun connect() {
        ensureSession {
            // 接続だけが目的の場合は特に何もしない
        }
    }

    private fun hasPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineLocation && nearbyDevices
    }

    /**
     * 双方向通信を開始します。
     * セッションが未確立の場合は、自動的に接続してから開始します。
     */
    fun startDiscovery() {
        // ★修正: ensureSession を経由して実行することで、1回目のタップでも確実に動作させます
        ensureSession { session ->
            startPublishing(session)
            startSubscribing(session)
        }
    }

    private fun startPublishing(session: WifiAwareSession) {
        val pubConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_ID)
            .build()

        try {
            session.publish(pubConfig, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    println("Publish開始: 探索待ち...")
                    publishSession = session
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    processReceivedChunk(message, peerHandle) { receivedText ->
                        val log = "受信(Pub): $receivedText"
                        println(log)
                        scope.launch(Dispatchers.Main) {
                            onMessageReceivedListener?.invoke(log)
                        }
                        // 返信
                        sendMultipartMessage(publishSession, peerHandle, "BLE通信のデバックに使用されます。ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~永鬱驫鷗髙﨑壱弐参〇々〆")
                    }
                }
            }, null)
        } catch (e: SecurityException) {
            println("Publishエラー: 権限不足")
        }
    }

    private fun startSubscribing(session: WifiAwareSession) {
        val subConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_ID)
            .build()

        try {
            session.subscribe(subConfig, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    println("Subscribe開始: 探索中...")
                    subscribeSession = session
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: MutableList<ByteArray>
                ) {
                    println("すれ違い成功！相手を発見しました。")
                    scope.launch(Dispatchers.Main) {
                        onMessageReceivedListener?.invoke("★ すれ違い成功！相手を発見")
                    }
                    // 送信
                    sendMultipartMessage(subscribeSession, peerHandle, "BLE通信のデバックに使用されます。ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~永鬱驫鷗髙﨑壱弐参〇々〆")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    processReceivedChunk(message, peerHandle) { receivedText ->
                        val log = "受信(Sub): $receivedText"
                        println(log)
                        scope.launch(Dispatchers.Main) {
                            onMessageReceivedListener?.invoke(log)
                        }
                    }
                }
            }, null)
        } catch (e: SecurityException) {
            println("Subscribeエラー: 権限不足")
        }
    }

    private fun sendMultipartMessage(
        session: DiscoverySession?,
        peerHandle: PeerHandle,
        message: String
    ) {
        if (session == null) return

        scope.launch(Dispatchers.IO) {
            val dataBytes = message.toByteArray(StandardCharsets.UTF_8)
            val headerSize = 3
            val maxPayload = CHUNK_SIZE - headerSize
            val totalChunks = (dataBytes.size + maxPayload - 1) / maxPayload

            val msgId = currentMessageIdCounter
            currentMessageIdCounter = (currentMessageIdCounter + 1) % 256

            var offset = 0
            for (seqNum in 1..totalChunks) {
                val size = min(maxPayload, dataBytes.size - offset)
                val chunk = ByteArray(headerSize + size).apply {
                    this[0] = msgId.toByte()
                    this[1] = seqNum.toByte()
                    this[2] = totalChunks.toByte()
                    System.arraycopy(dataBytes, offset, this, headerSize, size)
                }

                try {
                    session.sendMessage(peerHandle, 0, chunk)
                    delay(50)
                } catch (e: Exception) {
                    println("送信失敗: ${e.message}")
                }
                offset += size
            }
        }
    }

    private fun processReceivedChunk(
        message: ByteArray,
        peerHandle: PeerHandle,
        onComplete: (String) -> Unit
    ) {
        if (message.size < 3) return

        val msgId = message[0].toUByte().toInt()
        val seqNum = message[1].toUByte().toInt()
        val totalChunks = message[2].toUByte().toInt()

        val dataSize = message.size - 3
        val data = ByteArray(dataSize)
        System.arraycopy(message, 3, data, 0, dataSize)

        val chunkMap = receivedBuffers.getOrPut(msgId) { ConcurrentHashMap() }
        chunkMap[seqNum] = data

        if (chunkMap.size == totalChunks) {
            val completedMap = receivedBuffers.remove(msgId) ?: return
            val sortedKeys = completedMap.keys.sorted()
            val totalSize = sortedKeys.sumOf { completedMap[it]?.size ?: 0 }
            val combinedData = ByteArray(totalSize)

            var currentPosition = 0
            for (key in sortedKeys) {
                val chunk = completedMap[key] ?: continue
                System.arraycopy(chunk, 0, combinedData, currentPosition, chunk.size)
                currentPosition += chunk.size
            }

            val fullText = String(combinedData, StandardCharsets.UTF_8)
            onComplete(fullText)
        }
    }

    fun close() {
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        scope.cancel()
        receivedBuffers.clear()
        // セッション破棄時はnullに戻す
        awareSession = null
        isConnecting = false
        pendingTasks.clear()
    }
}

@Composable
fun MessageCard(text: String) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 16.sp
        )
    }
}
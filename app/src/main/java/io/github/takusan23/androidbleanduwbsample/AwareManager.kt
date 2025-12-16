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
import kotlinx.coroutines.* // 追加: コルーチン用
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

    private val SERVICE_ID = "42a3302d-83ca-44b4-9b5a-e5f369bb673a"

    var onMessageReceivedListener: ((String) -> Unit)? = null

    // ★追加: 非同期処理用スコープ
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ★追加: 受信バッファ (MsgID -> (SeqNum -> Data))
    private val receivedBuffers = ConcurrentHashMap<Int, ConcurrentHashMap<Int, ByteArray>>()

    // ★追加: 送信メッセージIDカウンター
    private var currentMessageIdCounter = 0

    fun connect() {
        if (!hasPermissions() || wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            return
        }

        try {
            wifiAwareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    super.onAttached(session)
                    awareSession = session
                    println("セッション確立成功！")
                }

                override fun onAttachFailed() {
                    println("セッション確立失敗")
                }
            }, null)
        } catch (e: Exception) {
            println("接続エラー: ${e.message}")
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

    fun startPublishing() {
        val session = awareSession ?: return
        if (!hasPermissions()) return

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_ID)
            .build()

        try {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    println("Publish開始: 探索待ち...")
                    publishSession = session
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    // ★変更: チャンク処理へ委譲
                    processReceivedChunk(message, peerHandle) { receivedText ->
                        val log = "受信(Pub): $receivedText"
                        println(log)

                        // メインスレッドでUI更新
                        scope.launch(Dispatchers.Main) {
                            onMessageReceivedListener?.invoke(log)
                        }

                        // 返信 (分割送信を使用)
                        sendMultipartMessage(publishSession, peerHandle, "BLE通信のデバックに使用されます。ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~永鬱驫鷗髙﨑壱弐参〇々〆")
                    }
                }
            }, null)
        } catch (e: SecurityException) {
            println("Publishエラー: 権限不足")
        }
    }

    fun startSubscribing() {
        val session = awareSession ?: return
        if (!hasPermissions()) return

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_ID)
            .build()

        try {
            session.subscribe(config, object : DiscoverySessionCallback() {
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

                    // ★変更: 分割送信を使用
                    sendMultipartMessage(subscribeSession, peerHandle, "BLE通信のデバックに使用されます。ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~永鬱驫鷗髙﨑壱弐参〇々〆")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    // ★変更: チャンク処理へ委譲
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

    // ---------------------------------------------------------
    // ★追加: 分割送信ロジック
    // ---------------------------------------------------------
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
            // IDを0-255でループさせる
            currentMessageIdCounter = (currentMessageIdCounter + 1) % 256

            var offset = 0
            for (seqNum in 1..totalChunks) {
                val size = min(maxPayload, dataBytes.size - offset)
                val chunk = ByteArray(headerSize + size).apply {
                    // ヘッダー作成 [MsgID, SeqNum, Total]
                    this[0] = msgId.toByte()
                    this[1] = seqNum.toByte()
                    this[2] = totalChunks.toByte()
                    // データコピー
                    System.arraycopy(dataBytes, offset, this, headerSize, size)
                }

                try {
                    // 送信 (IDはシステム用のユニークIDとして適当な値を入れる)
                    session.sendMessage(peerHandle, 0, chunk)
                    // ★重要: 連続送信するとパケット落ちしやすいので少し待機
                    delay(30)
                } catch (e: Exception) {
                    println("送信失敗: ${e.message}")
                }
                offset += size
            }
        }
    }

    // ---------------------------------------------------------
    // ★追加: 受信・再構築ロジック
    // ---------------------------------------------------------
    private fun processReceivedChunk(
        message: ByteArray,
        peerHandle: PeerHandle,
        onComplete: (String) -> Unit
    ) {
        if (message.size < 3) return // ヘッダー不足

        // ヘッダー読み取り
        val msgId = message[0].toUByte().toInt()
        val seqNum = message[1].toUByte().toInt()
        val totalChunks = message[2].toUByte().toInt()

        // データ部分を取り出し
        val dataSize = message.size - 3
        val data = ByteArray(dataSize)
        System.arraycopy(message, 3, data, 0, dataSize)

        // バッファに保存
        val chunkMap = receivedBuffers.getOrPut(msgId) { ConcurrentHashMap() }
        chunkMap[seqNum] = data

        // 全てのチャンクが揃ったか確認
        if (chunkMap.size == totalChunks) {
            val completedMap = receivedBuffers.remove(msgId) ?: return

            // 結合処理
            val sortedKeys = completedMap.keys.sorted()
            val totalSize = sortedKeys.sumOf { completedMap[it]?.size ?: 0 }
            val combinedData = ByteArray(totalSize)

            var currentPosition = 0
            for (key in sortedKeys) {
                val chunk = completedMap[key] ?: continue
                System.arraycopy(chunk, 0, combinedData, currentPosition, chunk.size)
                currentPosition += chunk.size
            }

            // 文字列に戻してコールバック
            val fullText = String(combinedData, StandardCharsets.UTF_8)
            onComplete(fullText)
        }
    }

    fun close() {
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        scope.cancel() // コルーチンのキャンセル
        receivedBuffers.clear()
    }
}

// (以下 MessageCard は変更なし)
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
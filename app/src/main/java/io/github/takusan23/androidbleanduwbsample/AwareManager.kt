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
import java.nio.charset.StandardCharsets
import kotlin.text.toByteArray

class AwareManager(private val context: Context) {

    // システムからWIFI_AWARE_SERVICEをもってきて(getSystemService)、WifiAwareManager型にしている(as)
    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    // 通信セッション
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private val SERVICE_ID = "42a3302d-83ca-44b4-9b5a-e5f369bb673a"

    // ★追加: UIにメッセージを伝えるための「連絡係」。画面(MainActivityにメッセージを届ける)
    var onMessageReceivedListener: ((String) -> Unit)? = null

    // システムにwifiAware機能を使わせてもらう関数
    fun connect() {
        //権限チェックなど
        if (!hasPermissions() || wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            return
        }

        // wifiAware機能を使わせて！と申請(attach)
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

    // 権限チェック
    private fun hasPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineLocation && nearbyDevices
    }

    // 発信する (Publish)
    fun startPublishing() {
        val session = awareSession ?: return
        if (!hasPermissions()) return

        // 探す対象のサービスIDを設定
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_ID)
            .build()

        try {
            // 発信開始
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    println("Publish開始: 探索待ち...")
                    publishSession = session
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val receivedText = String(message, StandardCharsets.UTF_8)
                    val log = "受信(Pub): $receivedText"
                    println(log)

                    // ★UIに通知
                    onMessageReceivedListener?.invoke(log)

                    // 返信する
                    publishSession?.sendMessage(
                        peerHandle,
                        0,
                        "こちらこそこんにちは！".toByteArray(StandardCharsets.UTF_8)
                    )
                }
            }, null)
        } catch (e: SecurityException) {
            println("Publishエラー: 権限不足")
        }
    }

    // 3. 探索する (Subscribe)
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
                    // ★発見したこともUIに通知
                    onMessageReceivedListener?.invoke("★ すれ違い成功！相手を発見")

                    subscribeSession?.sendMessage(
                        peerHandle,
                        0,
                        "こんにちは！すれ違いましたね。".toByteArray(StandardCharsets.UTF_8)
                    )
                }

                // Subscribe側も返信を受け取れるようにする
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val receivedText = String(message, StandardCharsets.UTF_8)
                    val log = "受信(Sub): $receivedText"
                    println(log)

                    // ★UIに通知
                    onMessageReceivedListener?.invoke(log)
                }
            }, null)
        } catch (e: SecurityException) {
            println("Subscribeエラー: 権限不足")
        }
    }

    fun close() {
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
    }
}


// Wifi Awareで追加
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
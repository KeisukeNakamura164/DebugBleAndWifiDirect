package io.github.takusan23.androidbleanduwbsample

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * BLE通信を管理するクラス
 * Service機能を取り除き、純粋なロジックのみを保持
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
    }

    private var connectionRetryCount = 0
    private val MAX_CONNECT_RETRIES = 3 // 最大3回まで再試行
    private var currentTargetDevice: BluetoothDevice? = null // 現在接続を試みているデバイスを記憶

    // 状態の保持と公開 (ViewModelが監視する)

    // スキャン結果
    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    // スキャン結果の重複を管理するためのSet
    private val discoveredDevices = mutableSetOf<String>()

    // 受信メッセージ
    private val _receivedMessage = MutableStateFlow<String>("")
    val receivedMessage: StateFlow<String> = _receivedMessage.asStateFlow()

    // スキャン中フラグ
    private val _isScanning = MutableStateFlow<Boolean>(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // アドバタイズ中フラグ
    private val _isAdvertising = MutableStateFlow<Boolean>(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()


    // アドバタイズで使っているサービスUUID
    private val SERVICE_UUID: UUID = UUID.fromString("42a3302d-83ca-44b4-9b5a-e5f369bb673a")

    // データを書き込むためのキャラクタリスティックUUID
    private val MESSAGE_CHAR_UUID: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")

    private val REPLY_CHAR_UUID: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1215")

    // BluetoothAdapterの準備
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // BLEスキャナの取得
    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    // BLEアドバタイズの取得
    private val advertiser by lazy {
        bluetoothAdapter?.bluetoothLeAdvertiser
    }

    // GATTサーバー関連
    private var gattServer: BluetoothGattServer? = null

    // GATTクライアント関連
    private var clientGatt: BluetoothGatt? = null

    private var messageCharacteristic: BluetoothGattCharacteristic? = null // ★書き込み用キャラクタリスティックを保持

    // ネゴシエートされたMTUサイズ (デフォルトは23)
    private var currentMtu = 23

    // 送信データキュー (送信待ちのチャンク)
    private val dataToSend = mutableListOf<ByteArray>()

    // 現在送信中のチャンクのインデックス
    private var chunkIndex = 0

    // 送信中フラグ
    private var isSendingData = false

    // サーバー送信関連
    private val serverDataToSend = mutableListOf<ByteArray>()
    private var serverChunkIndex = 0
    private var isServerSendingData = false
    private var subscribedDevice: BluetoothDevice? = null

    private val replyDataBuffer = ConcurrentHashMap<Int, ByteArray>()

    // 受信データバッファ (サーバー側) - スレッドセーフなMapを使用
    private val receivedDataBuffer = ConcurrentHashMap<Int, ByteArray>()

    // ★追加: 最近通信したデバイスのアドレスと時間を記録するマップ
    private val recentConnectionHistory = ConcurrentHashMap<String, Long>()

    // ★追加: 再接続を禁止する時間 (ミリ秒) 。60分 = 3600,000ms
    //todo この箇所は試験のため　削除
    //private val RECONNECT_COOLDOWN_MS = 60 * 60 * 1000L * 0

    // コルーチンスコープ (Managerの生存期間に合わせるためSupervisorJobを使用)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    //todo この箇所が送信するメッセージ
    // 送信するメッセージを保持する変数
    private var messageToSendContent: String = "BLE通信のデバックに使用されます。ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロヮワヰヱヲンヴヵヶヷヸヹヺabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#\$%&'()*+,-./:;<=>?@[]^_`{|}~永鬱驫鷗髙﨑壱弐参〇々〆"

    // 外部から、このメッセージを書き換えるための関数
    fun setCustomMessage(message: String) {
        Log.d(TAG, "送信メッセージを更新しました: $message")
        this.messageToSendContent = message
    }

    // --- 初期化ブロック (旧 onCreate) ---
    init {
        Log.d(TAG, "BleManager initialized.")
    }

    // --- 終了処理 (旧 onDestroy) ---
    fun release() {
        Log.d(TAG, "release: BleManagerのリソースを解放します。")
        stopScan()
        stopAdvertising()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "release: BLUETOOTH_CONNECT 権限がないため、GATTリソースを解放できません。")
        } else {
            try {
                gattServer?.close()
                clientGatt?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "release時にGATTリソースの解放に失敗しました", e)
            }
        }
        // コルーチンのキャンセル
        scope.cancel()
    }

    // BLEコールバック

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device.address

            // 既に誰かと接続中(clientGatt != null)でなければ、見つけた端末に即接続しにいく
            if (clientGatt == null) {
                Log.d(TAG, "ターゲットを発見: $deviceAddress -> 接続を開始します")
                stopScan()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "スキャン失敗: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GATT_SERVER", "クライアントが接続しました: ${device?.address}")
                // 相手から接続されたので、もう自分から探しに行く必要はない
                stopScan()
                // 1対1通信なら、他の人に見つからないようにアドバタイズも止める（任意）
                stopAdvertising()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GATT_SERVER", "クライアントが切断しました: ${device?.address}")
                currentMtu = 23 //サーバー側も接続切れたらMTUをデフォルトに戻す
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            Log.d("GATT_SERVER", "サービスが追加されました: ${service?.uuid}")
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Log.d("GATT_SERVER", "MTUサイズが $mtu に変更されました (デバイス: ${device?.address})")
            currentMtu = mtu
        }

        // クライアントから書き込み要求があったとき
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
            if (characteristic?.uuid == MESSAGE_CHAR_UUID && value != null && value.isNotEmpty()) {

                val sequenceNumber = value[0].toInt() // 先頭バイトをシーケンス番号として取得

                if (sequenceNumber == 0) {
                    // シーケンス番号が0番 (EOFマーカー) を受信した場合
                    Log.d("GATT_SERVER", "EOFチャンク受信。データを結合します。")

                    // 結合
                    reconstructAndDisplayMessage()
                    receivedDataBuffer.clear() // バッファクリア

                    // 返信処理を開始
                    prepareAndSendReply(device, messageToSendContent)

                } else {
                    // 通常のデータチャンク (シーケンス番号が1番以降) を受信した場合
                    val dataChunk = value.copyOfRange(1, value.size) // データ本体を取得
                    Log.d(
                        "GATT_SERVER",
                        "チャンク受信: 番号=$sequenceNumber, サイズ=${dataChunk.size} bytes"
                    )
                    receivedDataBuffer[sequenceNumber] = dataChunk // バッファに格納

                }


                // 応答が必要な場合、成功したことをクライアントに返す
                if (responseNeeded) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e("GATT_SERVER", "応答を送信するためのCONNECT権限がありません。")
                        return
                    }
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(
                device,
                requestId,
                descriptor,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            // CCCD (クライアントキャラクタリスティック設定デスクリプタ) かどうかをチェック
            if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                if (value != null) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        Log.d("GATT_SERVER", "クライアントが通知の購読を開始しました: ${device?.address}")
                        subscribedDevice = device // ★ 通知先のデバイスを保存
                    } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        Log.d("GATT_SERVER", "クライアントが通知の購読を停止しました。")
                        subscribedDevice = null // ★ 通知先をクリア
                    }
                }
            }

            // 応答
            if (responseNeeded) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            super.onNotificationSent(device, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 1つのチャンクが送信完了した
                serverChunkIndex++ // 次のチャンクへ

                if (isServerSendingData && serverChunkIndex < serverDataToSend.size) {
                    // まだ送るデータがある場合、次のチャンクを送信
                    //ディレイを少し入れることで安定化する
                    scope.launch {
                        delay(20) // 20ms待機
                        sendReplyChunk(device)
                    }
                } else if (isServerSendingData) {
                    // 全チャンク送信完了
                    Log.d("GATT_SERVER", "全返信チャンク送信完了 (onNotificationSent)。")
                    isServerSendingData = false
                }
            } else {
                Log.e("GATT_SERVER", "通知の送信に失敗: $status")
                isServerSendingData = false
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d("BLE", "Advertise started successfully.")
        }


        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            // エラーコードで原因がわかります
            Log.e("BLE", "Advertise failed with error code: $errorCode")
        }
    }

    //  --- GATTクライアントコールバック ---
    private val gattClientCallback = object : BluetoothGattCallback() {
        // 接続状態が変わった
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            // Android 12以上で、BLUETOOTH_CONNECT権限があるかチェック
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.e("GATT_CLIENT", "onConnectionStateChange: 権限がありません。")
                return
            }


            // 権限がある場合のみ、以下の処理が実行される
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("GATT_CLIENT", "GATTサーバーに接続しました。サービスを検索します...")
                    // 接続に成功したらリトライ回数をリセット
                    connectionRetryCount = 0
                    // 接続に成功したら、サービスを検索
                    gatt?.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("GATT_CLIENT", "GATTサーバーから切断しました。")

                    handleDisconnection()
                }
            } else {
                Log.e("GATT_CLIENT", "接続エラー: $status")
                handleDisconnection()
            }
        }

        private fun handleDisconnection() {
            // リソース解放
            messageCharacteristic = null
            try {
                clientGatt?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "権限がないため close() に失敗しましたが、無視します。")
            }
            clientGatt = null

            // currentTargetDevice が null でない場合は「まだ通信したい（意図しない切断）」と判断
            if (currentTargetDevice != null && connectionRetryCount < MAX_CONNECT_RETRIES) {
                connectionRetryCount++
                Log.w(
                    "GATT_CLIENT",
                    "予期せぬ切断。再接続を試みます ($connectionRetryCount/$MAX_CONNECT_RETRIES)"
                )

                // 少し待ってから再接続 (即座だと失敗しやすいため)
                scope.launch {
                    delay(500) // 0.5秒待機
                    // ターゲットがまだ有効なら再接続
                    currentTargetDevice?.let { device ->
                        connectToDevice(device)
                    }
                }
            } else {
                Log.d("GATT_CLIENT", "再接続を諦めました。または意図的な切断です。")
                currentTargetDevice = null // 諦めるのでクリア
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            //権限チェックを追加
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.e("GATT_CLIENT", "onMtuChanged: 権限がありません。")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("GATT_CLIENT", "MTUサイズが $mtu バイトに変更されました。")
                //  変更されたMTUサイズを保存
                currentMtu = mtu
                gatt?.discoverServices()
            } else {
                Log.w(
                    "GATT_CLIENT",
                    "MTUサイズ変更要求失敗: $status 。デフォルトMTU($currentMtu)でサービス検索します..."
                )
                gatt?.discoverServices()
            }
        }

        // サービスが発見された
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("GATT_CLIENT", "サービスを発見しました。")
                // 目的のサービスを探す
                val service = gatt?.getService(SERVICE_UUID)
                if (service == null) {
                    // サービスが見つからない場合
                    Log.e("GATT_CLIENT", "目的のサービス(${SERVICE_UUID})が見つかりません。切断します。")
                    disconnectClient()
                    return // ここで処理を終了
                }

                // 目的のキャラクタリスティックを探す
                messageCharacteristic = service.getCharacteristic(MESSAGE_CHAR_UUID)
                if (messageCharacteristic == null) {
                    // キャラクタリスティックが見つからない場合
                    Log.e(
                        "GATT_CLIENT",
                        "目的のキャラクタリスティック(${MESSAGE_CHAR_UUID})が見つかりません。切断します。"
                    )
                    disconnectClient()
                    return // ここで処理を終了
                }

                // 返信用キャラクタリスティックを探す
                val replyCharacteristic = service.getCharacteristic(REPLY_CHAR_UUID)
                if (replyCharacteristic == null) {
                    Log.e(TAG, "目的のキャラクタリスティック(${REPLY_CHAR_UUID})が見つかりません")
                    disconnectClient()
                    return
                }

                // サーバーからの「返事」の通知を購読する
                Log.d(TAG, "サーバーからの通知(Notify)を購読します...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                gatt.setCharacteristicNotification(replyCharacteristic, true)

                val descriptor =
                    replyCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (descriptor == null) {
                    Log.e(TAG, "CCCDデスクリプタが見つかりません。")
                    disconnectClient()
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
                Log.d(TAG, "GATT_CLIENT: 書き込み・通知購読の準備完了。")
            } else {
                Log.e("GATT_CLIENT", "サービス検索に失敗: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "GATT_CLIENT: 通知の購読(DescriptorWrite)に成功しました。")

                // 通知の購読が完了したので、ここで最初のメッセージを送信する
                if (gatt != null && messageCharacteristic != null) {

                    Log.d(TAG, "GATT_CLIENT: 最初のメッセージを送信します: $messageToSendContent")

                    prepareAndSendData(
                        gatt,
                        messageCharacteristic!!,
                        messageToSendContent
                    )
                } else {
                    Log.e(TAG, "GATT_CLIENT: メッセージ送信に失敗。キャラクタリスティックがnullです。")
                }

            } else {
                Log.e(TAG, "GATT_CLIENT: 通知の購読(DescriptorWrite)に失敗しました: $status")
            }
        }

        // onCharacteristicChanged (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)

            if (characteristic.uuid == REPLY_CHAR_UUID) {
                if (value.isEmpty()) return

                val sequenceNumber = value[0].toInt()

                if (sequenceNumber == 0) {
                    // --- 0番 (EOFマーカー) を受信した場合 ---
                    Log.d("GATT_CLIENT", "サーバーからのEOF受信。返信データを結合します。")

                    // バッファからデータを結合
                    val finalMessage = reconstructReplyMessage()

                    replyDataBuffer.clear() // バッファクリア

                    if (finalMessage.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) { // DB操作はバックグラウンド(IO)で行う
                            Log.d(
                                "GATT_CLIENT",
                                "メッセージをDBに保存します: ${finalMessage.take(20)}..."
                            )
                            //todo メッセージを保存する
                            try {
                                //messageRepository.insertMessage(finalMessage)
                            } catch (e: Exception) {
                                Log.e("GATT_CLIENT", "DB保存に失敗しました", e)
                            }
                        }

                        _receivedMessage.value = "サーバーからの返信: $finalMessage"


                        Log.d("GATT_CLIENT", "返信の受信完了。切断します。")
                        disconnectClient()

                        // 切断したら、またスキャンを再開して次の人を探す
                        //todo 試験のため次の探索を自動でおこなわないように変更
                        /*
                        scope.launch {
                            delay(1000) //
                            startScan()
                        }
                        */
                    }
                } else {
                    // --- 通常のデータチャンク (1番以降) を受信した場合 ---
                    val dataChunk = value.copyOfRange(1, value.size) // データ本体を取得
                    Log.d(
                        "GATT_CLIENT",
                        "返信チャンク受信: 番号=$sequenceNumber, サイズ=${dataChunk.size} bytes"
                    )
                    replyDataBuffer[sequenceNumber] = dataChunk // バッファに格納
                }
            }
        }

        //  API 32 以前用の onCharacteristicChanged
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // API 32 以前の場合、このコールバックが呼ばれる
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value // データ(ByteArray?)を取得

                // 取得したデータが null でないことを確認
                if (value != null) {
                    // 新しい方(API 33+)の onCharacteristicChanged に処理を渡す
                    onCharacteristicChanged(gatt, characteristic, value)
                }
            }
        }

        // データが書き込まれた結果
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("GATT_CLIENT", "チャンク ${chunkIndex + 1}/${dataToSend.size} の送信成功。")
                chunkIndex++ // 次のチャンクへ
                // 次のチャンクがあれば送信、なければ完了
                if (chunkIndex < dataToSend.size) {
                    sendChunk(gatt, characteristic) // 次のチャンクを送信
                } else {
                    Log.d("GATT_CLIENT", "全データ送信完了。")
                    isSendingData = false
                }
            } else {
                Log.e("GATT_CLIENT", "チャンク ${chunkIndex + 1} の送信に失敗: $status")
                isSendingData = false // 送信失敗
                disconnectClient() // エラー発生時も切断
            }
        }
    }


    fun startScan() {
        Log.d(TAG, "startScan() 呼び出し")

        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
        } else {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            Log.e("PermissionError", "スキャンを開始できません。権限がありません。")
            return
        }

        discoveredDevices.clear()
        _scanResults.value = emptyList()
        _isScanning.value = true

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val filters = listOf(scanFilter)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(filters, scanSettings, scanCallback)
        Log.d("BLE", "Scan started with filter for $SERVICE_UUID")
    }

    fun stopScan() {
        Log.d(TAG, "stopScan() 呼び出し")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("PermissionError", "スキャンを停止できません。権限がありません。")
            return
        }
        _isScanning.value = false
        bleScanner?.stopScan(scanCallback)
    }

    private fun prepareAndSendReply(device: BluetoothDevice?, message: String) {
        if (device == null || gattServer == null) {
            Log.w(TAG, "返信先のデバイスが見つかりません。")
            return
        }
        if (isServerSendingData) {
            Log.w(TAG, "現在データを送信中です。")
            return
        }

        isServerSendingData = true
        serverChunkIndex = 0
        serverDataToSend.clear()

        val dataBytes = message.toByteArray(Charsets.UTF_8)
        val chunkSize = (currentMtu - 3 - 1)
        var offset = 0
        var sequenceNumber = 1

        Log.d(TAG, "返信データ分割開始: 全体=${dataBytes.size}bytes, チャンクサイズ=$chunkSize bytes")

        while (offset < dataBytes.size) {
            val remaining = dataBytes.size - offset
            val size = min(chunkSize, remaining)
            val chunk = ByteArray(size + 1)
            chunk[0] = sequenceNumber.toByte()
            System.arraycopy(dataBytes, offset, chunk, 1, size)
            serverDataToSend.add(chunk)
            offset += size
            sequenceNumber++
        }

        serverDataToSend.add(ByteArray(1) { 0.toByte() })
        Log.d(TAG, "${serverDataToSend.size}個のチャンクに分割。返信を開始します...")
        sendReplyChunk(device)
    }

    private fun sendReplyChunk(device: BluetoothDevice?) {
        if (device == null || gattServer == null || !isServerSendingData) return
        if (serverChunkIndex >= serverDataToSend.size) {
            return
        }

        val chunk = serverDataToSend[serverChunkIndex]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "sendReplyChunk: 権限がありません。")
            return
        }

        val characteristic = gattServer
            ?.getService(SERVICE_UUID)
            ?.getCharacteristic(REPLY_CHAR_UUID)

        if (characteristic == null) {
            Log.e(TAG, "返信用のキャラクタリスティックが見つかりません。")
            isServerSendingData = false
            return
        }

        Log.d(
            TAG,
            "返信チャンク ${serverChunkIndex + 1}/${serverDataToSend.size} (${chunk.size} bytes) を送信します..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false, chunk)
        } else {
            @Suppress("DEPRECATION")
            {
                characteristic.value = chunk
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    fun startAdvertising() {
        Log.d(TAG, "startAdvertising() 呼び出し")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED)
        ) {
            return
        }

        setupGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        _isAdvertising.value = true
    }

    fun stopAdvertising() {
        Log.d(TAG, "stopAdvertising() 呼び出し")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED)
        ) {
            Log.e("GATT", "停止に必要な権限がありません。")
            return
        }

        advertiser?.stopAdvertising(advertiseCallback)
        Log.d("BLE", "Advertise stopped.")

        //gattServer?.close()
        //gattServer = null
        _isAdvertising.value = false
    }

    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "connectToDevice() 呼び出し: ${device.address}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("GATT_CLIENT", "BLUETOOTH_CONNECT 権限がありません。")
            return
        }
        clientGatt?.disconnect()
        clientGatt?.close()

        Log.d("GATT_CLIENT", "デバイスに接続中...: ${device.address}")
        clientGatt = device.connectGatt(context, false, gattClientCallback)
    }

    fun disconnectClient() {
        Log.d(TAG, "disconnectClient() 呼び出し")

        currentTargetDevice = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("GATT_CLIENT", "BLUETOOTH_CONNECT 権限がありません。")
            return
        }
        Log.d("GATT_CLIENT", "クライアントを切断します。")
        clientGatt?.disconnect()
    }

    fun clearMessage() {
        Log.d(TAG, "受信メッセージをクリアします。")
        _receivedMessage.value = ""
    }

    private fun reconstructAndDisplayMessage() {
        if (receivedDataBuffer.isEmpty()) return

        val sortedKeys = receivedDataBuffer.keys.sorted()
        val totalSize = sortedKeys.sumOf { receivedDataBuffer[it]?.size ?: 0 }
        val combinedData = ByteArray(totalSize)
        var currentPosition = 0
        sortedKeys.forEach { key ->
            val chunk = receivedDataBuffer[key]
            if (chunk != null) {
                System.arraycopy(chunk, 0, combinedData, currentPosition, chunk.size)
                currentPosition += chunk.size
            }
        }

        val finalMessage = combinedData.toString(Charsets.UTF_8)
        Log.d("GATT_SERVER", "再構築されたメッセージ: $finalMessage")

        _receivedMessage.value = finalMessage

        scope.launch(Dispatchers.IO) {
            // NOTE: saveOtherAccountの実装に合わせて引数を調整してください。
            // 他のクラスファイルにある saveOtherAccount が String を受け取れるようにするか、
            // ここで型変換が必要です。
            Log.d(TAG, "メッセージをデータベースに保存する処理: ${finalMessage.take(20)}...")
        }
    }

    private fun reconstructReplyMessage(): String {
        if (replyDataBuffer.isEmpty()) return ""

        return try {
            val sortedKeys = replyDataBuffer.keys.sorted()
            val totalSize = sortedKeys.sumOf { replyDataBuffer[it]?.size ?: 0 }
            val combinedData = ByteArray(totalSize)
            var currentPosition = 0
            sortedKeys.forEach { key ->
                val chunk = replyDataBuffer[key]
                if (chunk != null) {
                    System.arraycopy(chunk, 0, combinedData, currentPosition, chunk.size)
                    currentPosition += chunk.size
                }
            }

            combinedData.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("GATT_CLIENT", "返信データの再構築に失敗", e)
            ""
        }
    }

    private fun prepareAndSendData(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        message: String
    ) {
        if (isSendingData) {
            Log.w("GATT_CLIENT", "現在データを送信中です。")
            return
        }
        isSendingData = true
        chunkIndex = 0
        dataToSend.clear()

        val dataBytes = message.toByteArray(Charsets.UTF_8)
        val chunkSize = currentMtu - 3 - 1
        var offset = 0
        var sequenceNumber = 1

        Log.d(
            "GATT_CLIENT",
            "データ分割開始: 全体=${dataBytes.size}bytes, チャンクサイズ=$chunkSize bytes (MTU=$currentMtu)"
        )

        while (offset < dataBytes.size) {
            val remaining = dataBytes.size - offset
            val size = min(chunkSize, remaining)
            val chunk = ByteArray(size + 1)
            chunk[0] = sequenceNumber.toByte()
            System.arraycopy(dataBytes, offset, chunk, 1, size)
            dataToSend.add(chunk)

            offset += size
            sequenceNumber++
        }

        dataToSend.add(ByteArray(1) { 0.toByte() })
        Log.d("GATT_CLIENT", "${dataToSend.size}個のチャンクに分割しました。送信を開始します...")
        sendChunk(gatt, characteristic)
    }

    private fun setupGattServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("GATT_SERVER", "BLUETOOTH_CONNECT 権限がありません。")
            return
        }

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(messageCharacteristic)

        val replyCharacteristic = BluetoothGattCharacteristic(
            REPLY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        replyCharacteristic.addDescriptor(cccDescriptor)
        service.addCharacteristic(replyCharacteristic)

        gattServer?.addService(service)
    }

    private fun sendChunk(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        if (gatt == null || characteristic == null || !isSendingData) return
        if (chunkIndex >= dataToSend.size) {
            Log.e("GATT_CLIENT", "送信するチャンクがありません。")
            isSendingData = false
            return
        }

        val chunk = dataToSend[chunkIndex]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("GATT_CLIENT", "sendChunk: BLUETOOTH_CONNECT 権限がありません。")
            return
        }

        Log.d(
            "GATT_CLIENT",
            "チャンク ${chunkIndex + 1}/${dataToSend.size} (${chunk.size} bytes) を送信します..."
        )

        var retryCount = 0
        var success = false
        val maxRetries = 3

        while (retryCount < maxRetries && !success) {
            try {
                success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val statusCode = gatt.writeCharacteristic(
                        characteristic,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    statusCode == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }

                if (!success) {
                    Log.w(
                        "GATT_CLIENT",
                        "書き込み受付拒否 (Busy/Error)。リトライします... ($retryCount/$maxRetries)"
                    )
                    retryCount++
                    Thread.sleep(20)
                }
            } catch (e: Exception) {
                Log.e("GATT_CLIENT", "書き込み中に例外発生", e)
                retryCount++
            }
        }

        if (!success) {
            Log.e("GATT_CLIENT", "致命的エラー: 何度試行しても書き込めませんでした。切断します。")
            isSendingData = false
            disconnectClient()
        }
    }

    private fun getDeviceName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                "名前なし (権限不足)"
            } else {
                bluetoothAdapter?.name ?: "名前なし"
            }
        } catch (_: SecurityException) {
            "名前なし (権限不足)"
        }
    }
}
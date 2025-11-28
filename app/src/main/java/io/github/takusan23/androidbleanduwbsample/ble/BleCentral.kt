package io.github.takusan23.androidbleanduwbsample.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** BLE セントラル側のコード */
class BleCentral(private val context: Context) {

    /** [readCharacteristic]等で使いたいので */
    private val _bluetoothGatt = MutableStateFlow<BluetoothGatt?>(null)

    /** コールバックの返り値をコルーチン側から受け取りたいので */
    private val _characteristicReadChannel = Channel<ByteArray>()

    /** BLE 通信をし、GATT サーバーへ接続しサービスを探す */
    @SuppressLint("MissingPermission")
    suspend fun connectGattServer() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // BluetoothDevice が見つかるまで一時停止
        val bluetoothDevice: BluetoothDevice? = suspendCoroutine { continuation ->
            val bluetoothLeScanner = bluetoothManager.adapter.bluetoothLeScanner
            val bleScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    super.onScanResult(callbackType, result)
                    // 見つけたら返して、スキャンも終了させる
                    continuation.resume(result?.device)
                    bluetoothLeScanner.stopScan(this)
                }

                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    continuation.resume(null)
                }
            }

            // GATT サーバーのサービス UUID を指定して検索を始める
            val scanFilter = ScanFilter.Builder().apply {
                setServiceUuid(ParcelUuid(BleUuid.GATT_SERVICE_UUID))
            }.build()
            bluetoothLeScanner.startScan(
                listOf(scanFilter),
                ScanSettings.Builder().build(),
                bleScanCallback
            )
        }

        // BLE デバイスを見つけたら、GATT サーバーへ接続
        bluetoothDevice?.connectGatt(context, false, object : BluetoothGattCallback() {

            // ペリフェラル側との接続
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                when (newState) {
                    // 接続できたらサービスを探す
                    BluetoothProfile.STATE_CONNECTED -> gatt?.discoverServices()
                    // なくなった
                    BluetoothProfile.STATE_DISCONNECTED -> _bluetoothGatt.value = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                // サービスが見つかったら GATT サーバーに対して操作ができるはず
                // サービスとキャラクタリスティックを探して、read する
                // キャラクタリスティック操作ができたら flow に入れる
                _bluetoothGatt.value = gatt
            }

            // onCharacteristicReadRequest で送られてきたデータを受け取る
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                super.onCharacteristicRead(gatt, characteristic, value, status)

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    println("BleCentral: Read成功 (${value.size} bytes)")
                    _characteristicReadChannel.trySend(value)
                } else {
                    println("BleCentral: Read失敗！ Status=$status")
                    // 失敗した場合は空の配列を送って処理を進める（止まらないように）
                    _characteristicReadChannel.trySend(ByteArray(0))
                }
            }
        })

        // GATT サーバーへ接続できるまで一時停止する
        _bluetoothGatt.first { it != null }
    }

    /** 終了時に呼ぶ */
    @SuppressLint("MissingPermission")
    fun destroy() {
        _bluetoothGatt.value?.close()
        _bluetoothGatt.value = null
    }

    /** キャラクタリスティックから読み出す */
    @SuppressLint("MissingPermission")
    suspend fun readCharacteristic(): ByteArray {
        // GATT サーバーとの接続を待つ
        val gatt = _bluetoothGatt.filterNotNull().first()

        // サービス一覧が null の可能性も考慮して ?. を使う
        val services = gatt.services ?: run {
            println("BleCentral: サービスリストが取得できませんでした (null)")
            return ByteArray(0)
        }

        // first ではなく firstOrNull を使い、見つからない場合は null を受け取る
        val findService = services.firstOrNull { it.uuid == BleUuid.GATT_SERVICE_UUID }

        // サービスが見つからなかった場合のデバッグログ
        if (findService == null) {
            println("BleCentral: 目的のサービスが見つかりません。")
            println("探しているUUID: ${BleUuid.GATT_SERVICE_UUID}")
            println("見つかったUUID一覧:")
            services.forEach { println(" - ${it.uuid}") }
            // ここで終了する（クラッシュさせない）
            return ByteArray(0)
        }

        // キャラクタリスティックも同様に安全に探す
        val findCharacteristic = findService.characteristics.firstOrNull { it.uuid == BleUuid.GATT_CHARACTERISTIC_UUID }

        if (findCharacteristic == null) {
            println("BleCentral: 目的のキャラクタリスティックが見つかりません。")
            println("探しているUUID: ${BleUuid.GATT_CHARACTERISTIC_UUID}")
            return ByteArray(0)
        }

        // 結果は onCharacteristicRead で
        gatt.readCharacteristic(findCharacteristic)
        return _characteristicReadChannel.receive()
    }

    /** キャラクタリスティックへ書き込む */
    @SuppressLint("MissingPermission")
    suspend fun writeCharacteristic(sendData: ByteArray) {
        // GATT サーバーとの接続を待つ
        val gatt = _bluetoothGatt.filterNotNull().first()
        // GATT サーバーへ狙ったサービス内にあるキャラクタリスティックへ write を試みる
        val findService = gatt.services?.first { it.uuid == BleUuid.GATT_SERVICE_UUID } ?: return
        val findCharacteristic = findService.characteristics?.first { it.uuid == BleUuid.GATT_CHARACTERISTIC_UUID } ?: return
        // 結果は onCharacteristicWriteRequest で
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(findCharacteristic, sendData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            // TODO 下位バージョン対応するなら。UWB 対応デバイスが、TIRAMISU より前に存在するかを考えるとめんどい
        }
    }

}
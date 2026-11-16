
package com.example.evkgnssreceiver

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * MQTT Publisher
 *
 * 功能：
 * 1. 使用手機輸入的 Host、Port、Username、Password
 *    連接 MQTT Broker
 *
 * 2. 將 EVK-X20P 的 GNSS JSON 發送至 Broker
 *
 * 3. 統計 MQTT 發布成功與失敗次數
 *
 * 注意：
 * 目前沒有實作斷線暫存與補傳功能。
 */
class MqttPublisher {

    // 發布 GNSS 資料的 Topic
    private val topic = "gnss/evk01/position"

    // 目前使用的 MQTT Client
    @Volatile
    private var client: Mqtt3AsyncClient? = null

    // MQTT 連線狀態
    @Volatile
    var connected = false
        private set

    // MQTT 發布成功次數
    val publishedCount = AtomicLong(0)

    // MQTT 發布失敗次數
    val failedCount = AtomicLong(0)

    // --------------------------------------------------
    // 1. 連接 MQTT Broker
    // --------------------------------------------------

    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        onResult: (Boolean, String) -> Unit
    ) {

        // 先中斷上一個 MQTT 連線
        disconnect()

        // 建立新的 MQTT Client
        val newClient = MqttClient.builder()
            .useMqttVersion3()

            // 每次連線產生獨立的 Client ID
            .identifier(
                "evk-android-${UUID.randomUUID()}"
            )

            // 使用手機畫面輸入的 Host
            .serverHost(host)

            // 使用手機畫面輸入的 Port
            .serverPort(port)

            .buildAsync()

        client = newClient

        connected = false

        // 使用 Username / Password 進行 MQTT 驗證
        newClient.connectWith()
            .simpleAuth()

            // 使用者在手機輸入的 Username
            .username(username)

            // 使用者在手機輸入的 Password
            .password(
                password.toByteArray(
                    StandardCharsets.UTF_8
                )
            )

            .applySimpleAuth()
            .send()
            .whenComplete { _, error ->

                // 避免舊連線的回呼改變新連線狀態
                if (client !== newClient) {
                    return@whenComplete
                }

                if (error == null) {

                    connected = true

                    Log.d(
                        "EVK_MQTT",
                        "MQTT connected: $host:$port"
                    )

                    onResult(
                        true,
                        "MQTT 已連線：$host:$port"
                    )

                } else {

                    connected = false

                    Log.e(
                        "EVK_MQTT",
                        "MQTT connect failed",
                        error
                    )

                    onResult(
                        false,
                        "MQTT 連線失敗：${error.message}"
                    )
                }
            }
    }

    // --------------------------------------------------
    // 2. 發布 GNSS JSON
    // --------------------------------------------------

    fun publishJson(json: String) {

        val current = client ?: return

        // 尚未連線就不發送
        if (!connected || !current.state.isConnected) {
            return
        }

        // 發布 JSON
        current.publishWith()

            // 指定 Topic
            .topic(topic)

            // QoS 1：Broker 至少收到一次
            // 可能發生重複訊息
            .qos(MqttQos.AT_LEAST_ONCE)

            // 將 JSON 字串轉成 UTF-8 Bytes
            .payload(
                json.toByteArray(
                    StandardCharsets.UTF_8
                )
            )

            .send()
            .whenComplete { _, error ->

                if (error == null) {

                    // QoS 1 發布已收到確認
                    publishedCount.incrementAndGet()

                } else {

                    // 發布失敗
                    failedCount.incrementAndGet()

                    // 記錄實際錯誤供 Logcat 檢查
                    Log.e(
                        "EVK_MQTT",
                        "MQTT publish failed",
                        error
                    )
                }
            }
    }

    // --------------------------------------------------
    // 3. 中斷 MQTT 連線
    // --------------------------------------------------

    fun disconnect() {

        connected = false

        val oldClient = client
        client = null

        // 非同步關閉 MQTT 連線
        oldClient?.disconnect()
    }
}

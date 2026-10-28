package com.pcpowercard.mqtt

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import com.pcpowercard.data.DeviceStatus
import com.pcpowercard.util.LogWriter

class MqttManager : MqttCallback {

    companion object {
        const val BROKER = "tcp://broker.emqx.io:1883"
    }

    private lateinit var topicPrefix: String
    private val topicPower: String get() = topicPrefix + "/power"
    private val topicReset: String get() = topicPrefix + "/reset"
    private val topicStatus: String get() = topicPrefix + "/status"

    private var client: MqttClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var statusCallback: ((DeviceStatus) -> Unit)? = null
    private var connectCallback: (() -> Unit)? = null
    private var disconnectCallback: (() -> Unit)? = null
    var isConnected: Boolean = false
        private set

    fun setTopicPrefix(prefix: String) {
        topicPrefix = prefix
    }

    fun connect() {
        if (client != null) {
            LogWriter.log("MQTT client already exists")
            return
        }
        LogWriter.log("Connecting MQTT: $BROKER")
        try {
            val clientId = "pcpowercard_app" + System.currentTimeMillis() % 10000
            client = MqttClient(BROKER, clientId, MemoryPersistence())
            client?.setCallback(this)
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                keepAliveInterval = 60
                connectionTimeout = 15
            }
            options.setAutomaticReconnect(true)
            client?.connect(options)
        } catch (e: Exception) {
            LogWriter.log("MQTT 连接失败：${e.message}")
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
            client?.close()
            client = null
            isConnected = false
        } catch (e: Exception) {
            LogWriter.log("MQTT disconnect error: ${e.message}")
        }
    }

    fun subscribeStatus(onStatus: (DeviceStatus) -> Unit,
                       onConnected: () -> Unit,
                       onDisconnected: () -> Unit) {
        statusCallback = onStatus
        connectCallback = onConnected
        disconnectCallback = onDisconnected
    }

    fun publishPower(cmd: String) {
        val c = client ?: run { LogWriter.log("PUB FAIL: no client"); return }
        if (!c.isConnected) { LogWriter.log("PUB FAIL: not connected"); return }
        val payload = "{\"computer_todo\":\"$cmd\"}"
        try {
            val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            msg.qos = 0
            c.publish(topicPower, msg)
            LogWriter.log("PUB OK $topicPower -> $payload")
        } catch (e: Exception) {
            LogWriter.log("PUB FAIL: ${e.message}")
        }
    }

    fun publishReset() {
        val c = client ?: run { LogWriter.log("PUB RESET FAIL: no client"); return }
        if (!c.isConnected) { LogWriter.log("PUB RESET FAIL: not connected"); return }
        val payload = "{\"rst\":\"1\"}"
        try {
            val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            msg.qos = 0
            c.publish(topicReset, msg)
            LogWriter.log("PUB OK $topicReset -> $payload")
        } catch (e: Exception) {
            LogWriter.log("PUB RESET FAIL: ${e.message}")
        }
    }

    // MqttCallback
    override fun connectionLost(cause: Throwable?) {
        isConnected = false
        handler.post {
            disconnectCallback?.invoke()
            LogWriter.log("MQTT disconnected" + (cause?.message?.let { ": " + it } ?: ""))
        }
    }

    override fun messageArrived(topic: String, message: MqttMessage) {
        val text = String(message.payload, Charsets.UTF_8)
        LogWriter.log("RX $topic : $text")
        if (topic == topicStatus) {
            try {
                val obj = org.json.JSONObject(text)
                val status = DeviceStatus(
                    power = obj.optString("power_state", "OFF"),
                    wifi = if (obj.optBoolean("online", false)) "UP" else "DOWN",
                    mqtt = "UP",
                    temp = if (obj.has("temp_c") && !obj.isNull("temp_c")) {
                        obj.optDouble("temp_c").toFloat()
                    } else {
                        null
                    },
                    online = obj.optBoolean("online", false),
                    time = obj.optString("time", "")
                )
                handler.post { statusCallback?.invoke(status) }
            } catch (e: Exception) {
                LogWriter.log("RX parse fail: $text")
            }
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken) {
        LogWriter.log("MQTT delivery complete")
    }

    // Detect connection via monitoring
    fun checkConnect() {
        val c = client ?: return
        if (c.isConnected) {
            if (!isConnected) {
                isConnected = true
                handler.post {
                    statusCallback?.invoke(DeviceStatus(mqtt = "UP"))
                    connectCallback?.invoke()
                    LogWriter.log("MQTT connected")
                    try {
                        c.subscribe(topicStatus, 0)
                        LogWriter.log("SUB OK $topicStatus")
                    } catch (e: Exception) {
                        LogWriter.log("SUB FAIL: ${e.message}")
                    }
                }
            }
        }
    }
}

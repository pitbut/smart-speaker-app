package com.pit.smartspeaker

import android.content.Context
import android.util.Log
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException

/**
 * Thin wrapper around the MQTT client for publishing simple smart-home commands.
 * Configure broker/topic/credentials in Settings. If no broker is configured,
 * publish() is a no-op and returns false via the callback.
 */
class MqttHelper(private val context: Context) {

    private var client: MqttAndroidClient? = null
    private var connected = false

    fun publish(payload: String, onResult: (Boolean, String?) -> Unit) {
        val broker = Prefs.getMqttBroker(context)
        if (broker.isBlank()) {
            onResult(false, "MQTT брокер не настроен")
            return
        }

        val topic = Prefs.getMqttTopic(context)
        val clientId = "smart-speaker-" + System.currentTimeMillis()

        try {
            val mqttClient = client ?: MqttAndroidClient(context, broker, clientId).also { client = it }

            if (connected) {
                mqttClient.publish(topic, payload.toByteArray(), 0, false)
                onResult(true, null)
                return
            }

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5

                val user = Prefs.getMqttUser(context)
                val pass = Prefs.getMqttPass(context)
                if (user.isNotBlank()) {
                    userName = user
                    password = pass.toCharArray()
                }
            }

            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    connected = true
                    try {
                        mqttClient.publish(topic, payload.toByteArray(), 0, false)
                        onResult(true, null)
                    } catch (e: MqttException) {
                        onResult(false, "Ошибка публикации: ${e.message}")
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connected = false
                    onResult(false, "Не удалось подключиться к брокеру")
                }
            })
        } catch (e: Exception) {
            Log.e("MqttHelper", "MQTT error", e)
            onResult(false, "Ошибка MQTT: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            client?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        connected = false
    }
}

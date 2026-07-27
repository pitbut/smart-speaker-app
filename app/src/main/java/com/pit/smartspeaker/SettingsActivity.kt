package com.pit.smartspeaker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val wakePhraseInput = findViewById<EditText>(R.id.wakePhraseInput)
        val cityInput = findViewById<EditText>(R.id.cityInput)
        val brokerInput = findViewById<EditText>(R.id.brokerInput)
        val topicInput = findViewById<EditText>(R.id.topicInput)
        val userInput = findViewById<EditText>(R.id.userInput)
        val passInput = findViewById<EditText>(R.id.passInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        wakePhraseInput.setText(Prefs.getWakePhrase(this))
        cityInput.setText(Prefs.getWeatherCity(this))
        brokerInput.setText(Prefs.getMqttBroker(this))
        topicInput.setText(Prefs.getMqttTopic(this))
        userInput.setText(Prefs.getMqttUser(this))
        passInput.setText(Prefs.getMqttPass(this))

        saveButton.setOnClickListener {
            val phrase = wakePhraseInput.text.toString().trim().lowercase()
            Prefs.setWakePhrase(this, if (phrase.isBlank()) "катя слушай" else phrase)
            Prefs.setWeatherCity(this, cityInput.text.toString().trim().ifBlank { "Ташкент" })
            Prefs.setMqttBroker(this, brokerInput.text.toString().trim())
            Prefs.setMqttTopic(this, topicInput.text.toString().trim().ifBlank { "home/smart-speaker" })
            Prefs.setMqttUser(this, userInput.text.toString().trim())
            Prefs.setMqttPass(this, passInput.text.toString())

            Toast.makeText(this, "Сохранено. Перезапусти колонку, чтобы применить.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

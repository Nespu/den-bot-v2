package com.denbot

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val CAPTURE_REQ = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val accessBtn = findViewById<Button>(R.id.accessBtn)
        val colorToggle = findViewById<Switch>(R.id.colorToggle)
        val bulletToggle = findViewById<Switch>(R.id.bulletToggle)
        val eloSlider = findViewById<SeekBar>(R.id.eloSlider)
        val eloLabel = findViewById<TextView>(R.id.eloLabel)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)

        val prefs = getSharedPreferences("denbot_prefs", Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString("api_key", ""))
        colorToggle.isChecked = prefs.getBoolean("playAsBlack", false)
        bulletToggle.isChecked = prefs.getBoolean("bulletMode", false)
        eloSlider.progress = prefs.getInt("eloProgress", 15)

        val eloValues = (600..3000 step 100).toList()
        eloLabel.text = "ELO: ${eloValues.getOrElse(eloSlider.progress) { 1500 }}"

        colorToggle.text = if (colorToggle.isChecked) "Juego con ♚ Negras" else "Juego con ♔ Blancas"
        bulletToggle.text = if (bulletToggle.isChecked) "⚡ Modo Bala: ON" else "⚡ Modo Bala: OFF"

        eloSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                val elo = eloValues.getOrElse(p) { 1500 }
                eloLabel.text = "ELO: $elo"
                prefs.edit().putInt("eloProgress", p).putInt("elo", elo).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        colorToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("playAsBlack", checked).apply()
            colorToggle.text = if (checked) "Juego con ♚ Negras" else "Juego con ♔ Blancas"
        }

        bulletToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("bulletMode", checked).apply()
            bulletToggle.text = if (checked) "⚡ Modo Bala: ON" else "⚡ Modo Bala: OFF"
        }

        accessBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startBtn.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            prefs.edit().putString("api_key", apiKey).apply()

            if (!Settings.canDrawOverlays(this)) {
                statusText.text = "⚠ Otorga permiso de overlay y vuelve a tocar Iniciar"
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            if (!AutoClickService.isEnabled) {
                statusText.text = "⚠ Activa el servicio de accesibilidad primero"
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            statusText.text = "⏳ Solicitando grabación de pantalla..."
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), CAPTURE_REQ)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQ && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startForegroundService(serviceIntent)
            finish()
        } else if (requestCode == CAPTURE_REQ) {
            findViewById<TextView>(R.id.statusText)?.text = "⚠ Permiso de grabación denegado"
        }
    }

    override fun onResume() {
        super.onResume()
        val statusText = findViewById<TextView>(R.id.statusText)
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = AutoClickService.isEnabled
        statusText.text = when {
            !overlayOk -> "⚠ Falta permiso de overlay"
            !accessOk -> "⚠ Activa el servicio de accesibilidad"
            else -> "✅ Todo listo — toca Iniciar"
        }
    }
}

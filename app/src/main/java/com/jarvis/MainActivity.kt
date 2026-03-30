package com.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.audio.VoiceSessionService
import com.jarvis.databinding.ActivityMainBinding
import com.jarvis.settings.MemorySettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // UI stays minimal for prototype mode.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestRequiredPermissions()

        binding.startButton.setOnClickListener {
            binding.statusText.text = getString(R.string.active_status)
            startVoiceService(VoiceSessionService.ACTION_START_SESSION)
        }

        binding.stopButton.setOnClickListener {
            binding.statusText.text = getString(R.string.idle_status)
            startVoiceService(VoiceSessionService.ACTION_STOP_SESSION)
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, MemorySettingsActivity::class.java))
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startVoiceService(action: String) {
        val intent = Intent(this, VoiceSessionService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }
}

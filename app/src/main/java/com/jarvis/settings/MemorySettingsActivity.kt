package com.jarvis.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.R
import com.jarvis.databinding.ActivityMemorySettingsBinding
import com.jarvis.memory.LocalMemoryStore
import kotlinx.coroutines.launch

class MemorySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemorySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemorySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val memoryStore = LocalMemoryStore.get(this)

        binding.clearButton.setOnClickListener {
            lifecycleScope.launch {
                memoryStore.clearAll()
                binding.resultText.text = getString(R.string.memory_cleared)
            }
        }

        binding.exportButton.setOnClickListener {
            lifecycleScope.launch {
                binding.resultText.text = memoryStore.exportAsJson()
            }
        }
    }
}

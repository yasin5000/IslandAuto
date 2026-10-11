package com.example.islandauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 18f
            text = serviceStatusText()
        }
        val btnAcc = Button(this).apply {
            text = "1) Accessibility Settings kholo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnShow = Button(this).apply {
            text = "2) Floating toolbar dekhao"
            setOnClickListener {
                if (AutoService.instance != null) AutoService.instance?.showToolbar()
                else Toast.makeText(this@MainActivity, "Age Accessibility Service ON korun", Toast.LENGTH_SHORT).show()
            }
        }

        content.addView(status)
        content.addView(btnAcc)
        content.addView(btnShow)

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun serviceStatusText(): String =
        if (AutoService.instance != null) "Service: ON ✅"
        else "Service: OFF ❌\nAccessibility e 'Island Auto' ON korun"

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = serviceStatusText()
    }
}

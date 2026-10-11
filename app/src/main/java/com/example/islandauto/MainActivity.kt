package com.example.islandauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        val btnAcc = Button(this).apply {
            text = "1) Accessibility Settings kholo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnShow = Button(this).apply {
            text = "2) Floating toolbar dekhao"
            setOnClickListener { AutoService.instance?.showToolbar() }
        }
        root.addView(status)
        root.addView(btnAcc)
        root.addView(btnShow)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (AutoService.instance != null) "Service: ON \u2705"
        else "Service: OFF \u274C\nAccessibility e 'Island Auto' ON korun"
    }
}

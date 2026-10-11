package com.example.islandauto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var fileStatus: TextView
    private lateinit var emailBox: EditText
    private val remainingEmails = mutableListOf<String>()
    private var sourceFileName = ""

    companion object {
        private const val PICK_TXT_FILE = 2001
        private const val SAVE_REMAINING_TXT = 2002
    }

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

        val divider = TextView(this).apply {
            text = "\nTXT Email Import"
            textSize = 20f
        }
        fileStatus = TextView(this).apply {
            text = "Kono TXT file select kora hoyni"
            textSize = 14f
        }
        val btnUpload = Button(this).apply {
            text = "TXT File Upload"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                }
                startActivityForResult(i, PICK_TXT_FILE)
            }
        }
        emailBox = EditText(this).apply {
            hint = "Email ekhane dekhabe"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            singleLine = true
        }
        val btnNext = Button(this).apply {
            text = "Next Email (list theke remove)"
            setOnClickListener {
                if (remainingEmails.isEmpty()) {
                    Toast.makeText(this@MainActivity, "TXT file upload korun ba email list sesh", Toast.LENGTH_SHORT).show()
                } else {
                    emailBox.setText(remainingEmails.removeAt(0))
                    updateFileStatus()
                }
            }
        }
        val btnSave = Button(this).apply {
            text = "Save Remaining Emails as TXT"
            setOnClickListener {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "remaining_emails.txt")
                }
                startActivityForResult(i, SAVE_REMAINING_TXT)
            }
        }

        content.addView(status)
        content.addView(btnAcc)
        content.addView(btnShow)
        content.addView(divider)
        content.addView(fileStatus)
        content.addView(btnUpload)
        content.addView(emailBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(btnNext)
        content.addView(btnSave)

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun serviceStatusText(): String = if (AutoService.instance != null) "Service: ON ✅" else "Service: OFF ❌\nAccessibility e 'Island Auto' ON korun"

    private fun updateFileStatus() {
        fileStatus.text = "File: $sourceFileName\nBaki email: ${remainingEmails.size}"
    }

    @Deprecated("Deprecated in Android API, kept for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            PICK_TXT_FILE -> loadEmails(uri)
            SAVE_REMAINING_TXT -> saveRemainingEmails(uri)
        }
    }

    private fun loadEmails(uri: Uri) {
        try {
            val list = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readLines()
            } ?: emptyList()
            remainingEmails.clear()
            remainingEmails.addAll(list.map { it.trim() }.filter { it.isNotEmpty() && it.contains("@") })
            sourceFileName = uri.lastPathSegment?.substringAfterLast('/') ?: "selected.txt"
            emailBox.setText("")
            updateFileStatus()
            Toast.makeText(this, "${remainingEmails.size} ta email load hoyeche", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "TXT file pora jayni: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveRemainingEmails(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(remainingEmails.joinToString(separator = "\n"))
                    if (remainingEmails.isNotEmpty()) writer.write("\n")
                }
            }
            Toast.makeText(this, "Remaining email gulo TXT hisebe save hoyeche", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save kora jayni: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = serviceStatusText()
    }
}

package com.sway.messageexporter

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private val contactCache = mutableMapOf<String, String>()
    private var pendingThreadToExport: ThreadItem? = null
    private var progressDialog: AlertDialog? = null

    private val progressReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.sway.messageexporter.EXPORT_PROGRESS" -> {
                    val current = intent.getIntExtra("current", 0)
                    val total = intent.getIntExtra("total", 0)
                    updateProgress(current, total)
                }
                "com.sway.messageexporter.EXPORT_COMPLETE",
                "com.sway.messageexporter.EXPORT_FAILED" -> {
                    progressDialog?.dismiss()
                }
            }
        }
    }

    data class ThreadItem(val id: Long, val names: String, val snippet: String, val date: Long)

    private val smsDefaultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isDefaultSmsApp()) {
            pendingThreadToExport?.let { startExportService(it) }
        } else {
            Toast.makeText(this, "Limited export started (Not default app)", Toast.LENGTH_LONG).show()
            pendingThreadToExport?.let { startExportService(it) }
        }
        pendingThreadToExport = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 123
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        val toolbar = Toolbar(this).apply {
            title = "Messages"
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
            setBackgroundColor(typedValue.data)
        }
        setSupportActionBar(toolbar)
        root.addView(toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, statusBars.top, 0, 0)
            insets
        }
        
        listView = ListView(this).apply {
            divider = null
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
            setBackgroundColor(typedValue.data)
        }
        root.addView(listView)
        
        setContentView(root)

        if (checkPermissions()) {
            loadThreads()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.sway.messageexporter.EXPORT_PROGRESS")
            addAction("com.sway.messageexporter.EXPORT_COMPLETE")
            addAction("com.sway.messageexporter.EXPORT_FAILED")
        }
        registerReceiver(progressReceiver, filter, RECEIVER_NOT_EXPORTED)

        if (isDefaultSmsApp()) {
            val dir = getExternalFilesDir(null)
            val mostRecentZip = dir?.listFiles { _, name -> name.endsWith(".zip") }
                ?.maxByOrNull { it.lastModified() }
            
            if (mostRecentZip != null && (System.currentTimeMillis() - mostRecentZip.lastModified() < 300000)) {
                showSwitchBackPrompt()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(progressReceiver)
    }

    private fun updateProgress(current: Int, total: Int) {
        progressDialog?.let { dialog ->
            dialog.findViewById<android.widget.ProgressBar>(android.R.id.progress)?.let {
                it.isIndeterminate = false
                it.max = total
                it.progress = current
            }
            dialog.findViewById<TextView>(android.R.id.message)?.let {
                it.text = String.format(Locale.getDefault(), "Fetching message %d of %d...", current, total)
            }
        }
    }

    private fun isDefaultSmsApp(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("Saved Exports").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                showSavedExports()
                true
            }
        }
        return true
    }

    private fun showSavedExports() {
        val dir = getExternalFilesDir(null) ?: return
        val files = dir.listFiles { _, name -> name.endsWith(".zip") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved exports found", Toast.LENGTH_SHORT).show()
            return
        }

        val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        val fileNames = files.map { "${it.name}\n${dateFormat.format(Date(it.lastModified()))}" }
        
        AlertDialog.Builder(this)
            .setTitle("Saved Exports")
            .setItems(fileNames.toTypedArray()) { _, which ->
                shareFile(files[which])
            }
            .setNeutralButton("Delete All") { _, _ ->
                dir.listFiles()?.forEach { it.deleteRecursively() }
                Toast.makeText(this, "All exports deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadThreads()
            } else {
                Toast.makeText(this, "Permissions required to export messages", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadThreads() {
        lifecycleScope.launch {
            val threads = withContext(Dispatchers.IO) {
                try { queryThreads() } catch (e: Exception) { Log.e("MainActivity", "Query failed", e); emptyList() }
            }
            
            val adapter = object : ArrayAdapter<ThreadItem>(this@MainActivity, R.layout.item_thread, threads) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_thread, parent, false)
                    val item = getItem(position) ?: return view
                    view.findViewById<TextView>(R.id.name).text = item.names
                    view.findViewById<TextView>(R.id.snippet).text = item.snippet
                    view.findViewById<TextView>(R.id.date).text = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(item.date))
                    val avatarTv = view.findViewById<TextView>(R.id.avatarText)
                    avatarTv.text = if (item.names.isNotEmpty()) item.names.first().uppercase() else "?"
                    val colors = intArrayOf(0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(), 0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF009688.toInt(), 0xFF4CAF50.toInt(), 0xFFFFC107.toInt(), 0xFFFF5722.toInt())
                    view.findViewById<ImageView>(R.id.avatar).setBackgroundColor(colors[item.names.hashCode().absoluteValue % colors.size])
                    return view
                }
            }
            
            listView.adapter = adapter
            listView.setOnItemLongClickListener { _, _, position, _ ->
                showExportDialog(threads[position])
                true
            }
        }
    }

    private fun showExportDialog(item: ThreadItem) {
        AlertDialog.Builder(this)
            .setTitle("Export Thread")
            .setMessage("Ready to export '${item.names}'? For full access, this app should be the default SMS app during the fetch.")
            .setPositiveButton("Export") { _, _ ->
                checkDefaultAndExport(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkDefaultAndExport(item: ThreadItem) {
        if (isDefaultSmsApp()) {
            startExportService(item)
        } else {
            pendingThreadToExport = item
            val roleManager = getSystemService(RoleManager::class.java)
            val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
            if (intent != null) {
                smsDefaultLauncher.launch(intent)
            } else {
                startExportService(item)
            }
        }
    }

    private fun startExportService(item: ThreadItem) {
        val intent = Intent(this, ExportService::class.java).apply {
            putExtra("threadId", item.id)
            putExtra("contactName", item.names)
        }
        
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Exporting")
            .setMessage("Preparing messages for ${item.names}...")
            .setView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = android.R.id.progress
                setPadding(40, 40, 40, 40)
                isIndeterminate = true
            })
            .setCancelable(false)
            .show()

        startForegroundService(intent)
        Toast.makeText(this, "Exporting in background... please wait for the notification.", Toast.LENGTH_LONG).show()
    }

    private fun queryThreads(): List<ThreadItem> {
        val threads = mutableListOf<ThreadItem>()
        val uri = "content://mms-sms/conversations?simple=true".toUri()
        val projection = arrayOf("_id", "recipient_ids", "snippet", "date")
        
        val canonicalAddresses = getCanonicalAddresses()

        contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndex("_id")
            val snippetColumn = cursor.getColumnIndex("snippet")
            val recipientIdsColumn = cursor.getColumnIndex("recipient_ids")
            val dateColumn = cursor.getColumnIndex("date")

            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(idColumn)
                val recipientIds = cursor.getString(recipientIdsColumn)?.split(" ") ?: listOf()
                val date = cursor.getLong(dateColumn)
                
                val names = recipientIds.joinToString(", ") { id ->
                    canonicalAddresses[id.toLongOrNull()]?.let { getContactName(it) } ?: id
                }

                threads.add(ThreadItem(threadId, names, cursor.getString(snippetColumn) ?: "", date))
            }
        }
        return threads
    }

    private fun getContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || (phoneNumber == "Unknown")) return phoneNumber
        contactCache[phoneNumber]?.let { return it }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    contactCache[phoneNumber] = name
                    return name
                }
            }
        } catch (_: Exception) {}
        return phoneNumber
    }

    private fun getCanonicalAddresses(): Map<Long, String> {
        val addresses = mutableMapOf<Long, String>()
        val uri = "content://mms-sms/canonical-addresses".toUri()
        try {
            contentResolver.query(uri, arrayOf("_id", "address"), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex("_id")
                val addressCol = cursor.getColumnIndex("address")
                while (cursor.moveToNext()) {
                    addresses[cursor.getLong(idCol)] = cursor.getString(addressCol) ?: "Unknown"
                }
            }
        } catch (e: Exception) { Log.e("MainActivity", "Canonical error", e) }
        return addresses
    }

    private fun showSwitchBackPrompt() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Export Complete")
            .setMessage("Please switch your default SMS app back to your preferred one.")
            .setPositiveButton("Switch Back") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun shareFile(file: File) {
        if (isFinishing || isDestroyed || !file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Export ZIP"))
    }
}

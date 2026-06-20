package com.sway.messageexporter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private val PERMISSIONS_REQUEST_CODE = 123
    private val contactCache = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        listView = ListView(this)
        setContentView(listView)

        if (checkPermissions()) {
            loadThreads()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS
            ),
            PERMISSIONS_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                loadThreads()
            } else {
                Toast.makeText(this, "Permissions required to export messages", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadThreads() {
        lifecycleScope.launch {
            val threads = withContext(Dispatchers.IO) {
                queryThreads()
            }
            listView.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_2, android.R.id.text1, threads)
            listView.setOnItemClickListener { _, _, position, _ ->
                val threadInfo = threads[position]
                if (threadInfo.startsWith("Thread ")) {
                    val threadIdString = threadInfo.split(":")[0].removePrefix("Thread ").trim()
                    val threadId = threadIdString.toLongOrNull() ?: -1L
                    if (threadId != -1L) exportThread(threadId)
                }
            }
        }
    }

    private suspend fun queryThreads(): List<String> {
        val threads = mutableListOf<String>()
        val uri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
        val projection = arrayOf("_id", "recipient_ids", "snippet")
        
        val canonicalAddresses = getCanonicalAddresses()

        contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndex("_id")
            val snippetColumn = cursor.getColumnIndex("snippet")
            val recipientIdsColumn = cursor.getColumnIndex("recipient_ids")

            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(idColumn)
                val snippet = cursor.getString(snippetColumn) ?: ""
                val recipientIds = cursor.getString(recipientIdsColumn)?.split(" ") ?: listOf()
                
                val names = recipientIds.map { id ->
                    val address = canonicalAddresses[id.toLongOrNull()] ?: id
                    getContactName(address)
                }.joinToString(", ")

                threads.add("Thread $threadId: $names\nSnippet: $snippet")
            }
        }

        if (threads.isEmpty()) {
            threads.add("No threads found")
        }
        return threads
    }

    private fun getContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return phoneNumber
        contactCache[phoneNumber]?.let { return it }
        
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber)
        )
        val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
        
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    contactCache[phoneNumber] = name
                    return name
                }
            }
        } catch (ignored: Exception) {
        }
        return phoneNumber
    }

    private fun getCanonicalAddresses(): Map<Long, String> {
        val addresses = mutableMapOf<Long, String>()
        val uri = android.net.Uri.parse("content://mms-sms/canonical-addresses")
        contentResolver.query(uri, arrayOf("_id", "address"), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex("_id")
            val addrCol = cursor.getColumnIndex("address")
            while (cursor.moveToNext()) {
                addresses[cursor.getLong(idCol)] = cursor.getString(addrCol) ?: "Unknown"
            }
        }
        return addresses
    }

    private fun exportThread(threadId: Long) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Exporting thread $threadId...", Toast.LENGTH_SHORT).show()
            val file = java.io.File(getExternalFilesDir(null), "thread_$threadId.xml")
            
            val success = withContext(Dispatchers.IO) {
                val exporter = MessageExporter(contentResolver)
                exporter.exportThread(threadId, file)
            }
            
            if (success) {
                Toast.makeText(this@MainActivity, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                shareFile(file)
            } else {
                Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareFile(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/xml"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share XML Export"))
    }
}

package com.sway.messageexporter

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageExporter(private val contentResolver: ContentResolver) {

    private val contactCache = mutableMapOf<String, String>()

    fun exportThread(threadId: Long, outputFile: File, onProgress: ((Int, Int) -> Unit)? = null): Boolean {
        val messages = mutableListOf<Message>()
        val attachmentsDir = File(outputFile.parentFile, "attachments_${outputFile.nameWithoutExtension}")
        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

        // 1. Get addresses for this thread
        val addresses = getThreadAddresses(threadId)
        Log.d("MessageExporter", "Thread $threadId has addresses: $addresses")

        // 2. Try unified query
        val unifiedUri = "content://mms-sms/conversations/$threadId".toUri()
        try {
            contentResolver.query(unifiedUri, null, null, null, "date ASC")?.use { c ->
                val total = c.count
                Log.d("MessageExporter", "Unified query found $total entries")
                
                val idCol = c.getColumnIndex("_id")
                val transportCol = c.getColumnIndex("transport_type")
                val bodyCol = c.getColumnIndex("body")
                val addressCol = c.getColumnIndex("address")
                val dateCol = c.getColumnIndex("date")
                val typeCol = c.getColumnIndex("type")
                val msgIdIdx = c.getColumnIndex("message_id")
                val replyIdx = c.getColumnIndex("replied_to_message_id")

                var current = 0
                while (c.moveToNext()) {
                    current++
                    onProgress?.invoke(current, total)

                    val id = c.getLong(idCol)
                    val transport = if (transportCol != -1) c.getString(transportCol) else "sms"
                    
                    if (bodyCol != -1 && addressCol != -1 && dateCol != -1) {
                        val body = c.getString(bodyCol) ?: ""
                        val address = c.getString(addressCol) ?: "Unknown"
                        val date = c.getLong(dateCol)
                        val type = if (typeCol != -1) c.getInt(typeCol) else 1
                        
                        val msg = Message(
                            id = id,
                            type = if (type == 1) "INCOMING" else "OUTGOING",
                            address = address,
                            senderName = getContactName(address),
                            body = body,
                            date = if (transport == "mms") date * 1000 else date,
                            protocol = transport.uppercase(),
                            reactions = getReactions(id),
                            rcsMessageId = if (msgIdIdx != -1) c.getString(msgIdIdx) else null,
                            replyToId = if (replyIdx != -1) c.getString(replyIdx) else null
                        )
                        
                        if (transport == "mms") {
                            extractMmsParts(id, msg, attachmentsDir)
                        }
                        messages.add(msg)
                    } else {
                        if (transport == "sms") fetchSmsMessage(id, messages) else fetchMmsMessage(id, messages, attachmentsDir)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MessageExporter", "Unified query failed", e)
        }

        // 3. Fallback: Query by thread_id directly in tables
        if (messages.size < 50) {
            Log.d("MessageExporter", "Falling back to direct thread_id queries")
            fetchSmsFromTable(threadId, messages)
            fetchMmsFromTable(threadId, messages, attachmentsDir)
        }

        // 4. Ultimate Fallback: Query by address
        if (messages.size < 50 && addresses.isNotEmpty()) {
            Log.d("MessageExporter", "Falling back to address-based queries")
            addresses.forEach { address ->
                fetchSmsByAddress(address, messages)
            }
        }

        messages.sortBy { it.date }
        val distinctMessages = messages.distinctBy { "${it.protocol}_${it.id}" }
        Log.d("MessageExporter", "Final deduplicated count: ${distinctMessages.size}")

        return try {
            outputFile.bufferedWriter().use { out ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                out.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                out.write("<thread id=\"$threadId\">\n")
                
                val participants = distinctMessages.map { it.senderName to it.address }.distinct()
                out.write("  <participants>\n")
                participants.forEach { (name, address) ->
                    out.write("    <participant name=\"${escapeXml(name)}\" address=\"${escapeXml(address)}\" />\n")
                }
                out.write("  </participants>\n")
                
                distinctMessages.forEach { msg ->
                    val typeAttr = msg.type.lowercase()
                    val protocolAttr = msg.protocol.replace("/", "_").lowercase()
                    val dateStr = dateFormat.format(Date(msg.date))
                    
                    var msgTags = "id=\"${msg.id}\" type=\"$typeAttr\" protocol=\"$protocolAttr\" timestamp=\"${msg.date}\" date=\"$dateStr\""
                    msg.rcsMessageId?.let { msgTags += " rcs_id=\"${escapeXml(it)}\"" }
                    msg.replyToId?.let { msgTags += " reply_to=\"${escapeXml(it)}\"" }

                    out.write("  <message $msgTags>\n")
                    val displayName = if (msg.type == "OUTGOING") "Me" else msg.senderName
                    out.write("    <sender name=\"${escapeXml(displayName)}\" address=\"${escapeXml(msg.address)}\" />\n")
                    out.write("    <body>${escapeXml(msg.body)}</body>\n")
                    
                    if (msg.attachments.isNotEmpty()) {
                        out.write("    <attachments>\n")
                        msg.attachments.forEach { attachment ->
                            out.write("      <attachment type=\"${escapeXml(attachment.type)}\" file=\"${escapeXml(attachment.localPath)}\" />\n")
                        }
                        out.write("    </attachments>\n")
                    }

                    if (msg.reactions.isNotEmpty()) {
                        out.write("    <reactions>\n")
                        msg.reactions.forEach { reaction ->
                            out.write("      <reaction>${escapeXml(reaction)}</reaction>\n")
                        }
                        out.write("    </reactions>\n")
                    }
                    out.write("  </message>\n")
                }
                out.write("</thread>\n")
            }
            true
        } catch (e: Exception) {
            Log.e("MessageExporter", "Failed to write XML", e)
            false
        }
    }

    private fun getThreadAddresses(threadId: Long): List<String> {
        val result = mutableListOf<String>()
        val uri = "content://mms-sms/conversations?simple=true".toUri()
        contentResolver.query(uri, arrayOf("recipient_ids"), "_id = ?", arrayOf(threadId.toString()), null)?.use { c ->
            if (c.moveToFirst()) {
                val ids = c.getString(0)?.split(" ") ?: emptyList()
                val addressUri = "content://mms-sms/canonical-addresses".toUri()
                ids.forEach { id ->
                    if (id.isBlank()) return@forEach
                    try {
                        contentResolver.query(addressUri, arrayOf("address"), "_id = ?", arrayOf(id), null)?.use { ac ->
                            if (ac.moveToFirst()) {
                                ac.getString(0)?.let { result.add(it) }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MessageExporter", "Failed to query canonical address for id $id", e)
                    }
                }
            }
        }
        return result
    }

    private fun fetchSmsByAddress(address: String, messages: MutableList<Message>) {
        val uri = Telephony.Sms.CONTENT_URI
        try {
            contentResolver.query(uri, arrayOf(Telephony.Sms._ID), "address LIKE ?", arrayOf("%$address%"), "date ASC")?.use { c ->
                val idCol = c.getColumnIndex(Telephony.Sms._ID)
                while (c.moveToNext()) fetchSmsMessage(c.getLong(idCol), messages)
            }
        } catch (e: Exception) {
            Log.e("MessageExporter", "Address query failed", e)
        }
    }

    private fun fetchSmsMessage(id: Long, messages: MutableList<Message>) {
        val uri = "content://sms/$id".toUri()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val address = c.getString(0) ?: "Unknown"
                val body = c.getString(1) ?: ""
                val date = c.getLong(2)
                val type = c.getInt(3)

                messages.add(Message(
                    id = id,
                    type = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "INCOMING" else "OUTGOING",
                    address = address,
                    senderName = getContactName(address),
                    body = body,
                    date = date,
                    protocol = "SMS",
                    reactions = getReactions(id)
                ))
            }
        }
    }

    private fun fetchMmsMessage(id: Long, messages: MutableList<Message>, attachmentsDir: File) {
        val uri = "content://mms/$id".toUri()
        val projection = arrayOf(
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX
        )
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val date = c.getLong(0) * 1000
                val box = c.getInt(1)
                val address = getMmsAddress(id)

                val msg = Message(
                    id = id,
                    type = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) "INCOMING" else "OUTGOING",
                    address = address,
                    senderName = getContactName(address),
                    body = "", 
                    date = date,
                    protocol = "MMS/RCS",
                    reactions = getReactions(id)
                )
                extractMmsParts(id, msg, attachmentsDir)
                messages.add(msg)
            }
        }
    }

    private fun extractMmsParts(mmsId: Long, message: Message, attachmentsDir: File) {
        val selection = "mid=$mmsId"
        val uri = "content://mms/part".toUri()
        val bodyBuilder = StringBuilder()
        
        contentResolver.query(uri, null, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex("_id")
            val textCol = cursor.getColumnIndex("text")
            val ctCol = cursor.getColumnIndex("ct")
            
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(idCol)
                val ct = cursor.getString(ctCol) ?: ""
                
                if (ct == "text/plain" || ct == "application/smil") {
                    bodyBuilder.append(if (textCol != -1) cursor.getString(textCol) ?: "" else "")
                } else {
                    // Export all non-text attachments (images, video, audio, vcards, PDFs, etc.)
                    val extension = getExtensionFromMimeType(ct)
                    val fileName = "mms_${mmsId}_part_${partId}.$extension"
                    val destFile = File(attachmentsDir, fileName)
                    
                    if (savePartToFile(partId, destFile)) {
                        message.attachments.add(Attachment(type = ct, localPath = "${attachmentsDir.name}/$fileName"))
                    }
                }
            }
        }
        message.body = bodyBuilder.toString().trim()
    }

    private fun getExtensionFromMimeType(mimeType: String): String {
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        if (extension != null) return extension

        return when {
            mimeType.startsWith("image/") -> mimeType.substringAfter("image/").substringBefore(";").replace("jpeg", "jpg")
            mimeType.startsWith("video/") -> mimeType.substringAfter("video/").substringBefore(";")
            mimeType.startsWith("audio/") -> mimeType.substringAfter("audio/").substringBefore(";")
            mimeType.contains("vcard") -> "vcf"
            mimeType.contains("pdf") -> "pdf"
            mimeType.contains("xml") -> "xml"
            else -> "bin"
        }
    }

    private fun savePartToFile(partId: Long, destFile: File): Boolean {
        val partUri = "content://mms/part/$partId".toUri()
        return try {
            contentResolver.openInputStream(partUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("MessageExporter", "Failed to save MMS part $partId", e)
            false
        }
    }

    private fun fetchSmsFromTable(threadId: Long, messages: MutableList<Message>) {
        val smsUri = Telephony.Sms.CONTENT_URI
        contentResolver.query(smsUri, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "date ASC")?.use { c ->
            val idCol = c.getColumnIndex(Telephony.Sms._ID)
            while (c.moveToNext()) {
                fetchSmsMessage(c.getLong(idCol), messages)
            }
        }
    }

    private fun fetchMmsFromTable(threadId: Long, messages: MutableList<Message>, attachmentsDir: File) {
        val mmsUri = Telephony.Mms.CONTENT_URI
        contentResolver.query(mmsUri, arrayOf(Telephony.Mms._ID), "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), "date ASC")?.use { c ->
            val idCol = c.getColumnIndex(Telephony.Mms._ID)
            while (c.moveToNext()) {
                fetchMmsMessage(c.getLong(idCol), messages, attachmentsDir)
            }
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun getContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return phoneNumber
        contactCache[phoneNumber]?.let { return it }

        val uri = Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
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
        } catch (_: Exception) {
        }
        return phoneNumber
    }

    private var reactionProviderStatus: Int = 0 // 0: Unknown, 1: Exists, 2: Missing

    private fun getReactions(messageId: Long): List<String> {
        if (reactionProviderStatus == 2) return emptyList()

        val reactions = mutableListOf<String>()
        val uris = arrayOf(
            "content://telephony/message_reactions".toUri(),
            "content://message_reactions".toUri(),
        )

        for (uri in uris) {
            try {
                contentResolver.query(uri, null, "message_id = ?", arrayOf(messageId.toString()), null)?.use { cursor ->
                    reactionProviderStatus = 1
                    val descCol = cursor.getColumnIndex("reaction_description")
                    while (cursor.moveToNext()) {
                        val reaction = if (descCol != -1) cursor.getString(descCol) else null
                        if (reaction != null) reactions.add(reaction)
                    }
                }
                if (reactions.isNotEmpty() || reactionProviderStatus == 1) break
            } catch (e: Exception) {
                if (e is SecurityException || e.message?.contains("find provider") == true) {
                    reactionProviderStatus = 2
                }
            }
        }
        
        if (reactionProviderStatus == 0) reactionProviderStatus = 2
        return reactions
    }

    private fun getMmsAddress(mmsId: Long): String {
        val uri = "content://mms/$mmsId/addr".toUri()
        contentResolver.query(uri, null, "msg_id=$mmsId AND type=137", null, null)?.use { cursor ->
            val addressCol = cursor.getColumnIndex("address")
            if (cursor.moveToFirst() && addressCol != -1) {
                val address = cursor.getString(addressCol)
                if (address != "insert-address-token") return address
            }
        }
        return "Unknown"
    }

    data class Message(
        val id: Long,
        val type: String,
        val address: String,
        val senderName: String,
        var body: String,
        val date: Long,
        val protocol: String,
        val reactions: List<String> = emptyList(),
        val rcsMessageId: String? = null,
        val replyToId: String? = null,
        val attachments: MutableList<Attachment> = mutableListOf()
    )

    data class Attachment(
        val type: String,
        val localPath: String
    )
}

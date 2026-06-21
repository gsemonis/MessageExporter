package com.sway.messageexporter

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageExporter(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val contactCache = mutableMapOf<String, String>()

    fun exportThread(threadId: Long, outputFile: File, onProgress: ((Int, Int) -> Unit)? = null): Boolean {
        val messages = mutableListOf<Message>()
        val attachmentsDir = File(outputFile.parentFile, "attachments_${outputFile.nameWithoutExtension}")
        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()

        // 1. Get initial total count for progress
        val smsCount = getTableCount(Telephony.Sms.CONTENT_URI, threadId)
        val mmsCount = getTableCount(Telephony.Mms.CONTENT_URI, threadId)
        val initialTotal = smsCount + mmsCount
        onProgress?.invoke(0, initialTotal)
        Log.d("MessageExporter", "Pre-fetch count: SMS=$smsCount, MMS=$mmsCount, Total=$initialTotal")

        // 2. Get addresses for this thread
        val addresses = getThreadAddresses(threadId)

        // 3. Bulk Fetch SMS with progress
        fetchSmsBulk(threadId, addresses, messages) { current ->
            onProgress?.invoke(current, initialTotal)
        }
        
        // 4. Bulk Fetch MMS with progress
        val smsFetchedCount = messages.size
        fetchMmsBulk(threadId, messages, attachmentsDir) { current ->
            onProgress?.invoke(smsFetchedCount + current, initialTotal)
        }

        // 5. Finalize Count & Reporting
        val fetchedTotal = messages.size
        Log.d("MessageExporter", "Final message count found: $fetchedTotal")

        messages.sortBy { it.date }
        val distinctMessages = messages.distinctBy { "${it.protocol}_${it.id}" }
        val finalTotal = distinctMessages.size
        
        // 6. Final Write with progress
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
                
                var writeCount = 0
                distinctMessages.forEach { msg ->
                    writeCount++
                    // Ensure display progress is accurate compared to final total
                    onProgress?.invoke(writeCount, finalTotal)

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
            
            val xsdFile = File(outputFile.parentFile, "messages.xsd")
            copyXsdFromAssets(xsdFile)
            true
        } catch (e: Exception) {
            Log.e("MessageExporter", "Failed to write XML", e)
            false
        }
    }

    private fun getTableCount(uri: Uri, threadId: Long): Int {
        return try {
            contentResolver.query(uri, arrayOf("COUNT(*)"), "thread_id = ?", arrayOf(threadId.toString()), null)?.use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            } ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun fetchSmsBulk(threadId: Long, addresses: List<String>, messages: MutableList<Message>, onProgress: (Int) -> Unit) {
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        
        contentResolver.query(uri, projection, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "date ASC")?.use { c ->
            addSmsFromCursor(c, messages, onProgress)
        }

        if (messages.size < 10 && addresses.isNotEmpty()) {
            addresses.forEach { address ->
                contentResolver.query(uri, projection, "address LIKE ?", arrayOf("%$address%"), "date ASC")?.use { c ->
                    addSmsFromCursor(c, messages, onProgress)
                }
            }
        }
    }

    private fun addSmsFromCursor(c: android.database.Cursor, messages: MutableList<Message>, onProgress: (Int) -> Unit) {
        val idCol = c.getColumnIndex(Telephony.Sms._ID)
        val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
        val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
        val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
        val typeCol = c.getColumnIndex(Telephony.Sms.TYPE)

        while (c.moveToNext()) {
            onProgress(messages.size + 1)
            
            val id = c.getLong(idCol)
            val address = c.getString(addrCol) ?: "Unknown"
            val type = c.getInt(typeCol)
            messages.add(Message(
                id = id,
                type = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "INCOMING" else "OUTGOING",
                address = address,
                senderName = getContactName(address),
                body = c.getString(bodyCol) ?: "",
                date = c.getLong(dateCol),
                protocol = "SMS",
                reactions = getReactions(id)
            ))
        }
    }

    private fun fetchMmsBulk(threadId: Long, messages: MutableList<Message>, attachmentsDir: File, onProgress: (Int) -> Unit) {
        val uri = Telephony.Mms.CONTENT_URI
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
        
        contentResolver.query(uri, projection, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), "date ASC")?.use { c ->
            val idCol = c.getColumnIndex(Telephony.Mms._ID)
            val dateCol = c.getColumnIndex(Telephony.Mms.DATE)
            val boxCol = c.getColumnIndex(Telephony.Mms.MESSAGE_BOX)

            var count = 0
            while (c.moveToNext()) {
                count++
                onProgress(count)
                
                val id = c.getLong(idCol)
                val date = c.getLong(dateCol) * 1000
                val box = c.getInt(boxCol)
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
        val uri = "content://mms/part".toUri()
        val bodyBuilder = StringBuilder()
        
        contentResolver.query(uri, null, "mid=$mmsId", null, null)?.use { cursor ->
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
                        message.attachments.add(Attachment(type = ct, localPath = "attachments/$fileName"))
                    }
                }
            }
        }
        message.body = bodyBuilder.toString().trim()
    }

    private fun getExtensionFromMimeType(mimeType: String): String {
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        if (extension != null) return extension
        return mimeType.substringAfter("/").substringBefore(";").lowercase()
    }

    private fun savePartToFile(partId: Long, destFile: File): Boolean {
        return try {
            contentResolver.openInputStream("content://mms/part/$partId".toUri())?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) { false }
    }

    private fun copyXsdFromAssets(destFile: File) {
        try {
            context.assets.open("messages.xsd").use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {}
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
                            if (ac.moveToFirst()) ac.getString(0)?.let { result.add(it) }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return result
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }

    private fun getContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return phoneNumber
        contactCache[phoneNumber]?.let { return it }
        val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        try {
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    contactCache[phoneNumber] = name
                    return name
                }
            }
        } catch (_: Exception) {}
        return phoneNumber
    }

    private var reactionProviderStatus: Int = 0 

    private fun getReactions(messageId: Long): List<String> {
        if (reactionProviderStatus == 2) return emptyList()
        val reactions = mutableListOf<String>()
        val uris = arrayOf("content://telephony/message_reactions".toUri(), "content://message_reactions".toUri())
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
                if (e is SecurityException || e.message?.contains("find provider") == true) reactionProviderStatus = 2
            }
        }
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

    data class Attachment(val type: String, val localPath: String)
}

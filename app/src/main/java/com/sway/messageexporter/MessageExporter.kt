package com.sway.messageexporter

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageExporter(private val contentResolver: ContentResolver) {

    private val contactCache = mutableMapOf<String, String>()

    fun exportThread(threadId: Long, outputFile: File): Boolean {
        val messages = mutableListOf<Message>()

        // 1. Get SMS
        val smsUri = Telephony.Sms.CONTENT_URI
        val smsProjection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            "message_id",
            "replied_to_message_id"
        )
        val smsSelection = "${Telephony.Sms.THREAD_ID} = ?"
        contentResolver.query(smsUri, smsProjection, smsSelection, arrayOf(threadId.toString()), "date ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndex(Telephony.Sms._ID)
            val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)
            val typeCol = cursor.getColumnIndex(Telephony.Sms.TYPE)
            val msgIdCol = cursor.getColumnIndex("message_id")
            val replyCol = cursor.getColumnIndex("replied_to_message_id")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val address = cursor.getString(addressCol) ?: "Unknown"
                val name = getContactName(address)
                messages.add(
                    Message(
                        id = id,
                        type = if (cursor.getInt(typeCol) == Telephony.Sms.MESSAGE_TYPE_INBOX) "INCOMING" else "OUTGOING",
                        address = address,
                        senderName = name,
                        body = cursor.getString(bodyCol) ?: "",
                        date = cursor.getLong(dateCol),
                        protocol = "SMS",
                        reactions = getReactions(id),
                        rcsMessageId = if (msgIdCol != -1) cursor.getString(msgIdCol) else null,
                        replyToId = if (replyCol != -1) cursor.getString(replyCol) else null
                    )
                )
            }
        }

        // 2. Get MMS (and often RCS)
        val mmsUri = Telephony.Mms.CONTENT_URI
        val mmsProjection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
            "message_id",
            "replied_to_message_id"
        )
        val mmsSelection = "${Telephony.Mms.THREAD_ID} = ?"
        contentResolver.query(mmsUri, mmsProjection, mmsSelection, arrayOf(threadId.toString()), "date ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndex(Telephony.Mms._ID)
            val dateCol = cursor.getColumnIndex(Telephony.Mms.DATE)
            val boxCol = cursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)
            val msgIdCol = cursor.getColumnIndex("message_id")
            val replyCol = cursor.getColumnIndex("replied_to_message_id")

            while (cursor.moveToNext()) {
                val mmsId = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol) * 1000 // MMS date is in seconds
                val box = cursor.getInt(boxCol)
                
                val body = getMmsBody(mmsId)
                val address = getMmsAddress(mmsId)
                val name = getContactName(address)

                messages.add(
                    Message(
                        id = mmsId,
                        type = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) "INCOMING" else "OUTGOING",
                        address = address,
                        senderName = name,
                        body = body,
                        date = date,
                        protocol = "MMS/RCS",
                        reactions = getReactions(mmsId),
                        rcsMessageId = if (msgIdCol != -1) cursor.getString(msgIdCol) else null,
                        replyToId = if (replyCol != -1) cursor.getString(replyCol) else null
                    )
                )
            }
        }

        messages.sortBy { it.date }

        return try {
            outputFile.bufferedWriter().use { out ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                out.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                out.write("<thread id=\"$threadId\">\n")
                
                // Add participants section for better structure
                val participants = messages.map { it.senderName to it.address }.distinct()
                out.write("  <participants>\n")
                participants.forEach { (name, address) ->
                    out.write("    <participant name=\"${escapeXml(name)}\" address=\"${escapeXml(address)}\" />\n")
                }
                out.write("  </participants>\n")
                
                messages.forEach { msg ->
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
            Log.e("MessageExporter", "Failed to write XML export", e)
            false
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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
        } catch (ignored: Exception) {
        }
        return phoneNumber
    }

    private fun getReactions(messageId: Long): List<String> {
        val reactions = mutableListOf<String>()
        val uris = arrayOf(
            Uri.parse("content://telephony/message_reactions"),
            Uri.parse("content://message_reactions")
        )

        for (uri in uris) {
            try {
                contentResolver.query(uri, null, "message_id = ?", arrayOf(messageId.toString()), null)?.use { cursor ->
                    val descCol = cursor.getColumnIndex("reaction_description")
                    while (cursor.moveToNext()) {
                        val reaction = if (descCol != -1) cursor.getString(descCol) else null
                        if (reaction != null) reactions.add(reaction)
                    }
                }
                if (reactions.isNotEmpty()) break
            } catch (ignored: Exception) {
            }
        }
        return reactions
    }

    private fun getMmsBody(mmsId: Long): String {
        val selection = "mid=$mmsId"
        val uri = Uri.parse("content://mms/part")
        val body = StringBuilder()
        contentResolver.query(uri, null, selection, null, null)?.use { cursor ->
            val textCol = cursor.getColumnIndex("text")
            val dataCol = cursor.getColumnIndex("_data")
            val ctCol = cursor.getColumnIndex("ct")
            
            while (cursor.moveToNext()) {
                val ct = cursor.getString(ctCol) ?: ""
                when {
                    ct == "text/plain" || ct == "application/smil" -> {
                        body.append(cursor.getString(textCol) ?: "")
                    }
                    ct.startsWith("image/") -> {
                        body.append("[Image: ${cursor.getString(dataCol) ?: "attachment"}]")
                    }
                    ct.startsWith("video/") -> {
                        body.append("[Video: ${cursor.getString(dataCol) ?: "attachment"}]")
                    }
                    else -> {
                        body.append("[$ct]")
                    }
                }
            }
        }
        return body.toString().trim()
    }

    private fun getMmsAddress(mmsId: Long): String {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        contentResolver.query(uri, null, "msg_id=$mmsId AND type=137", null, null)?.use { cursor ->
            val addressCol = cursor.getColumnIndex("address")
            if (cursor.moveToFirst()) {
                val addr = cursor.getString(addressCol)
                if (addr != "insert-address-token") return addr
            }
        }
        return "Unknown"
    }

    data class Message(
        val id: Long,
        val type: String,
        val address: String,
        val senderName: String,
        val body: String,
        val date: Long,
        val protocol: String,
        val reactions: List<String> = emptyList(),
        val rcsMessageId: String? = null,
        val replyToId: String? = null
    )
}

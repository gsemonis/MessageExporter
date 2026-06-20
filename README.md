# MessageExporter

An Android application to export messaging threads (SMS, MMS, and RCS) into a structured XML format. This tool is designed for personal use to archive conversations, including text, media references, emoji reactions, and reply threading.

## Features

- **Multi-Protocol Support**: Exports SMS, MMS, and RCS messages.
- **RCS Metadata**: Captures emoji reactions (tapbacks) and threaded reply relationships.
- **Contact Integration**: Automatically resolves phone numbers to contact names.
- **Structured XML Export**: Generates a clean, machine-readable XML file for each thread.
- **Easy Sharing**: Uses Android's Share Sheet to send the exported XML via email, drive, or other apps.
- **Performance Optimized**: Uses Kotlin Coroutines for background processing to ensure a smooth UI experience.

## XML Format

The exported XML includes:
- Thread ID and participant list.
- Message metadata: ID, type (incoming/outgoing), protocol, and timestamps.
- Sender details (Name and Address).
- Message body text.
- Emoji reactions.
- Reply linkage for RCS threads (`reply_to` attribute).

## Usage

1. **Launch the App**: Grant the requested permissions for `READ_SMS` and `READ_CONTACTS`.
2. **Select a Thread**: Browse the list of conversations and tap on the one you wish to export.
3. **Export & Share**: Wait for the background process to complete, then use the sharing dialog to save your file.

## Requirements

- Android 16 (API level 36) or higher.
- Built with Kotlin and modern Android Jetpack components.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

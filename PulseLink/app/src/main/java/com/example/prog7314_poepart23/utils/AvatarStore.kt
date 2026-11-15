/**
 * Lightweight on-device store for a user's chosen profile picture.
 *
 * The picked image is copied into the app's private files directory (keyed by Firebase UID) so
 * it survives navigation, activity recreation and app restarts, without needing a network upload
 * pipeline or a FileProvider — reads happen in-process, so a plain file:// Uri is safe to hand to
 * ImageView.setImageURI().
 */

package com.example.prog7314_poepart23.utils

import android.content.Context
import android.net.Uri
import java.io.File

object AvatarStore {

    private fun avatarFile(context: Context, uid: String): File {
        val dir = File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }
        return File(dir, "avatar_$uid.jpg")
    }

    /** Copies [source] into private storage for [uid]. Returns the resulting local file Uri, or null on failure. */
    fun save(context: Context, uid: String, source: Uri): Uri? {
        return try {
            val dest = avatarFile(context, uid)
            val copied = context.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (copied) Uri.fromFile(dest) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the stored avatar Uri for [uid], or null if none has been saved yet. */
    fun get(context: Context, uid: String): Uri? {
        val file = avatarFile(context, uid)
        return if (file.exists() && file.length() > 0) Uri.fromFile(file) else null
    }
}

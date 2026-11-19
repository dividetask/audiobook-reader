package com.dividetask.audiobookreader.remote

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where books are synced: an SSH/SFTP server or any S3-compatible bucket. */
enum class StorageKind(val label: String) { SFTP("SFTP server"), S3("S3-compatible") }

/**
 * An upload that was started but never finished (S3 multipart). [relPath] is relative to the folder;
 * [current] = it's the one this phone would continue for that path.
 */
data class UnfinishedUpload(
    val relPath: String,
    val uploadId: String,
    val initiated: String,
    val size: Long,
    val current: Boolean
)

/** One open connection to the storage. [RemoteFile.relPath] is relative to the configured folder. */
interface StorageConnection {
    /** Human-readable location, e.g. "/srv/sftp/Audiobooks" or "my-bucket/Audiobooks". */
    val root: String
    fun listAudio(): List<RemoteFile>
    /** Uploads [local] to [rel]; resumes an interrupted upload. [progress] returns false to stop. */
    fun upload(local: File, rel: String, progress: (Long, Long) -> Boolean)
    /** Downloads [rel] into [local], resuming if [local] already holds part of it. */
    fun download(rel: String, local: File, total: Long, progress: (Long, Long) -> Boolean)
    /** Uploads started but never finished. SFTP has none: it writes the file in place. */
    fun unfinishedUploads(): List<UnfinishedUpload> = emptyList()
    /** Cancels [upload] on the server, freeing the space its parts use. */
    fun abortUpload(upload: UnfinishedUpload) {}
}

/** The storage chosen in Settings. Blocking — call from Dispatchers.IO. */
@Singleton
class RemoteStorage @Inject constructor(
    private val settings: ServerSettings,
    private val sftp: SftpClient,
    private val s3: S3Client
) {
    fun <T> use(block: (StorageConnection) -> T): T = when (settings.storageKind) {
        StorageKind.SFTP -> sftp.use(block)
        StorageKind.S3 -> s3.use(block)
    }
}

package com.dividetask.audiobookreader.remote

import android.util.Base64
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** An audio file on the server. [relPath] is relative to the configured folder. */
data class RemoteFile(val relPath: String, val size: Long, val modified: Long) {
    val name: String get() = relPath.substringAfterLast('/')
    val title: String get() = name.substringBeforeLast('.')
    /** The folder the file sits in (normally the author), or "" at the top level. */
    val folder: String get() = relPath.substringBeforeLast('/', "").substringAfterLast('/')
}

/** Readable error for anything that goes wrong talking to the server. */
class ServerException(message: String, cause: Throwable? = null) : IOException(message, cause)

val AUDIO_EXTENSIONS = setOf("m4b", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav", "aax")

@Singleton
class SftpClient @Inject constructor(
    private val settings: ServerSettings,
    private val debugLog: AppDebugLog
) {
    /** Opens a connection, runs [block], and always disconnects. Blocking — call from Dispatchers.IO. */
    fun <T> use(block: (SftpConnection) -> T): T {
        if (!settings.isConfigured) throw ServerException("Server not set up — add it in Settings")
        val host = settings.host
        val port = settings.port
        val hostId = "$host:$port"

        val tofu = TofuHostKeys(hostId, settings.hostKey)
        val jsch = JSch().apply { hostKeyRepository = tofu }
        val session: Session = jsch.getSession(settings.username, host, port)
        session.setPassword(settings.password)
        session.userInfo = PasswordUserInfo(settings.password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // Android has no Ed25519/X25519 support, so offer only algorithms it can run
        session.setConfig("server_host_key", "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
        session.setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group14-sha256")
        val ciphers = "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr"
        session.setConfig("cipher.s2c", ciphers)
        session.setConfig("cipher.c2s", ciphers)
        session.timeout = 30_000
        session.setServerAliveInterval(15_000)

        try {
            debugLog.log("SFTP", "Connecting to $hostId as ${settings.username}")
            session.connect(15_000)
            tofu.newKey?.let {
                settings.hostKey = "$hostId $it"
                debugLog.log("SFTP", "Trusted new server key ${fingerprint(it)}")
            }
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            try {
                val root = resolveRoot(channel.home, settings.folder)
                return block(SftpConnection(channel, root))
            } finally {
                channel.disconnect()
            }
        } catch (e: ServerException) {
            throw e
        } catch (e: JSchChangedHostKeyException) {
            throw ServerException("Server key changed — if you reinstalled the server, tap \"Forget server key\" in Settings", e)
        } catch (e: JSchException) {
            throw ServerException(describe(e), e)
        } catch (e: SftpException) {
            throw ServerException("SFTP error: ${e.message}", e)
        } finally {
            session.disconnect()
        }
    }

    private fun resolveRoot(home: String, folder: String): String {
        val f = folder.trim().trimEnd('/')
        return when {
            f.isEmpty() || f == "~" -> home
            f.startsWith("/") -> f
            f.startsWith("~/") -> "$home/${f.removePrefix("~/")}"
            else -> "$home/$f"
        }
    }

    private fun describe(e: JSchException): String {
        val msg = e.message ?: ""
        return when (val cause = e.cause) {
            is UnknownHostException -> "Unknown host \"${settings.host}\""
            is ConnectException -> "Connection refused — is SSH running on port ${settings.port}?"
            is NoRouteToHostException -> "No route to ${settings.host} — is the server reachable from this network?"
            is SocketTimeoutException -> "Timed out connecting to ${settings.host}:${settings.port}"
            else -> when {
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) -> "Wrong username or password"
                msg.contains("Algorithm negotiation fail", true) -> "Server offers no supported key type (needs ECDSA or RSA host key)"
                msg.contains("timeout", true) -> "Timed out connecting to ${settings.host}:${settings.port}"
                cause != null -> "${cause::class.simpleName}: ${cause.message}"
                else -> msg.ifBlank { "Connection failed" }
            }
        }
    }

    companion object {
        fun fingerprint(keyBase64: String): String {
            val bytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}

class SftpConnection(private val channel: ChannelSftp, val root: String) {

    private fun abs(rel: String) = if (rel.isEmpty()) root else "$root/$rel"

    /** Every audio file under the root folder, up to a few levels deep. */
    fun listAudio(): List<RemoteFile> {
        if (stat("") == null) return emptyList()
        val out = mutableListOf<RemoteFile>()
        fun walk(rel: String, depth: Int) {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(abs(rel)) as java.util.Vector<ChannelSftp.LsEntry>
            for (entry in entries) {
                val name = entry.filename
                if (name.startsWith(".")) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                val attrs = entry.attrs
                if (attrs.isDir) {
                    if (depth < 4) walk(childRel, depth + 1)
                } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    out.add(RemoteFile(childRel, attrs.size, attrs.mTime * 1000L))
                }
            }
        }
        walk("", 0)
        return out.sortedBy { it.relPath.lowercase() }
    }

    fun stat(rel: String): SftpATTRS? = try {
        channel.stat(abs(rel))
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
    }

    fun mkdirs(relDir: String) {
        var path = ""
        for (part in relDir.split('/').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) part else "$path/$part"
            if (stat(path) == null) channel.mkdir(abs(path))
        }
    }

    /**
     * Uploads to "<name>.part" (resuming a previous partial upload) and renames
     * when complete, so a half-uploaded book never looks finished.
     */
    fun upload(local: File, rel: String, progress: (Long, Long) -> Boolean) {
        rel.substringBeforeLast('/', "").let { if (it.isNotEmpty()) mkdirs(it) }
        val part = abs(rel) + ".part"
        val existing = stat("$rel.part")?.size ?: 0L
        val mode = if (existing in 1 until local.length()) ChannelSftp.RESUME else ChannelSftp.OVERWRITE
        channel.put(local.absolutePath, part, Monitor(local.length(), if (mode == ChannelSftp.RESUME) existing else 0, progress), mode)
        val uploaded = channel.stat(part).size
        if (uploaded != local.length()) {
            throw IOException("Upload incomplete: $uploaded of ${local.length()} bytes")
        }
        channel.rename(part, abs(rel))
    }

    /** Downloads [rel] into [local], resuming if [local] already holds part of it. */
    fun download(rel: String, local: File, total: Long, progress: (Long, Long) -> Boolean) {
        val existing = if (local.exists()) local.length() else 0L
        val mode = if (existing in 1 until total) ChannelSftp.RESUME else ChannelSftp.OVERWRITE
        channel.get(abs(rel), local.absolutePath, Monitor(total, if (mode == ChannelSftp.RESUME) existing else 0, progress), mode)
    }

    private class Monitor(
        private val total: Long,
        start: Long,
        private val progress: (Long, Long) -> Boolean
    ) : SftpProgressMonitor {
        private var done = start
        override fun init(op: Int, src: String?, dest: String?, max: Long) {}
        override fun count(count: Long): Boolean {
            done += count
            return progress(done, total)
        }
        override fun end() {}
    }
}

/** Trust-on-first-use host key check: accept the first key seen, reject a different one later. */
private class TofuHostKeys(private val hostId: String, stored: String) : HostKeyRepository {
    private val trusted: String? = stored.takeIf { it.substringBefore(' ') == hostId }?.substringAfter(' ')
    var newKey: String? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
        return when (trusted) {
            null -> { newKey = b64; HostKeyRepository.OK }
            b64 -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "tofu"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private class PasswordUserInfo(private val pw: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String = pw
    override fun promptPassword(message: String?) = true
    override fun promptPassphrase(message: String?) = false
    override fun promptYesNo(message: String?) = false
    override fun showMessage(message: String?) {}
    override fun promptKeyboardInteractive(
        destination: String?, name: String?, instruction: String?,
        prompt: Array<out String>?, echo: BooleanArray?
    ): Array<String> = Array(prompt?.size ?: 0) { pw }
}

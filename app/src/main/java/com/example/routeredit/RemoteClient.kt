package com.example.routeredit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PublicKey
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class Entry(val name: String, val isDir: Boolean, val size: Long)

class ExecResult(val code: Int, val outBytes: ByteArray, val err: String) {
    val out: String get() = outBytes.toString(Charsets.UTF_8)
}

/** Single-quote a string for the remote shell. */
fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * SSH connection to a router. Uses SFTP when the server offers it, otherwise
 * falls back to plain SSH exec (ls / cat), which works with a bare Dropbear
 * that has no sftp-server installed.
 */
class RemoteClient {
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    val usingSftp: Boolean get() = sftp != null

    suspend fun connect(
        host: String,
        port: Int,
        user: String,
        secret: String,
        keyText: String?,
        trust: (hostId: String, fingerprint: String) -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            disconnectBlocking()
            val c = SSHClient()
            try {
                c.connectTimeout = 15000
                c.timeout = 30000
                c.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                        trust("$hostname:$port", SecurityUtils.getFingerprint(key))

                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                        emptyList()
                })
                c.connect(host, port)
                if (keyText != null) {
                    val finder = if (secret.isNotEmpty())
                        PasswordUtils.createOneOff(secret.toCharArray()) else null
                    c.authPublickey(user, c.loadKeys(keyText, null, finder))
                } else {
                    c.authPassword(user, secret)
                }
            } catch (e: Exception) {
                runCatching { c.disconnect() }
                throw e
            }
            ssh = c
            sftp = try {
                c.newSFTPClient()
            } catch (e: Exception) {
                null // server has no SFTP subsystem -> exec fallback
            }
        }
    }

    private fun execBlocking(cmd: String, input: ByteArray? = null): ExecResult {
        val session = ssh!!.startSession()
        try {
            val c = session.exec(cmd)
            var err = ByteArray(0)
            val errThread = thread { err = runCatching { c.errorStream.readBytes() }.getOrDefault(ByteArray(0)) }
            c.outputStream.use { if (input != null) it.write(input) }
            val out = c.inputStream.readBytes()
            errThread.join(30000)
            c.join(30, TimeUnit.SECONDS)
            return ExecResult(c.exitStatus ?: -1, out, err.toString(Charsets.UTF_8))
        } finally {
            runCatching { session.close() }
        }
    }

    suspend fun run(cmd: String): ExecResult = withContext(Dispatchers.IO) { execBlocking(cmd) }

    suspend fun list(dir: String): List<Entry> = withContext(Dispatchers.IO) {
        val s = sftp
        val items: List<Entry> = if (s != null) {
            s.ls(dir)
                .filter { it.name != "." && it.name != ".." }
                .map { r ->
                    var isDir = r.isDirectory
                    if (!isDir && r.attributes.type == FileMode.Type.SYMLINK) {
                        isDir = runCatching { s.stat(r.path).type == FileMode.Type.DIRECTORY }
                            .getOrDefault(false)
                    }
                    Entry(r.name, isDir, r.attributes.size)
                }
        } else {
            val r = execBlocking("ls -1ApL ${q(dir)}")
            if (r.code != 0 && r.outBytes.isEmpty()) {
                throw IOException(r.err.ifBlank { "ls failed (exit ${r.code})" })
            }
            r.out.lines().filter { it.isNotBlank() }.map { l ->
                if (l.endsWith("/")) Entry(l.dropLast(1), true, 0) else Entry(l, false, 0)
            }
        }
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    suspend fun read(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val s = sftp
        if (s != null) {
            s.open(path).use { f ->
                val len = f.length()
                if (len > maxBytes) throw IOException("File too large to edit here (${len / 1024} KB)")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32768)
                var off = 0L
                while (true) {
                    val n = f.read(off, buf, 0, buf.size)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    off += n
                }
                out.toByteArray()
            }
        } else {
            val r = execBlocking("cat ${q(path)}")
            if (r.code != 0) throw IOException(r.err.ifBlank { "cat failed (exit ${r.code})" })
            if (r.outBytes.size > maxBytes) {
                throw IOException("File too large to edit here (${r.outBytes.size / 1024} KB)")
            }
            r.outBytes
        }
    }

    suspend fun write(path: String, data: ByteArray) {
        withContext(Dispatchers.IO) {
            // One-time backup next to the original: file.bak (never overwritten).
            runCatching {
                execBlocking("[ -e ${q(path)} ] && [ ! -e ${q(path + ".bak")} ] && cp -p ${q(path)} ${q(path + ".bak")}")
            }
            val s = sftp
            if (s != null) {
                s.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { f ->
                    var off = 0
                    while (off < data.size) {
                        val n = minOf(32768, data.size - off)
                        f.write(off.toLong(), data, off, n)
                        off += n
                    }
                }
            } else {
                val r = execBlocking("cat > ${q(path)}", data)
                if (r.code != 0) throw IOException(r.err.ifBlank { "write failed (exit ${r.code})" })
            }
        }
    }

    private fun disconnectBlocking() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { disconnectBlocking() }
}

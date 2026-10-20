package com.example.routeredit

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class Screen { LOGIN, BROWSER, EDITOR }

private const val MAX_EDIT_BYTES = 256 * 1024

fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

fun parentPath(path: String): String =
    path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

class AppVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("routeredit", Context.MODE_PRIVATE)
    private val client = RemoteClient()

    var screen by mutableStateOf(Screen.LOGIN)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    // login form
    var host by mutableStateOf(prefs.getString("host", "192.168.1.1") ?: "192.168.1.1")
    var port by mutableStateOf(prefs.getString("port", "22") ?: "22")
    var user by mutableStateOf(prefs.getString("user", "root") ?: "root")
    var secret by mutableStateOf("")
    var keyText by mutableStateOf<String?>(null)
        private set
    var keyName by mutableStateOf<String?>(null)
        private set

    // browser
    var cwd by mutableStateOf("/etc/config")
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var transport by mutableStateOf("")
        private set

    // editor
    var filePath by mutableStateOf("")
        private set
    var text by mutableStateOf("")
    private var original by mutableStateOf("")
    val dirty: Boolean get() = text != original

    // command runner
    var cmdOutput by mutableStateOf<String?>(null)

    private fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun loadKey(uri: Uri) = task {
        val ctx = getApplication<Application>()
        val content = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: throw IOException("Could not read key file")
        if (!content.contains("PRIVATE KEY")) throw IOException("That doesn't look like a private key file")
        keyText = content
        keyName = uri.lastPathSegment?.substringAfterLast('/') ?: "key"
    }

    fun clearKey() {
        keyText = null
        keyName = null
    }

    fun forgetHostKey() {
        val p = port.toIntOrNull() ?: 22
        prefs.edit().remove("fp_${host.trim()}:$p").apply()
        message = "Saved host key forgotten"
    }

    fun connect() = task {
        val p = port.toIntOrNull() ?: 22
        try {
            client.connect(host.trim(), p, user.trim(), secret, keyText) { id, fp ->
                val k = "fp_$id"
                val old = prefs.getString(k, null)
                if (old == null) {
                    prefs.edit().putString(k, fp).apply()
                    true
                } else {
                    old == fp
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("host key", ignoreCase = true) == true) {
                throw IOException(
                    "Host key changed. If you reflashed or reset the router, tap \"Forget saved host key\" and connect again."
                )
            }
            throw e
        }
        prefs.edit()
            .putString("host", host.trim())
            .putString("port", port)
            .putString("user", user.trim())
            .apply()
        transport = if (client.usingSftp) "SFTP" else "SSH exec mode (server has no SFTP)"
        screen = Screen.BROWSER
        try {
            loadDir(cwd)
        } catch (e: Exception) {
            loadDir("/")
        }
    }

    private suspend fun loadDir(path: String) {
        entries = client.list(path)
        cwd = path
    }

    fun goTo(path: String) = task { loadDir(path) }
    fun goUp() = task { loadDir(parentPath(cwd)) }
    fun refresh() = task { loadDir(cwd) }

    fun openEntry(e: Entry) {
        val p = joinPath(cwd, e.name)
        if (e.isDir) {
            task { loadDir(p) }
        } else {
            task {
                val bytes = client.read(p, MAX_EDIT_BYTES)
                if (bytes.any { it == 0.toByte() }) throw IOException("Looks like a binary file")
                val s = bytes.toString(Charsets.UTF_8)
                filePath = p
                text = s
                original = s
                screen = Screen.EDITOR
            }
        }
    }

    fun save() = task {
        client.write(filePath, text.toByteArray(Charsets.UTF_8))
        original = text
        message = "Saved"
    }

    fun closeEditor() {
        screen = Screen.BROWSER
    }

    fun runCommand(cmd: String) = task {
        val r = client.run(cmd)
        cmdOutput = buildString {
            append("exit ${r.code}\n")
            append(r.out)
            if (r.err.isNotEmpty()) append("\n").append(r.err)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            client.disconnect()
            secret = ""
            entries = emptyList()
            screen = Screen.LOGIN
        }
    }
}

package com.example.routeredit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android ships a stripped-down "BC" provider; SSHJ needs the full one.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App()
            }
        }
    }
}

@Composable
fun App(vm: AppVm = viewModel()) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.screen) {
                Screen.LOGIN -> LoginScreen(vm)
                Screen.BROWSER -> BrowserScreen(vm)
                Screen.EDITOR -> EditorScreen(vm)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
fun LoginScreen(vm: AppVm) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadKey(uri)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("RouterEdit", style = MaterialTheme.typography.headlineMedium)
        Text("Edit router config files over SSH", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = vm.host, onValueChange = { vm.host = it },
            label = { Text("Router IP / hostname") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = vm.user, onValueChange = { vm.user = it },
                label = { Text("User") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = vm.port, onValueChange = { vm.port = it },
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.6f),
            )
        }
        OutlinedTextField(
            value = vm.secret, onValueChange = { vm.secret = it },
            label = { Text(if (vm.keyText != null) "Key passphrase (if any)" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(vm.keyName ?: "Use private key (optional)")
            }
            if (vm.keyText != null) TextButton(onClick = { vm.clearKey() }) { Text("Clear") }
        }
        Button(
            onClick = { vm.connect() },
            enabled = !vm.busy && vm.host.isNotBlank() && vm.user.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
        TextButton(onClick = { vm.forgetHostKey() }) { Text("Forget saved host key") }
    }
}

@Composable
fun BrowserScreen(vm: AppVm) {
    var showCmd by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.cwd != "/") { vm.goUp() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.goUp() }, enabled = vm.cwd != "/") { Text("↑ Up") }
            Text(
                vm.cwd, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            TextButton(onClick = { vm.refresh() }) { Text("⟳") }
            TextButton(onClick = { showCmd = true }) { Text("Run") }
            TextButton(onClick = { vm.disconnect() }) { Text("Exit") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("/etc/config", "/etc", "/tmp", "/root", "/").forEach { p ->
                AssistChip(onClick = { vm.goTo(p) }, label = { Text(p) })
            }
        }
        Text(
            "Mode: ${vm.transport}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(vm.entries, key = { it.name }) { e ->
                ListItem(
                    headlineContent = { Text(if (e.isDir) e.name + "/" else e.name) },
                    supportingContent = {
                        if (!e.isDir && e.size > 0) Text(formatSize(e.size))
                    },
                    modifier = Modifier.clickable { vm.openEntry(e) },
                )
                HorizontalDivider()
            }
        }
    }
    if (showCmd) CommandDialog(vm) { showCmd = false }
}

private fun formatSize(n: Long) = if (n < 1024) "$n B" else "${n / 1024} KB"

@Composable
fun CommandDialog(vm: AppVm, onClose: () -> Unit) {
    var cmd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onClose(); vm.cmdOutput = null },
        title = { Text("Run command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it },
                    placeholder = { Text("e.g. uci show network") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Restarting network/wifi can drop this connection.",
                    style = MaterialTheme.typography.labelSmall,
                )
                vm.cmdOutput?.let {
                    Text(
                        it,
                        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.runCommand(cmd) }, enabled = cmd.isNotBlank() && !vm.busy) { Text("Run") }
        },
        dismissButton = {
            TextButton(onClick = { onClose(); vm.cmdOutput = null }) { Text("Close") }
        },
    )
}

@Composable
fun EditorScreen(vm: AppVm) {
    var confirm by remember { mutableStateOf(false) }
    val close = { if (vm.dirty) confirm = true else vm.closeEditor() }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close) { Text("‹ Back") }
            Text(
                vm.filePath.substringAfterLast('/') + if (vm.dirty) " •" else "",
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Button(onClick = { vm.save() }, enabled = vm.dirty && !vm.busy) { Text("Save") }
        }
        OutlinedTextField(
            value = vm.text,
            onValueChange = { vm.text = it },
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved edits.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.closeEditor() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Keep editing") }
            },
        )
    }
}

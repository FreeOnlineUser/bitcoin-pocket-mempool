package com.pocketmempool.ui.mempool

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketmempool.rpc.BitcoinRpcClient
import com.pocketmempool.storage.ConnectionPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    onNavigateToMempool: () -> Unit,
    viewModel: ConnectionSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    var host by remember { mutableStateOf("localhost") }
    var port by remember { mutableStateOf("8332") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useSSL by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected) }
    
    // Load saved settings
    LaunchedEffect(Unit) {
        val prefs = ConnectionPreferences(context)
        host = prefs.getHost()
        port = prefs.getPort().toString()
        username = prefs.getUsername()
        password = prefs.getPassword()
        useSSL = prefs.getUseSSL()
        
        // Check if connection is configured and working
        if (prefs.isConfigured()) {
            // Test existing connection
            scope.launch {
                connectionStatus = ConnectionStatus.Connecting
                try {
                    val client = BitcoinRpcClient(
                        rpcHost = prefs.getHost(),
                        rpcPort = prefs.getPort(),
                        rpcUser = prefs.getUsername(),
                        rpcPassword = prefs.getPassword()
                    )
                    client.getBlockchainInfo()
                    connectionStatus = ConnectionStatus.Connected
                    // Auto-navigate to mempool if connection works
                    onNavigateToMempool()
                } catch (e: Exception) {
                    connectionStatus = ConnectionStatus.Error(e.message ?: "Connection failed")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Bitcoin Node Connection",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Connect to any Bitcoin node with RPC access to explore its mempool",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Quick presets
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Quick Setup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            host = "localhost"
                            port = "8332"
                            useSSL = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Local Node")
                    }
                    
                    OutlinedButton(
                        onClick = {
                            host = ""
                            port = "8332"
                            useSSL = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Remote Node")
                    }
                }
                
                // Paste from clipboard (e.g. copied from Pocket Node)
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).text?.toString() ?: ""
                            // Parse RPC URI format: rpc://user:pass@host:port
                            // or JSON format: {"host":"...","port":...,"user":"...","pass":"..."}
                            val parsed = parseConnectionString(text)
                            if (parsed != null) {
                                host = parsed.host
                                port = parsed.port.toString()
                                username = parsed.user
                                password = parsed.pass
                                useSSL = parsed.ssl
                                Toast.makeText(context, "Connection details pasted!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Clipboard doesn't contain RPC connection details", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Paste from Clipboard")
                }
            }
        }
        
        // Connection settings
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Connection Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    placeholder = { Text("localhost or IP address") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Storage, contentDescription = null)
                    }
                )
                
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    placeholder = { Text("8332") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                )
                
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("RPC Username") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    }
                )
                
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("RPC Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    }
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Switch(
                        checked = useSSL,
                        onCheckedChange = { useSSL = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use SSL/HTTPS")
                }
            }
        }
        
        // Connection status
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (connectionStatus) {
                    is ConnectionStatus.Disconnected -> {
                        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Text("Not connected", color = MaterialTheme.colorScheme.outline)
                    }
                    is ConnectionStatus.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Connecting...", color = MaterialTheme.colorScheme.primary)
                    }
                    is ConnectionStatus.Connected -> {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Connected", color = MaterialTheme.colorScheme.primary)
                    }
                    is ConnectionStatus.Error -> {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Column {
                            Text("Connection failed", color = MaterialTheme.colorScheme.error)
                            val errorMessage = (connectionStatus as ConnectionStatus.Error).message
                            Text(
                                errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        connectionStatus = ConnectionStatus.Connecting
                        try {
                            val client = BitcoinRpcClient(
                                rpcHost = host.trim(),
                                rpcPort = port.toIntOrNull() ?: 8332,
                                rpcUser = username.trim(),
                                rpcPassword = password
                            )
                            client.getBlockchainInfo()
                            connectionStatus = ConnectionStatus.Connected
                        } catch (e: Exception) {
                            connectionStatus = ConnectionStatus.Error(e.message ?: "Connection failed")
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Connection")
            }
            
            Button(
                onClick = {
                    // Save settings
                    val prefs = ConnectionPreferences(context)
                    prefs.saveConnection(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 8332,
                        username = username.trim(),
                        password = password,
                        useSSL = useSSL
                    )
                    onNavigateToMempool()
                },
                modifier = Modifier.weight(1f),
                enabled = connectionStatus is ConnectionStatus.Connected
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect")
            }
        }
    }
}

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

class ConnectionSettingsViewModel : androidx.lifecycle.ViewModel() {
    // Add any necessary view model logic here
}

private data class ParsedConnection(
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val ssl: Boolean = false
)

private fun parseConnectionString(text: String): ParsedConnection? {
    val trimmed = text.trim()
    
    // Try rpc://user:pass@host:port format
    val rpcRegex = Regex("""rpc(s?)://([^:]+):([^@]+)@([^:]+):(\d+)""")
    rpcRegex.find(trimmed)?.let { match ->
        return ParsedConnection(
            host = match.groupValues[4],
            port = match.groupValues[5].toIntOrNull() ?: 8332,
            user = match.groupValues[2],
            pass = match.groupValues[3],
            ssl = match.groupValues[1] == "s"
        )
    }
    
    // Try JSON format: {"host":"...","port":...,"user":"...","pass":"..."}
    try {
        if (trimmed.startsWith("{")) {
            val json = org.json.JSONObject(trimmed)
            return ParsedConnection(
                host = json.optString("host", "localhost"),
                port = json.optInt("port", 8332),
                user = json.optString("user", json.optString("username", "")),
                pass = json.optString("pass", json.optString("password", "")),
                ssl = json.optBoolean("ssl", false)
            )
        }
    } catch (_: Exception) {}
    
    // Try simple host:port:user:pass format
    val parts = trimmed.split(":")
    if (parts.size >= 4) {
        return ParsedConnection(
            host = parts[0],
            port = parts[1].toIntOrNull() ?: 8332,
            user = parts[2],
            pass = parts.drop(3).joinToString(":")
        )
    }
    
    return null
}
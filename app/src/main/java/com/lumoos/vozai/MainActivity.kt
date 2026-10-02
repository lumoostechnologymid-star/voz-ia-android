package com.lumoos.vozai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VozIAApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VozIAApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("voz_ia", Activity.MODE_PRIVATE) }
    val recorder = remember { VoiceRecorder(context) }
    val player = remember { AudioPlayer() }

    val defaultServer = "https://powhyjspdfsvvhtftttr.supabase.co/functions/v1/voz-ia"
    val storedServer = prefs.getString("server_url", null)
    var serverUrl by remember {
        mutableStateOf(
            if (storedServer.isNullOrBlank() || storedServer.contains("10.0.2.2")) defaultServer
            else storedServer
        )
    }

    var tab by remember { mutableIntStateOf(0) }
    var voiceName by remember { mutableStateOf("Mi voz") }
    var consent by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var voiceId by remember { mutableStateOf(prefs.getString("voice_id", "") ?: "") }
    var status by remember { mutableStateOf("Conectando con servidor...") }

    val messages = remember { mutableStateListOf<ApiClient.Message>() }
    var input by remember { mutableStateOf("") }
    var partialSpeech by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var handsFree by remember { mutableStateOf(false) }
    var pendingAutoSend by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        prefs.edit().putString("server_url", serverUrl).apply()
        try {
            val h = withContext(Dispatchers.IO) { ApiClient(serverUrl).health() }
            status = when {
                !h.ok -> "Servidor no disponible"
                !h.voiceConfigured && !h.chatConfigured -> "Servidor conectado · faltan claves de IA y voz"
                !h.voiceConfigured -> "Servidor conectado · falta configurar voz"
                !h.chatConfigured -> "Servidor conectado · falta configurar IA"
                else -> "Servidor conectado"
            }
        } catch (e: Exception) {
            status = "No se pudo conectar al servidor: ${e.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permiso de micrófono concedido" else "Se requiere permiso de micrófono"
    }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun ensureMic(): Boolean {
        if (hasMic()) return true
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    val speech = remember {
        SpeechInputController(
            context,
            onListeningChanged = { listening = it },
            onPartial = { partialSpeech = it },
            onFinal = {
                partialSpeech = ""
                input = it
                if (handsFree) pendingAutoSend = it
            },
            onError = { status = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose { speech.destroy(); recorder.cancel(); player.stop() }
    }

    fun startListening() {
        if (!ensureMic()) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "Reconocimiento de voz no disponible"
            return
        }
        status = "Escuchando..."
        speech.startListening("es-MX")
    }

    fun sendMessage(textValue: String, continueHandsFree: Boolean = handsFree) {
        val text = textValue.trim()
        if (text.isBlank() || busy) return
        if (voiceId.isBlank()) { status = "Primero crea un perfil de voz"; return }

        val history = messages.toList()
        messages.add(ApiClient.Message("user", text))
        input = ""
        partialSpeech = ""
        busy = true
        speech.cancel()
        status = "Pensando..."

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { ApiClient(serverUrl).chat(text, history) }
                messages.add(ApiClient.Message("assistant", reply))
                status = "Generando voz..."
                val audio = File(context.cacheDir, "reply_${System.currentTimeMillis()}.mp3")
                withContext(Dispatchers.IO) { ApiClient(serverUrl).speak(reply, voiceId, audio) }
                status = "Hablando..."
                player.play(audio) {
                    status = "Listo"
                    if (continueHandsFree && handsFree) scope.launch { delay(350); startListening() }
                }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(pendingAutoSend) {
        pendingAutoSend?.trim()?.takeIf { it.isNotBlank() }?.let {
            pendingAutoSend = null
            sendMessage(it, true)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Voz IA") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Text("🎙️") }, label = { Text("Voz") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Text("💬") }, label = { Text("Conversación") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    prefs.edit().putString("server_url", it).apply()
                },
                label = { Text("Servidor backend") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Text("Estado: $status", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                Text("Crear perfil de voz", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    voiceName,
                    { voiceName = it },
                    label = { Text("Nombre de la voz") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Button({
                    if (!ensureMic()) return@Button
                    try {
                        if (!recording) {
                            sampleFile = recorder.start()
                            recording = true
                            status = "Grabando muestra..."
                        } else {
                            sampleFile = recorder.stop()
                            recording = false
                            val kb = (sampleFile?.length() ?: 0L) / 1024
                            status = "Muestra guardada · ${kb} KB"
                        }
                    } catch (e: Exception) {
                        recording = false
                        status = "Error de grabación: ${e.message}"
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Text(if (recording) "Detener grabación" else "Grabar muestra")
                }

                if (sampleFile != null && !recording) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton({
                        sampleFile?.let {
                            status = "Reproduciendo muestra..."
                            player.play(it) { status = "Muestra lista" }
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                        Text("Escuchar muestra")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it })
                    Text("Confirmo que esta voz es mía o tengo permiso explícito.")
                }

                Button({
                    val sample = sampleFile ?: run {
                        status = "Primero graba y detén una muestra"
                        return@Button
                    }
                    if (!consent) {
                        status = "Confirma la autorización"
                        return@Button
                    }
                    busy = true
                    status = "Subiendo muestra y creando perfil..."
                    scope.launch {
                        try {
                            val id = withContext(Dispatchers.IO) {
                                ApiClient(serverUrl).createVoice(voiceName, sample, consent)
                            }
                            voiceId = id
                            prefs.edit().putString("voice_id", id).apply()
                            status = "✓ Perfil de voz creado"
                        } catch (e: Exception) {
                            status = "No se pudo crear: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = sampleFile != null && consent && !busy && !recording) {
                    Text(if (busy) "Creando..." else "Crear perfil de voz")
                }

                if (voiceId.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("✓ Perfil configurado. Ya puedes ir a Conversación.")
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Conversación", style = MaterialTheme.typography.titleLarge)
                    Switch(
                        checked = handsFree,
                        onCheckedChange = {
                            if (it && voiceId.isNotBlank()) {
                                handsFree = true
                                startListening()
                            } else {
                                handsFree = false
                                speech.cancel()
                                player.stop()
                            }
                        },
                        enabled = voiceId.isNotBlank() && !busy
                    )
                }

                Text(if (handsFree) "Modo manos libres activo" else "Puedes escribir o usar el micrófono")
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (msg.role == "user") "Tú" else "IA", style = MaterialTheme.typography.labelMedium)
                                Text(msg.text)
                            }
                        }
                    }
                }

                if (partialSpeech.isNotBlank()) Text("🎤 $partialSpeech")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        input,
                        { input = it },
                        label = { Text("Escribe o dicta") },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !handsFree
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        { if (listening) speech.stopListening() else startListening() },
                        enabled = !busy && !handsFree
                    ) {
                        Text(if (listening) "⏹" else "🎤")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    { sendMessage(input) },
                    enabled = input.isNotBlank() && voiceId.isNotBlank() && !busy && !handsFree,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Procesando..." else "Enviar y escuchar")
                }
            }
        }
    }
}

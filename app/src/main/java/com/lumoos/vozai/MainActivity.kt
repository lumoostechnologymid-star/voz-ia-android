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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    val clipboard = LocalClipboardManager.current
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

    val initialVault = remember {
        prefs.getString("vault_id", null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("vault_id", it).apply() }
    }

    var vaultId by remember { mutableStateOf(initialVault) }
    var recoveryDraft by remember { mutableStateOf(initialVault) }
    var tab by remember { mutableIntStateOf(0) }

    var voiceName by remember { mutableStateOf("Mi voz") }
    var consent by remember { mutableStateOf(false) }
    var cleanAudio by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf(false) }
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var voiceId by remember { mutableStateOf(prefs.getString("voice_id", "") ?: "") }
    var activeVoiceName by remember { mutableStateOf(prefs.getString("voice_name", "") ?: "") }
    var status by remember { mutableStateOf("Conectando con servidor...") }
    var busy by remember { mutableStateOf(false) }

    val profiles = remember { mutableStateListOf<ApiClient.VoiceProfile>() }
    var renamingProfile by remember { mutableStateOf<ApiClient.VoiceProfile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingProfile by remember { mutableStateOf<ApiClient.VoiceProfile?>(null) }

    val messages = remember { mutableStateListOf<ApiClient.Message>() }
    var input by remember { mutableStateOf("") }
    var partialSpeech by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var handsFree by remember { mutableStateOf(false) }
    var pendingAutoSend by remember { mutableStateOf<String?>(null) }

    fun selectProfile(profile: ApiClient.VoiceProfile) {
        voiceId = profile.voiceId
        activeVoiceName = profile.name
        prefs.edit()
            .putString("voice_id", profile.voiceId)
            .putString("voice_name", profile.name)
            .apply()
        status = "✓ Voz activa: ${profile.name}"
    }

    LaunchedEffect(Unit) {
        prefs.edit().putString("server_url", serverUrl).apply()
        try {
            val api = ApiClient(serverUrl)
            val h = withContext(Dispatchers.IO) { api.health() }
            status = when {
                !h.ok -> "Servidor no disponible"
                !h.voiceConfigured && !h.chatConfigured -> "Servidor conectado · faltan claves de IA y voz"
                !h.voiceConfigured -> "Servidor conectado · falta configurar voz"
                !h.chatConfigured -> "Servidor conectado · falta configurar IA"
                else -> "Servidor conectado"
            }

            var loaded = withContext(Dispatchers.IO) { api.listProfiles(vaultId) }

            // Migra automáticamente la voz guardada por versiones anteriores.
            if (loaded.isEmpty() && voiceId.isNotBlank()) {
                val migrated = withContext(Dispatchers.IO) {
                    api.saveProfile(
                        vaultId,
                        activeVoiceName.ifBlank { "Voz anterior" },
                        voiceId
                    )
                }
                loaded = listOf(migrated)
            }

            profiles.clear()
            profiles.addAll(loaded)

            val current = loaded.firstOrNull { it.voiceId == voiceId }
            when {
                current != null -> {
                    activeVoiceName = current.name
                    prefs.edit().putString("voice_name", current.name).apply()
                }
                loaded.isNotEmpty() -> selectProfile(loaded.first())
            }
        } catch (e: Exception) {
            status = "No se pudo conectar: ${e.message}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status = if (granted) "Permiso de micrófono concedido" else "Se requiere permiso de micrófono"
    }

    fun hasMic() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

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
        onDispose {
            speech.destroy()
            recorder.cancel()
            player.stop()
        }
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
        if (voiceId.isBlank()) {
            status = "Primero selecciona un perfil de voz"
            return
        }

        val history = messages.toList()
        messages.add(ApiClient.Message("user", text))
        input = ""
        partialSpeech = ""
        busy = true
        speech.cancel()
        status = "Pensando..."

        scope.launch {
            try {
                val api = ApiClient(serverUrl)
                val reply = withContext(Dispatchers.IO) {
                    api.chat(text, history, vaultId)
                }
                messages.add(ApiClient.Message("assistant", reply))
                status = "Generando voz con ${activeVoiceName.ifBlank { "perfil activo" }}..."
                val audio = File(context.cacheDir, "reply_${System.currentTimeMillis()}.mp3")
                withContext(Dispatchers.IO) {
                    api.speak(reply, voiceId, vaultId, audio)
                }
                status = "Hablando..."
                player.play(audio) {
                    status = "Listo"
                    if (continueHandsFree && handsFree) {
                        scope.launch {
                            delay(350)
                            startListening()
                        }
                    }
                }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun testProfile(profile: ApiClient.VoiceProfile) {
        if (busy) return
        busy = true
        status = "Preparando prueba de ${profile.name}..."
        scope.launch {
            try {
                val audio = File(context.cacheDir, "test_${System.currentTimeMillis()}.mp3")
                withContext(Dispatchers.IO) {
                    ApiClient(serverUrl).speak(
                        "Hola. Esta es una prueba de mi voz.",
                        profile.voiceId,
                        vaultId,
                        audio
                    )
                }
                player.play(audio) { status = "Prueba finalizada" }
            } catch (e: Exception) {
                status = "Error al probar voz: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun loadRecoveryCode() {
        val candidate = recoveryDraft.trim()
        try {
            UUID.fromString(candidate)
        } catch (_: Exception) {
            status = "Código de recuperación inválido"
            return
        }

        busy = true
        status = "Recuperando biblioteca..."
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    ApiClient(serverUrl).listProfiles(candidate)
                }
                vaultId = candidate
                prefs.edit().putString("vault_id", candidate).apply()
                profiles.clear()
                profiles.addAll(loaded)

                if (loaded.isNotEmpty()) {
                    selectProfile(loaded.first())
                    status = "✓ Biblioteca recuperada: ${loaded.size} voz/vozes"
                } else {
                    voiceId = ""
                    activeVoiceName = ""
                    prefs.edit().remove("voice_id").remove("voice_name").apply()
                    status = "Biblioteca recuperada, pero no tiene voces"
                }
            } catch (e: Exception) {
                status = "No se pudo recuperar: ${e.message}"
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

    renamingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { renamingProfile = null },
            title = { Text("Renombrar voz") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Nombre") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameText.trim()
                        if (newName.isBlank()) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                val updated = withContext(Dispatchers.IO) {
                                    ApiClient(serverUrl).renameProfile(
                                        vaultId,
                                        profile.id,
                                        newName
                                    )
                                }
                                val index = profiles.indexOfFirst { it.id == profile.id }
                                if (index >= 0) profiles[index] = updated
                                if (voiceId == updated.voiceId) {
                                    activeVoiceName = updated.name
                                    prefs.edit().putString("voice_name", updated.name).apply()
                                }
                                status = "✓ Voz renombrada"
                            } catch (e: Exception) {
                                status = "No se pudo renombrar: ${e.message}"
                            } finally {
                                busy = false
                                renamingProfile = null
                            }
                        }
                    },
                    enabled = renameText.isNotBlank() && !busy
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { renamingProfile = null }) { Text("Cancelar") }
            }
        )
    }

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text("Eliminar de biblioteca") },
            text = {
                Text(
                    "Se quitará “${profile.name}” de esta biblioteca. " +
                        "La voz original seguirá existiendo en ElevenLabs."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    ApiClient(serverUrl).deleteProfile(vaultId, profile.id)
                                }
                                val index = profiles.indexOfFirst { it.id == profile.id }
                                if (index >= 0) profiles.removeAt(index)

                                if (voiceId == profile.voiceId) {
                                    if (profiles.isNotEmpty()) {
                                        selectProfile(profiles.first())
                                    } else {
                                        voiceId = ""
                                        activeVoiceName = ""
                                        prefs.edit().remove("voice_id").remove("voice_name").apply()
                                    }
                                }
                                status = "Perfil eliminado de la biblioteca"
                            } catch (e: Exception) {
                                status = "No se pudo eliminar: ${e.message}"
                            } finally {
                                busy = false
                                deletingProfile = null
                            }
                        }
                    },
                    enabled = !busy
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Voz IA") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("🎙️") },
                    label = { Text("Crear") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("🗂️") },
                    label = { Text("Mis voces") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("💬") },
                    label = { Text("Conversación") }
                )
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
        ) {
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
            if (activeVoiceName.isNotBlank()) {
                Text("Voz activa: $activeVoiceName", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> {
                    Text("Crear perfil de voz", style = MaterialTheme.typography.titleLarge)
                    Text("Puedes guardar varias voces y cambiar entre ellas después.")
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = voiceName,
                        onValueChange = { voiceName = it },
                        label = { Text("Nombre de la voz") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
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
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) {
                        Text(if (recording) "Detener grabación" else "Grabar muestra")
                    }

                    if (sampleFile != null && !recording) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                sampleFile?.let {
                                    status = "Reproduciendo muestra..."
                                    player.play(it) { status = "Muestra lista" }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        ) {
                            Text("Escuchar muestra")
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text("Confirmo que esta voz es mía o tengo permiso explícito.")
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Limpieza IA de voz", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Reduce ruido, ambiente y reverberación antes de clonar.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = cleanAudio,
                                    onCheckedChange = { cleanAudio = it },
                                    enabled = !busy
                                )
                            }
                            if (cleanAudio) {
                                Text(
                                    "Recomendado para una voz más limpia y fiel.",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val sample = sampleFile ?: run {
                                status = "Primero graba y detén una muestra"
                                return@Button
                            }
                            if (!consent) {
                                status = "Confirma la autorización"
                                return@Button
                            }
                            if (voiceName.isBlank()) {
                                status = "Escribe un nombre para la voz"
                                return@Button
                            }

                            busy = true
                            status = if (cleanAudio) "Limpiando audio con IA y creando perfil..." else "Creando y guardando perfil..."
                            scope.launch {
                                try {
                                    val profile = withContext(Dispatchers.IO) {
                                        ApiClient(serverUrl).createVoice(
                                            voiceName.trim(),
                                            sample,
                                            consent,
                                            vaultId,
                                            cleanAudio
                                        )
                                    }
                                    profiles.add(0, profile)
                                    selectProfile(profile)
                                    voiceName = "Mi voz"
                                    consent = false
                                    sampleFile = null
                                    status = if (cleanAudio) "✓ Perfil limpio guardado en Mis voces" else "✓ Perfil guardado en Mis voces"
                                } catch (e: Exception) {
                                    status = "No se pudo crear: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = sampleFile != null && consent && !busy && !recording
                    ) {
                        Text(if (busy) "Creando..." else "Crear y guardar perfil")
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Perfiles guardados: ${profiles.size}")
                    if (profiles.isNotEmpty()) {
                        TextButton(onClick = { tab = 1 }) {
                            Text("Ver Mis voces →")
                        }
                    }
                }

                1 -> {
                    Text("Mis voces", style = MaterialTheme.typography.titleLarge)
                    Text("Selecciona cuál quieres usar en la conversación.")
                    Spacer(Modifier.height(8.dp))

                    if (profiles.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "Todavía no hay perfiles en esta biblioteca.",
                                Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(profiles, key = { it.id }) { profile ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    profile.name,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                if (profile.voiceId == voiceId) {
                                                    Text(
                                                        "✓ Voz activa",
                                                        style = MaterialTheme.typography.labelMedium
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { selectProfile(profile) },
                                                modifier = Modifier.weight(1f),
                                                enabled = !busy && profile.voiceId != voiceId
                                            ) { Text(if (profile.voiceId == voiceId) "Activa" else "Usar") }

                                            OutlinedButton(
                                                onClick = { testProfile(profile) },
                                                modifier = Modifier.weight(1f),
                                                enabled = !busy
                                            ) { Text("Probar") }
                                        }

                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    renameText = profile.name
                                                    renamingProfile = profile
                                                },
                                                modifier = Modifier.weight(1f),
                                                enabled = !busy
                                            ) { Text("Renombrar") }

                                            TextButton(
                                                onClick = { deletingProfile = profile },
                                                modifier = Modifier.weight(1f),
                                                enabled = !busy
                                            ) { Text("Eliminar") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("Recuperar voces en otro teléfono", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Guarda este código como una contraseña. Quien lo tenga puede recuperar tu biblioteca.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = recoveryDraft,
                        onValueChange = { recoveryDraft = it },
                        label = { Text("Código de recuperación") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(vaultId))
                                status = "Código copiado"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Copiar") }

                        Button(
                            onClick = { loadRecoveryCode() },
                            modifier = Modifier.weight(1f),
                            enabled = !busy
                        ) { Text("Cargar") }
                    }
                }

                else -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Conversación", style = MaterialTheme.typography.titleLarge)
                            if (activeVoiceName.isNotBlank()) {
                                Text("Responde con: $activeVoiceName")
                            }
                        }
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
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { msg ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        if (msg.role == "user") "Tú" else "IA",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(msg.text)
                                }
                            }
                        }
                    }

                    if (partialSpeech.isNotBlank()) Text("🎤 $partialSpeech")

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Escribe o dicta") },
                            modifier = Modifier.weight(1f),
                            enabled = !busy && !handsFree
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (listening) speech.stopListening() else startListening()
                            },
                            enabled = !busy && !handsFree
                        ) {
                            Text(if (listening) "⏹" else "🎤")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { sendMessage(input) },
                        enabled = input.isNotBlank() && voiceId.isNotBlank() && !busy && !handsFree,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "Procesando..." else "Enviar y escuchar")
                    }
                }
            }
        }
    }
}

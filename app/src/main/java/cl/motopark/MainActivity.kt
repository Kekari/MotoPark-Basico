package cl.motopark

import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MotoPark() }
    }
}

@Composable
fun MotoPark() {
    val contexto = LocalContext.current
    val datos = remember { DatosLocales(contexto) }
    var usuarios by remember { mutableStateOf(datos.leerUsuarios()) }
    var usuario by remember { mutableStateOf<Usuario?>(null) }
    var nombre by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var nuevoNombre by remember { mutableStateOf("") }
    var nuevaClave by remember { mutableStateOf("") }
    var pantalla by remember { mutableStateOf("Monitoreo") }
    var distancia by remember { mutableStateOf<Int?>(null) }
    var sistemaActivo by remember { mutableStateOf(false) }
    var dispositivo by remember { mutableStateOf<String?>(null) }
    var buzzerActivo by remember { mutableStateOf(false) }
    var sonidoActivo by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf("") }
    var avisoSistema by remember { mutableStateOf("") }
    var registros by remember { mutableStateOf(emptyList<RegistroDistancia>()) }

    var paginaAuditoria by remember { mutableStateOf(0) }
    var cargandoAuditoria by remember { mutableStateOf(false) }
    val bluetooth = remember { BluetoothTransport(contexto) }
    val ambito = rememberCoroutineScope()
    var tarea by remember { mutableStateOf<Job?>(null) }
    var conectando by remember { mutableStateOf(false) }
    var listaDispositivos by remember { mutableStateOf<List<PairedDevice>?>(null) }
    var ultimoElegido by remember { mutableStateOf<PairedDevice?>(null) }
    var estadoRecibido by remember { mutableStateOf(false) }
    var ultimaLectura by remember { mutableStateOf(0L) }
    var ultimoEstado by remember { mutableStateOf(0L) }
    var ultimoRegistro by remember { mutableStateOf(0L) }
    var cambioPendiente by remember { mutableStateOf("") }
    var enviadoEn by remember { mutableStateOf(0L) }
    var sesion by remember { mutableStateOf(0) }

    fun cargarAuditoria() {
        val autor = usuario?.nombre ?: return
        if (cargandoAuditoria) return
        cargandoAuditoria = true
        ambito.launch {
            try {
                val lista = withContext(Dispatchers.IO) { datos.leerHoy() }
                if (usuario?.nombre == autor) {
                    registros = lista; paginaAuditoria = 0; pantalla = "Auditoría"; mensaje = ""
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { avisoSistema = "No se pudo leer la auditoría." }
            finally { cargandoAuditoria = false }
        }
    }
    fun desconectar() {
        sesion++
        tarea?.cancel()
        bluetooth.close()
        dispositivo = null; distancia = null; sistemaActivo = false
        buzzerActivo = false; conectando = false; estadoRecibido = false; cambioPendiente = ""
    }

    fun enviar(comando: String) {
        val numero = sesion
        cambioPendiente = comando; enviadoEn = SystemClock.elapsedRealtime()
        ambito.launch {
            try { bluetooth.send(comando + "\n") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (numero == sesion) { desconectar(); avisoSistema = "Se perdió la conexión. Usa Reconectar Bluetooth." }
            }
        }
    }

    fun listarDispositivos() {
        if (dispositivo != null || conectando) {
            avisoSistema = "Ya hay una conexión activa o en curso. Desconecta antes de elegir otro dispositivo."
            return
        }
        try { listaDispositivos = bluetooth.pairedDevices() }
        catch (error: Exception) { avisoSistema = error.message ?: "Revisa Bluetooth y sus permisos." }
    }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido && usuario != null) listarDispositivos()
        else if (!concedido) avisoSistema = "Permite Dispositivos cercanos en Ajustes > Aplicaciones > MotoPark Básico > Permisos."
    }
    fun elegirDispositivo() {
        if (Build.VERSION.SDK_INT >= 31 && contexto.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else listarDispositivos()
    }

    fun conectar(elegido: PairedDevice) {
        if (usuario == null) return
        if (dispositivo != null || conectando) { avisoSistema = "Ya hay una conexión. Desconecta primero."; return }
        val autor = usuario!!.nombre
        ultimoElegido = elegido; listaDispositivos = null; conectando = true
        sesion++
        val numero = sesion
        tarea = ambito.launch {
            try {
                bluetooth.run(elegido.address, onConnected = {
                    if (numero == sesion) {
                        dispositivo = elegido.name; conectando = false
                        ultimoEstado = SystemClock.elapsedRealtime(); ultimoRegistro = 0
                    }
                }, onFrame = { dato ->
                    if (numero == sesion) {
                        val ahora = SystemClock.elapsedRealtime()
                        if (dato is Frame.Status) {
                            if (!dato.monitoring || !sistemaActivo) distancia = null
                            sistemaActivo = dato.monitoring; buzzerActivo = dato.buzzer
                            estadoRecibido = true; ultimoEstado = ahora
                            if (cambioPendiente == "M:${if (dato.monitoring) 1 else 0}" ||
                                cambioPendiente == "B:${if (dato.buzzer) 1 else 0}") cambioPendiente = ""
                        } else if (dato is Frame.Distance && sistemaActivo) {
                            distancia = dato.cm; ultimaLectura = ahora
                            // Guardamos como máximo una lectura real por segundo.
                            if (dato.cm != null && ahora - ultimoRegistro >= 1000) {
                                ultimoRegistro = ahora
                                try { withContext(Dispatchers.IO) { datos.guardarDistancia(autor, "${elegido.name} (${elegido.address})", dato.cm) } }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) { avisoSistema = "No se pudo guardar la distancia en la auditoría." }
                            }
                        }
                    }
                })
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (numero == sesion) { desconectar(); avisoSistema = "No se pudo conectar o se perdió el enlace. Revisa el HC-05, el emparejamiento y que no esté ocupado." }
            }
        }
    }

    // Consultamos el estado real del Arduino y ocultamos lecturas antiguas.
    LaunchedEffect(dispositivo) {
        if (dispositivo != null) {
            val numero = sesion
            while (isActive && numero == sesion) {
                try { bluetooth.send("Q\n") }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { desconectar(); avisoSistema = "Se perdió Bluetooth. Puedes reconectar."; break }
                val ahora = SystemClock.elapsedRealtime()
                if (ahora - ultimaLectura >= 2000) distancia = null
                if (ahora - ultimoEstado >= 3500 || (cambioPendiente.isNotEmpty() && ahora - enviadoEn >= 2000)) {
                    desconectar(); avisoSistema = "Arduino no responde o no confirmó el comando. Revisa el sketch compatible."; break
                }
                delay(250)
            }
        }
    }

    // Al salir de la pantalla se cierra Bluetooth y cesan las alertas.
    DisposableEffect(contexto) {
        val actividad = contexto as androidx.activity.ComponentActivity
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_STOP) desconectar()
        }
        actividad.lifecycle.addObserver(observador)
        onDispose { actividad.lifecycle.removeObserver(observador); desconectar() }
    }

    val lecturaActual by rememberUpdatedState(distancia)
    LaunchedEffect(sonidoActivo, sistemaActivo, dispositivo) {
        if (sonidoActivo && sistemaActivo && dispositivo != null) {
            val tono = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }.getOrNull()
            if (tono != null) {
                var ultimoPitido = 0L
                try {
                    while (isActive) {
                        val intervalo = Parking.beepInterval(lecturaActual)
                        val ahora = SystemClock.elapsedRealtime()
                        if (intervalo != null && ahora - ultimoPitido >= intervalo) {
                            tono.startTone(ToneGenerator.TONE_PROP_BEEP, 90); ultimoPitido = ahora
                        } else if (intervalo == null) tono.stopTone()
                        delay(20)
                    }
                } finally { tono.stopTone(); tono.release() }
            }
        }
    }

    val aviso = when (Parking.proximity(distancia)) {
        Proximity.DANGER -> "Peligro"
        Proximity.CAUTION -> "Precaución"
        Proximity.CLEAR -> "Distancia segura"
        else -> "Sin datos del sensor"
    }
    val colorAviso = if (distancia == null) Color.LightGray else if (distancia!! <= 15) Color.Red else if (distancia!! <= 40) Color(0xFFFF9800) else Color.Green
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF21D879))) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                // Solo el contenido se desplaza; la navegación permanece abajo.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("MotoPark", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Bluetooth HC-05 ", color = Color.LightGray)

                    if (usuario == null) {
                        val primeraCuenta = usuarios.isEmpty()
                        Text(if (primeraCuenta) "Crear primer administrador" else "Iniciar sesión", fontSize = 20.sp)
                        if (primeraCuenta) Text("La primera cuenta será administradora de este teléfono.")
                        OutlinedTextField(nombre, { nombre = it }, label = { Text("Usuario") }, singleLine = true)
                        OutlinedTextField(clave, { clave = it }, label = { Text("Contraseña") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            isError = primeraCuenta && clave.isNotEmpty() && !claveSegura(clave),
                            supportingText = {
                                if (primeraCuenta) Text(if (clave.isNotEmpty() && !claveSegura(clave))
                                    "Contraseña insegura: usa letras, números y al menos una mayúscula (6 a 100 caracteres)."
                                    else "Usa letras, números y al menos una mayúscula (6 a 100 caracteres).")
                            })
                        Button(onClick = {
                            try {
                                val cuenta = if (primeraCuenta) datos.crearUsuario(null, nombre, clave)
                                    else datos.iniciarSesion(nombre, clave)
                                usuarios = datos.leerUsuarios()
                                usuario = cuenta
                                nombre = ""; clave = ""; mensaje = ""
                                pantalla = "Monitoreo"
                            } catch (error: Exception) { mensaje = error.message ?: "No se pudo iniciar sesión." }
                        }) { Text(if (primeraCuenta) "Crear administrador" else "Entrar") }
                        if (mensaje.isNotEmpty()) Text(mensaje, color = Color(0xFFFFB74D))
                    } else {
                        val actual = usuario!!
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${actual.nombre} · ${if (actual.administrador) "Administrador" else "Usuario"}")
                            TextButton(onClick = {
                                desconectar(); usuario = null; listaDispositivos = null
                                clave = ""; nuevaClave = ""; nuevoNombre = ""; mensaje = ""; avisoSistema = ""; registros = emptyList()
                            }) { Text("Salir") }
                        }
                        Text(if (conectando) "Conectando…" else if (dispositivo == null) "Bluetooth desconectado" else "Conectado a $dispositivo")
                        if (dispositivo == null) {
                            Button(enabled = !conectando, onClick = {
                                val elegido = ultimoElegido
                                if (elegido != null && (Build.VERSION.SDK_INT < 31 || contexto.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) conectar(elegido)
                                else elegirDispositivo()
                            }) { Text(if (ultimoElegido == null) "Conectar Bluetooth" else "Reconectar Bluetooth") }
                        }
                        if (conectando) TextButton(onClick = { desconectar() }) { Text("Cancelar conexión") }
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101010), contentColor = Color.White),
                            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (pantalla == "Monitoreo") {
                                    Text("Distancia detectada", fontWeight = FontWeight.Bold)
                                    Text(if (sistemaActivo && dispositivo != null && distancia != null) "$distancia cm" else "— cm", fontSize = 42.sp)
                                    Text(if (sistemaActivo) aviso else "Sistema inactivo", color = if (sistemaActivo) colorAviso else Color.LightGray)
                                    Button(enabled = dispositivo != null && estadoRecibido && cambioPendiente.isEmpty(), onClick = {
                                        enviar(if (sistemaActivo) "M:0" else "M:1")
                                    }) { Text(if (sistemaActivo) "Desactivar sistema" else "Activar sistema") }
                                    Text(if (cambioPendiente.isNotEmpty()) "Esperando confirmación del Arduino…" else "Las distancias llegan del sensor. Se registra hasta una lectura por segundo.")
                                } else if (pantalla == "Configuración") {
                                    Text("Configuración del sistema", fontWeight = FontWeight.Bold)
                                    Button(enabled = dispositivo != null, onClick = { desconectar() }) { Text("Desconectar Bluetooth") }
                                    TextButton(onClick = { elegirDispositivo() }) { Text("Elegir dispositivo") }
                                    TextButton(onClick = { contexto.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Ajustes de Bluetooth") }
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("Buzzer Arduino", Modifier.weight(1f))
                                        Switch(buzzerActivo, { enviar(if (it) "B:1" else "B:0") }, enabled = dispositivo != null && estadoRecibido && cambioPendiente.isEmpty())
                                    }
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("Sonido del teléfono", Modifier.weight(1f))
                                        Switch(sonidoActivo, { sonidoActivo = it })
                                    }
                                    if (actual.administrador) {
                                        Text("Crear usuarios", fontWeight = FontWeight.Bold)
                                        OutlinedTextField(nuevoNombre, { nuevoNombre = it }, label = { Text("Nuevo usuario") }, singleLine = true)
                                        OutlinedTextField(nuevaClave, { nuevaClave = it }, label = { Text("Contraseña nueva") },
                                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                            isError = nuevaClave.isNotEmpty() && !claveSegura(nuevaClave),
                                            supportingText = {
                                                Text(if (nuevaClave.isNotEmpty() && !claveSegura(nuevaClave))
                                                    "Contraseña insegura: usa letras, números y al menos una mayúscula (6 a 100 caracteres)."
                                                    else "Usa letras, números y al menos una mayúscula (6 a 100 caracteres).")
                                            })
                                        Button(onClick = {
                                            try {
                                                datos.crearUsuario(actual, nuevoNombre, nuevaClave)
                                                usuarios = datos.leerUsuarios(); nuevoNombre = ""; nuevaClave = ""
                                                mensaje = "Usuario creado. Ya puede iniciar sesión."
                                            } catch (error: Exception) { mensaje = error.message ?: "No se pudo crear el usuario." }
                                        }) { Text("Crear usuario") }
                                        Text("Cuentas creadas:")
                                        usuarios.forEach { Text("${it.nombre} · ${if (it.administrador) "Administrador" else "Usuario"}") }
                                    }
                                    if (mensaje.isNotEmpty()) Text(mensaje, color = Color(0xFFFFB74D))
                                } else {
                                    Text("Auditoría del ${LocalDate.now()}", fontWeight = FontWeight.Bold)
                                    Text(if (actual.administrador) "Lecturas de todos los usuarios" else "Tus lecturas")
                                    val visibles = remember(registros, actual) { registros.filter { actual.administrador || it.usuario == actual.nombre }.asReversed() }
                                    Text("Total de lecturas: ${visibles.size}")
                                    if (visibles.isEmpty()) Text("Todavía no hay distancias registradas hoy.")
                                    visibles.drop(paginaAuditoria * 50).take(50).forEach {
                                            Text("${it.hora} · ${it.cm} cm · ${it.usuario}\n${it.dispositivo} · ${it.origen}")
                                        HorizontalDivider()
                                    }
                                    Text("Página ${paginaAuditoria + 1} · hasta 50 lecturas por página")
                                        Row {
                                            TextButton(enabled = paginaAuditoria > 0, onClick = { paginaAuditoria-- }) { Text("Anterior") }
                                            TextButton(enabled = (paginaAuditoria + 1) * 50 < visibles.size, onClick = { paginaAuditoria++ }) { Text("Siguiente") }
                                        }
                                        TextButton(enabled = !cargandoAuditoria, onClick = { cargarAuditoria() }) { Text(if (cargandoAuditoria) "Cargando…" else "Actualizar") }
                                }
                            }
                        }
                    }
                }
                if (usuario != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { pantalla = "Monitoreo"; mensaje = "" }) { Text("Monitoreo") }
                        TextButton(onClick = { pantalla = "Configuración"; mensaje = "" }) { Text("Configuración") }
                        TextButton(enabled = !cargandoAuditoria, onClick = { cargarAuditoria() }) { Text(if (cargandoAuditoria) "Cargando…" else "Auditoría") }
                    }
                }
            }
            listaDispositivos?.let { lista ->
                AlertDialog(onDismissRequest = { listaDispositivos = null }, title = { Text("Dispositivos emparejados") }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (lista.isEmpty()) Text("Empareja el HC-05 desde los ajustes Bluetooth del teléfono.")
                        lista.forEach { elegido ->
                            TextButton(onClick = { conectar(elegido) }) { Text("${elegido.name}\n${elegido.address}") }
                        }
                    }
                }, confirmButton = {
                    TextButton(onClick = { listaDispositivos = null; contexto.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Ajustes Bluetooth") }
                }, dismissButton = { TextButton(onClick = { listaDispositivos = null }) { Text("Cancelar") } })
            }
            if (avisoSistema.isNotEmpty()) {
                AlertDialog(onDismissRequest = { avisoSistema = "" }, title = { Text("Aviso del sistema") },
                    text = { Text(avisoSistema) }, confirmButton = { TextButton(onClick = { avisoSistema = "" }) { Text("Aceptar") } })
            }
        }
    }
}




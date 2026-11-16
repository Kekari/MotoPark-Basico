package cl.motopark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Usuario(val nombre: String, val administrador: Boolean, val sal: String, val resumen: String)
data class RegistroDistancia(val fecha: String, val hora: String, val usuario: String, val dispositivo: String, val cm: Int, val origen: String)

// Debe tener letras, números y al menos una mayúscula.
fun claveSegura(clave: String): Boolean {
    return clave.length in 6..100 && clave.any { it.isLetter() } &&
        clave.any { it.isDigit() } && clave.any { it.isUpperCase() }
}

// El primer usuario es administrador. Después solo el administrador puede crear cuentas.
fun prepararUsuario(usuarios: List<Usuario>, actual: Usuario?, nombre: String, clave: String): Usuario {
    val limpio = nombre.trim()
    require(limpio.length in 1..30) { "Escribe un usuario de 1 a 30 caracteres." }
    require(claveSegura(clave)) { "Contraseña insegura: usa entre 6 y 100 caracteres, con letras, números y al menos una mayúscula." }
    require(usuarios.none { it.nombre.equals(limpio, ignoreCase = true) }) { "Ese usuario ya existe." }
    if (usuarios.isNotEmpty()) {
        require(actual != null && usuarios.any { it == actual && it.administrador }) { "Solo el administrador puede crear usuarios." }
    }
    val sal = ByteArray(16)
    SecureRandom().nextBytes(sal)
    val textoSal = Base64.getEncoder().encodeToString(sal)
    return Usuario(limpio, usuarios.isEmpty(), textoSal, resumirClave(clave, textoSal))
}

// Esta función evita guardar las contraseñas como texto legible.
private fun resumirClave(clave: String, sal: String): String {
    val especificacion = PBEKeySpec(clave.toCharArray(), Base64.getDecoder().decode(sal), 120000, 256)
    try {
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(especificacion).encoded
        return Base64.getEncoder().encodeToString(bytes)
    } finally { especificacion.clearPassword() }
}

fun comprobarClave(usuario: Usuario, clave: String): Boolean = MessageDigest.isEqual(
    Base64.getDecoder().decode(usuario.resumen), Base64.getDecoder().decode(resumirClave(clave, usuario.sal)))

class DatosLocales(private val contexto: Context) {
    private val preferencias = contexto.getSharedPreferences("cuentas_motopark", Context.MODE_PRIVATE)

    fun leerUsuarios(): List<Usuario> {
        val lista = JSONArray(preferencias.getString("usuarios", "[]"))
        val resultado = mutableListOf<Usuario>()
        for (i in 0 until lista.length()) {
            val dato = lista.getJSONObject(i)
            resultado.add(Usuario(dato.getString("nombre"), dato.getBoolean("administrador"), dato.getString("sal"), dato.getString("resumen")))
        }
        return resultado
    }

    fun crearUsuario(actual: Usuario?, nombre: String, clave: String): Usuario {
        val usuarios = leerUsuarios()
        val nuevo = prepararUsuario(usuarios, actual, nombre, clave)
        val lista = JSONArray()
        for (usuario in usuarios + nuevo) {
            lista.put(JSONObject().put("nombre", usuario.nombre).put("administrador", usuario.administrador)
                .put("sal", usuario.sal).put("resumen", usuario.resumen))
        }
        check(preferencias.edit().putString("usuarios", lista.toString()).commit()) { "No se pudo guardar la cuenta." }
        return nuevo
    }

    fun iniciarSesion(nombre: String, clave: String): Usuario {
        val usuario = leerUsuarios().find { it.nombre.equals(nombre.trim(), ignoreCase = true) }
        require(usuario != null && clave.length <= 100 && comprobarClave(usuario, clave)) { "Usuario o contraseña incorrectos." }
        return usuario
    }

    fun guardarDistancia(usuario: String, dispositivo: String, cm: Int) {
        require(cm in 2..400)
        val fecha = LocalDate.now().toString()
        val hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val dato = JSONObject().put("fecha", fecha).put("hora", hora).put("usuario", usuario)
            .put("dispositivo", dispositivo).put("cm", cm).put("origen", "Sensor Bluetooth")
        // Un archivo por día, dentro del almacenamiento privado de la aplicación.
        contexto.openFileOutput("distancias_$fecha.jsonl", Context.MODE_APPEND).bufferedWriter().use {
            it.write(dato.toString())
            it.newLine()
        }
    }

    fun leerHoy(): List<RegistroDistancia> {
        val fecha = LocalDate.now().toString()
        val archivo = java.io.File(contexto.filesDir, "distancias_$fecha.jsonl")
        if (!archivo.exists()) return emptyList()
        val resultado = mutableListOf<RegistroDistancia>()
        archivo.forEachLine { linea ->
            if (linea.isNotBlank()) {
                val dato = JSONObject(linea)
                resultado.add(RegistroDistancia(dato.getString("fecha"), dato.getString("hora"),
                    dato.getString("usuario"), dato.getString("dispositivo"), dato.getInt("cm"), dato.optString("origen", "Versión anterior")))
            }
        }
        return resultado
    }
}

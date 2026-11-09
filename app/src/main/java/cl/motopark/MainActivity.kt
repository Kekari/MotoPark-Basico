package cl.motopark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MotoPark() }
    }
}

@Composable
fun MotoPark() {
    // variables que guardan lo que el usuario cambia mientras usa la pantalla.
    var pantalla by remember { mutableStateOf("Monitoreo") }
    var distancia by remember { mutableStateOf(28) }
    var sistemaActivo by remember { mutableStateOf(true) }
    var buzzerActivo by remember { mutableStateOf(true) }
    var sonidoActivo by remember { mutableStateOf(false) }

    // Elegimos el aviso usando condiciones sencillas.
    var aviso = "Distancia segura"
    var colorAviso = Color.Green
    if (distancia <= 15) {
        aviso = "Peligro"
        colorAviso = Color.Red
    } else if (distancia <= 40) {
        aviso = "Precaución"
        colorAviso = Color(0xFFFF9800)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF21D879))) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("MotoPark", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Datos de prueba", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))

                // El if decide cuál de las dos pantallas mostrar.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            if (pantalla == "Monitoreo") {
                                Text("Distancia detectada", fontWeight = FontWeight.Bold)
                                Text(
                                    if (sistemaActivo) "$distancia cm" else "— cm",
                                    fontSize = 42.sp, fontWeight = FontWeight.Bold,
                                )
                                Text(if (sistemaActivo) aviso else "Sistema desactivado",
                                    color = if (sistemaActivo) colorAviso else Color.Gray)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.weight(1f).height(10.dp).background(Color(0xFFD8464B)))
                                    Box(Modifier.weight(1f).height(10.dp).background(Color(0xFFDFA923)))
                                    Box(Modifier.weight(1f).height(10.dp).background(Color.LightGray))
                                }
                                Text(if (sistemaActivo) "Sistema activo" else "Sistema inactivo")

                                // ! invierte el valor: de true a false o de false a true.
                                Button(onClick = { sistemaActivo = !sistemaActivo }) {
                                    Text(if (sistemaActivo) "Desactivar sistema" else "Activar sistema")
                                }
                                Text("Cambiar distancia de prueba", fontSize = 14.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { distancia = 10 }) { Text("10 cm") }
                                    TextButton(onClick = { distancia = 28 }) { Text("28 cm") }
                                    TextButton(onClick = { distancia = 80 }) { Text("80 cm") }
                                }
                            } else {
                                Text("Configuración del sistema", fontWeight = FontWeight.Bold)
                                Text("Bluetooth HC-05: simulado", color = Color(0xFF21D879))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Buzzer Arduino", Modifier.weight(1f))
                                    Switch(checked = buzzerActivo, onCheckedChange = { buzzerActivo = it })
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Sonido del teléfono", Modifier.weight(1f))
                                    Switch(checked = sonidoActivo, onCheckedChange = { sonidoActivo = it })
                                }
                                Text("Los switches son de prueba: no activan hardware ni sonido real.",
                                    color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // Estos botones cambian la variable pantalla.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { pantalla = "Monitoreo" }) { Text("Monitoreo") }
                    TextButton(onClick = { pantalla = "Configuración" }) { Text("Configuración") }
                }
            }
        }
    }
}

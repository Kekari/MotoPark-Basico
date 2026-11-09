# MotoPark Básico

Abre esta carpeta en Android Studio y ejecuta app. El proyecto ya está configurado.

## El archivo que debes estudiar

Toda la lógica y la interfaz están en **app/src/main/java/cl/motopark/MainActivity.kt**. No necesitas estudiar los archivos Gradle por ahora: sirven para configurar y compilar el proyecto.

En MainActivity.kt verás solo estas ideas:

1. **Variables**: distancia, sistemaActivo, buzzerActivo, sonidoActivo y pantalla.
2. **Condiciones if y else**: elegir el aviso y la pantalla que se muestra.
3. **Text**: mostrar texto.
4. **Button y TextButton**: cambiar una variable cuando pulsas un botón.
5. **Switch**: guardar si un interruptor está encendido o apagado.
6. **Column y Row**: ordenar los elementos vertical u horizontalmente.

`remember` permite que los valores se conserven mientras usas la pantalla. Al cambiar una variable, Compose actualiza la interfaz. `!sistemaActivo` invierte su valor. `it` en los switches es el nuevo valor que entrega el interruptor.

## Práctica

Primero busca `mutableStateOf(28)` y cambia 28 por 10. Ejecuta la app y observa cómo cambia el aviso. Después busca `distancia <= 15`: esa condición decide cuándo aparece Peligro.

La app utiliza datos de prueba. Los switches solo cambian visualmente; no hay sonido real ni conexión Bluetooth. Los permisos Bluetooth están declarados en AndroidManifest.xml para una futura conexión; esta versión no los solicita porque no accede al hardware. Al agregar Bluetooth real en Android 12 o superior habrá que solicitar CONNECT durante la ejecución.

Esta versión no incluye seguridad, ViewModel, comunicación, almacenamiento ni clases adicionales. Las versiones anteriores se conservaron aparte. Los valores se reinician si Android recrea la pantalla, por ejemplo al girar el teléfono; por ahora priorizamos entender el código básico.

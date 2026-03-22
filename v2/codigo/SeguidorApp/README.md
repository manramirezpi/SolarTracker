# SeguidorApp — Android v2.0

Aplicación móvil para monitoreo en tiempo real y control remoto del sistema 
SolarTracker v2.0. Se comunica con el firmware del ESP32 mediante MQTT.

---

## Funcionalidades

| Función | Descripción |
|---|---|
| Dashboard energético | Medidores de voltaje, corriente y potencia instantánea a 5 Hz |
| Control manual | Sliders de azimut y elevación para posicionamiento directo |
| Modo simulación | Ajuste de velocidad y prueba de trayectorias solares |
| Comparación de paneles | Energía acumulada (mWh) seguidor vs. estático — gráficas en v2.1 |
| Geolocalización | Visualización de coordenadas GPS y tiempo UTC activo |
| Exportación de datos | Descarga y comparte el batch de calibración en formato CSV |

---

## Arquitectura

La app sigue el patrón MVC con separación estricta entre comunicaciones,
procesamiento de datos y capa de presentación.
```
SeguidorApp/
├── comunicaciones/
│   └── ClientePubSubMQTT.java     ← cliente MQTT asíncrono con cola concurrente
├── datos/
│   ├── AlmacenDatosRAM.java       ← estado global compartido entre capas (volatile)
│   └── ProcesadorTelemetria.java  ← parsing de telemetría optimizado para 5 Hz
├── utilidades/
│   ├── GeneradorUI.java           ← componentes visuales aislados de la lógica
│   ├── GaugeSimple.java           ← medidor analógico con renderizado en Canvas
│   ├── CircularSlider.java        ← control circular para factor de simulación
│   └── DialogoSalir.java          ← confirmación de salida
└── ActividadSeguidor.java         ← coordinación general y ciclo de vida (Controlador MVC)
```

### Decisiones de diseño relevantes

**Parsing sin JSON**: el procesador de telemetría lee los mensajes MQTT como 
texto plano en lugar de deserializar JSON. Esto evita la presión sobre el 
Garbage Collector de Java a 5 Hz de actualización, reduciendo los saltos 
visuales en los medidores.

**Cola concurrente en MQTT**: el cliente publica y recibe mensajes en un hilo 
separado para no bloquear el UI Thread. Los datos llegan a la interfaz mediante 
un handler que garantiza actualizaciones seguras desde el hilo principal.

**Bloqueo post-intervención manual**: tras un comando manual del usuario, la 
app suspende las actualizaciones automáticas por 3 segundos para evitar que 
la telemetría entrante sobreescriba visualmente el ajuste antes de que el 
hardware responda.

**Buffer circular para promedios móviles**: los medidores usan un buffer 
circular para suavizar lecturas ruidosas, evitando oscilaciones bruscas en 
la aguja sin introducir latencia perceptible.

**GeneradorUI desacoplado**: todos los componentes visuales están contenidos 
en una clase separada, lo que permite modificar la interfaz sin tocar la 
lógica de comunicaciones ni el procesamiento de datos.

---

## Integración MQTT

### Tópicos de suscripción

| Tópico | Contenido | Frecuencia |
|---|---|---|
| `solar/status/fast` | Ángulos de azimut y elevación, potencia de ambos paneles | 5 Hz |
| `solar/status/slow` | Coordenadas GPS, tiempo UTC, estado del sistema | ~1 Hz |

### Tópicos de publicación

| Tópico | Contenido |
|---|---|
| `solar/sub` | Coordenadas manuales, fecha estática, comandos de modo |

---

## Compilación

**Requisitos:** Android Studio Koala o superior, JDK 17, Android SDK API 24+.

```bash
cd v2/codigo/SeguidorApp

# 1. Configurar credenciales del broker MQTT (solo la primera vez)
cp app/src/main/java/com/solartracker/Configuracion.example.java \
   app/src/main/java/com/solartracker/Configuracion.java
# Editar Configuracion.java con la IP/dominio del broker y las credenciales

# 2. Compilar y generar APK de debug
./gradlew assembleDebug

# El APK queda en: app/build/outputs/apk/debug/app-debug.apk
```

También se puede abrir el proyecto directamente en Android Studio y ejecutar con `Run ▶` sobre un dispositivo o emulador con Android 7.0+.

---

## Requisitos

- Android Studio Koala o superior
- SDK mínimo: Android 7.0 (API 24)
- Dependencias: Paho MQTT Client, MPAndroidChart

---

## Capturas de pantalla

*(Las capturas de pantalla se agregarán junto con los datos de campo en v2.1)*

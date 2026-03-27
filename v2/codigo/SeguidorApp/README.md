# SeguidorApp — Android v2.0

Aplicación móvil para monitoreo en tiempo real y control remoto del sistema SolarTracker v2.0. Se comunica con el firmware del ESP32 mediante MQTT, ofreciendo visualización de telemetría a 4 Hz, control remoto de posición y adquisición de datos para calibración.

---

## Capturas de pantalla

*(Las capturas de pantalla se agregarán junto con los datos de campo en v2.1)*

---

## Funcionalidades

| Función | Descripción |
|---|---|
| Tabla de telemetría | Visualización compacta de potencia instantánea, promedio y energía acumulada para 2 paneles con actualizaciones a 4 Hz |
| Medidores analógicos | Gauges para visualización de ángulos solares (azimut/elevación) y posición de servos con suavizado de lecturas |
| Control remoto | Sliders para ajuste manual de azimut (0° a 180°) y elevación (0° a 180°) con suspensión temporal de actualizaciones |
| Cálculo de ganancia | Comparación porcentual en tiempo real entre energía acumulada del panel móvil vs estático |
| Control de coordenadas | Ajuste manual de latitud/longitud para pruebas sin mover el hardware físico |
| Simulación de tiempo | Factor de velocidad 1x a 1440x — permite simular 1 día completo en 1 minuto |
| Adquisición de datos | Datalogger asíncrono — genera batch de 150 muestras sincronizadas exportable como CSV |
| Estado GPS | Indicador visual de señal GPS (ESTABLE/BUSCANDO) |

---

## Arquitectura

La app sigue el patrón **MVC (Modelo-Vista-Controlador)** con separación estricta entre comunicaciones, procesamiento de datos y capa de presentación.

```
SeguidorApp/
├── comunicaciones/
│   └── ClientePubSubMQTT.java     ← cliente MQTT asíncrono con cola concurrente
├── datos/
│   ├── AlmacenDatosRAM.java       ← estado global compartido entre capas (volatile)
│   └── ProcesadorTelemetria.java  ← parsing de telemetría optimizado para 4 Hz
├── utilidades/
│   ├── GeneradorUI.java           ← componentes visuales aislados de la lógica
│   ├── GaugeSimple.java           ← medidor analógico con renderizado en Canvas
│   ├── CircularSlider.java        ← control circular para factor de simulación
│   ├── Boton.java                 ← botones personalizados con retroalimentación
│   └── DialogoSalir.java          ← confirmación de salida
└── ActividadSeguidor.java         ← coordinación general y ciclo de vida
```

### Decisiones de diseño relevantes

**Parsing sin JSON para canal rápido (4 Hz):** el procesador de telemetría extrae valores del mensaje MQTT como texto plano mediante búsqueda de subcadenas, en lugar de deserializar el objeto JSON completo. Esto evita la presión sobre el Garbage Collector de Java a 4 Hz de actualización, reduciendo los saltos visuales en la tabla y mejorando la fluidez.

**Cola concurrente en MQTT:** el cliente publica y recibe mensajes en un hilo separado para no bloquear el UI Thread. Los datos llegan a la interfaz mediante un `Handler` que garantiza actualizaciones seguras desde el hilo principal de Android.

**Bloqueo post-intervención manual:** tras un comando manual del usuario (cambio de ángulo, fecha o coordenadas), la app suspende las actualizaciones automáticas por 3 segundos (`MANUAL_LOCKOUT_MS`) para evitar que la telemetría entrante sobreescriba visualmente el ajuste antes de que el hardware responda. Este mecanismo elimina los rebotes visuales y mejora la percepción de control directo.

**Buffer circular para promedios móviles:** los medidores analógicos (gauges) usan un buffer circular de las últimas 10 lecturas para suavizar valores ruidosos, evitando oscilaciones bruscas en la aguja sin introducir latencia perceptible.

**GeneradorUI desacoplado:** todos los componentes visuales están contenidos en una clase separada (`GeneradorUI.java`), lo que permite modificar la interfaz sin tocar la lógica de comunicaciones ni el procesamiento de datos. Facilita el mantenimiento y testing.

**Variables volatile en AlmacenDatosRAM:** todos los campos del almacén central son `volatile` para garantizar visibilidad entre el hilo MQTT y el UI Thread, evitando lecturas cacheadas por el compilador JIT de Android.

---

## Características principales

- **Tabla de telemetría con actualización a 4 Hz** — Visualización compacta con 6 parámetros eléctricos (potencia instantánea, promedio móvil y energía acumulada) para ambos paneles, sin saltos visuales gracias al parsing directo sin JSON.
- **Cálculo de ganancia energética en tiempo real** — Comparación porcentual automática entre la energía acumulada del panel móvil y el estático, actualizada continuamente conforme avanzan las mediciones diarias.
- **Medidores analógicos (Gauges):**
  - Visualización de ángulos solares calculados (azimut/elevación) y posición real de los servos.
  - Representación tipo instrumentación analógica con aguja y escala graduada.
  - Renderizado directo en Canvas de Android con buffer circular de 10 muestras para suavizado.
  - Elimina saltos bruscos en lecturas ruidosas sin introducir lag perceptible.
- **Control remoto de posición:**
  - Sliders horizontales para ajuste manual de azimut (0° a 180°) y elevación (0° a 180°).
  - Suspensión temporal de actualizaciones automáticas por 3 segundos tras un comando para evitar rebotes visuales antes de que el hardware responda.
  - Sincronización automática de sliders con la posición real del hardware tras el periodo de gracia.
- **Control de coordenadas y tiempo:**
  - Ajuste manual de latitud (-90° a +90°) y longitud (-180° a +180°) para pruebas sin mover el hardware.
  - Factor de velocidad de simulación (1x a 1440x) mediante slider circular — permite simular 1 día completo en 1 minuto (útil para validación de trayectorias).
  - Botón "VOLVER A GPS" para restaurar el modo automático con coordenadas reales del satélite.
- **Adquisición de datos para calibración:**
  - Botón "DESCARGAR" que solicita al ESP32 un batch sincronizado de 150 muestras delta de potencia.
  - Exportación a archivo CSV con timestamp mediante botón "COMPARTIR".
  - Permite análisis externo de correlación entre paneles para determinar coeficientes de normalización cuadrática.
- **Indicador de estado GPS:**
  - Visualización en tiempo real del estado de la señal GPS (SEÑAL ESTABLE / BUSCANDO...).
  - Código de color: verde para señal válida, amarillo durante búsqueda.

---

## Integración MQTT

### Tópicos de suscripción

| Tópico | Contenido | Frecuencia |
|---|---|---|
| `solar/status/fast` | Ángulos de azimut y elevación, potencia instantánea de ambos paneles | ~4 Hz |
| `solar/status/slow` | Coordenadas GPS, tiempo UTC, promedios móviles, energía acumulada, estado del sistema | ~1 Hz |
| `solar/data/batch` | Archivo batch (150 puntos) desencadenado asíncronamente por comando | Evento |

### Tópicos de publicación

| Tópico | Contenido |
|---|---|
| `solar/sub` | Comandos de control: ángulos manuales, fecha estática, coordenadas, factor de simulación, solicitud de batch |

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
- JDK 17
- SDK mínimo: Android 7.0 (API 24)
- SDK objetivo: Android 14 (API 34)
- Dependencias:
  - Paho MQTT Client 1.2.5

---

## Licencia

MIT License — ver [LICENSE](../../../LICENSE)

# SeguidorApp — Android v2.0

Aplicación móvil de instrumentación industrial para monitoreo en tiempo real y control remoto del sistema SolarTracker v2.0. Se comunica con el firmware del ESP32 mediante MQTT, ofreciendo visualización de telemetría a 4 Hz, control híbrido AUTO/MAN, diagnóstico de salud y adquisición de datos para calibración.

---

## Capturas de pantalla

*(Las capturas de pantalla se agregarán junto con los datos de campo en v2.1)*

---

## Funcionalidades

| Función | Descripción |
|---|---|
| Dashboard Industrial | Tabla compacta de parámetros (Voltaje, Corriente, Potencia) para 2 paneles con actualizaciones fluidas a 4 Hz |
| Medidores analógicos (Gauges) | Representación visual tipo instrumentación de aviación con buffer circular para suavizado de lecturas ruidosas |
| Control manual remoto | Control híbrido AUTO/MAN para orientar físicamente (azimut/elevación) el seguidor mediante sliders |
| Monitoreo de Salud LWT | Sistema de semáforo global (verde/amarillo/rojo) y panel detallado (bottom sheet) de salud: Conexión, I2C INA3221, GPS, Memoria NVS |
| Adquisición de datos | Datalogger asíncrono activado por hardware — genera batch de 150 muestras sincronizadas exportable como CSV/.txt |
| Comparación Energética | Visualización de energía acumulada diaria (mWh) con relación 1:1. Preparado para inyección de corrección cuadrática |
| Geolocalización y tiempo | Datos de Lat/Lon en tiempo real y ajustes de fecha manual y velocidad de simulación (1x a 3600x) |

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

**Parsing sin JSON:** el procesador de telemetría lee los mensajes MQTT como texto plano en lugar de deserializar JSON. Esto evita la presión sobre el Garbage Collector de Java a 4 Hz de actualización, reduciendo los saltos visuales en la tabla y mejorando la fluidez.

**Cola concurrente en MQTT:** el cliente publica y recibe mensajes en un hilo separado para no bloquear el UI Thread. Los datos llegan a la interfaz mediante un `Handler` que garantiza actualizaciones seguras desde el hilo principal de Android.

**Bloqueo post-intervención manual:** tras un comando manual del usuario (cambio de ángulo, fecha o coordenadas), la app suspende las actualizaciones automáticas por 3 segundos para evitar que la telemetría entrante sobreescriba visualmente el ajuste antes de que el hardware responda.

**Buffer circular para promedios móviles:** los medidores analógicos (gauges) usan un buffer circular de las últimas 10 lecturas para suavizar valores ruidosos, evitando oscilaciones bruscas en la aguja sin introducir latencia perceptible.

**GeneradorUI desacoplado:** todos los componentes visuales están contenidos en una clase separada (`GeneradorUI.java`), lo que permite modificar la interfaz sin tocar la lógica de comunicaciones ni el procesamiento de datos. Facilita el mantenimiento y testing.

**Gestión de Salud y LWT:** la aplicación integra un sistema integral de monitoreo mediante Last Will & Testament (LWT) independiente para App y ESP32. Un panel lateral analiza los códigos JSON de estado y pinta un semáforo global para indicar la conexión, fallos en periféricos internos (GPS, I2C INA3221) o problemas de persistencia NVS.

**Variables volatile en AlmacenDatosRAM:** todos los campos del almacén central son `volatile` para garantizar visibilidad entre el hilo MQTT y el UI Thread, evitando lecturas cacheadas por el compilador JIT.

---

## Características principales

- **Dashboard Industrial con actualización a 4 Hz** — Tabla compacta con 6 parámetros eléctricos (voltaje, corriente, potencia) para ambos paneles, sin saltos visuales gracias al parsing directo sin JSON.
- **Monitoreo de Salud Inteligente mediante LWT:**
  - Sistema de semáforo visual (verde/amarillo/rojo) que refleja el estado global del sistema.
  - Panel detallado desplegable (bottom sheet) con desglose de salud: conexión MQTT, integridad de memoria NVS, periférico GPS, periférico I2C INA3221.
  - Análisis automático de códigos JSON publicados por el firmware en el LWT.
- **Control híbrido AUTO/MAN:**
  - **Modo AUTO:** el firmware sigue automáticamente al sol con coordenadas GPS en tiempo real.
  - **Modo MAN:** control remoto directo de azimut (0° a 180°) y elevación (0° a 180°) mediante sliders horizontales.
  - Suspensión temporal de actualizaciones automáticas por 3 segundos tras un comando para evitar rebotes visuales.
- **Adquisición de datos para calibración:**
  - Botón dedicado que solicita al ESP32 un batch sincronizado de 150 muestras delta.
  - Exportación a archivo .txt con timestamp en formato legible para análisis de correlación entre paneles.
  - Almacenamiento en carpeta de Descargas del dispositivo Android.
- **Visualización de energía acumulada:**
  - Gráfico comparativo de energía diaria (mWh) para panel seguidor y estático.
  - Permite evaluar visualmente la ganancia del seguimiento a lo largo del día.
- **Medidores analógicos (Gauges):**
  - Representación tipo instrumentación de aviación con aguja analógica y escala graduada.
  - Renderizado directo en Canvas de Android con buffer circular de 10 muestras para suavizado.
  - Elimina saltos bruscos en lecturas ruidosas sin introducir lag perceptible.
- **Geolocalización y control de tiempo:**
  - Visualización de coordenadas GPS (latitud/longitud) en tiempo real.
  - Ajuste manual de fecha (día/mes/año) para pruebas de trayectorias solares en fechas específicas.
  - Control de factor de simulación (1x a 3600x) mediante slider circular para acelerar el tiempo y validar algoritmos.
- **Persistencia de configuración:**
  - La app guarda automáticamente la IP del broker MQTT y el estado del modo AUTO/MAN.
  - Restablece la configuración al reiniciar la app sin intervención del usuario.

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

### Last Will & Testament (LWT)

La app publica su propio **mensaje LWT** al desconectarse:
```json
{"app": 0}
```

Y escucha el **LWT del ESP32** para diagnóstico de salud:
```json
{
  "conn": 0,
  "nvs": 1,
  "gps": 1,
  "i2c": 1
}
```

Este formato permite al panel de salud identificar qué componente falló específicamente.

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
  - MPAndroidChart 3.1.0

---

## Licencia

MIT License — ver [LICENSE](../../../LICENSE)

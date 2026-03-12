# SolarTracker v2.0 - Firmware ESP32

Este directorio contiene el código fuente del firmware para el microcontrolador ESP32, desarrollado sobre el framework **ESP-IDF v5.5.3**. El sistema está diseñado para operar como un nodo IoT de alta disponibilidad para el seguimiento solar astronómico.

## Especificaciones Técnicas
- **Microcontrolador:** ESP32 dual-core (240 MHz).
- **Framework:** ESP-IDF v5.5.3.
- **Sistema Operativo:** FreeRTOS (Arquitectura multitarea).
- **Consumo de Memoria:** Optimizado para evitar fragmentación del Heap (uso intensivo de buffers estáticos).

## Pinout (Asignación de Pines)
| Función | Pin (GPIO) | Descripción |
| :--- | :--- | :--- |
| **Servo Azimut** | 19 | Salida PWM (LEDC Channel 0) |
| **Servo Elevación** | 18 | Salida PWM (LEDC Channel 1) |
| **GPS RX** | 17 | Recepción UART2 (9600 baud) |
| **GPS TX** | 16 | Transmisión UART2 (No utilizado) |
| **I2C SDA** | 21 | Bus de datos para sensor INA3221 |
| **I2C SCL** | 22 | Bus de reloj para sensor INA3221 |

## Arquitectura del Firmware
El código está organizado en 5 módulos críticos que se ejecutan de forma concurrente:

1.  **Capa de Conectividad:** 
    - Gestión WiFi con triple redundancia (SSID principal + 2 de respaldo).
    - Reconexión autónoma mediante **backoff exponencial** (2s a 64s).
    - Comunicación MQTT bidireccional.
2.  **Motor de Procesamiento GPS:** 
    - Parseo de tramas NMEA-0183 ($GPRMC) con validación por **Checksum XOR**.
    - Implementación de **Inercia GPS**: mantiene el seguimiento con las últimas coordenadas válidas ante pérdida de señal.
3.  **Medición y Telemetría Multinivel:** 
    - Lectura del sensor INA3221 a 10 Hz.
    - **Canal Rápido (4Hz - 250ms):** Envía ángulos y potencia instantánea a `solar/status/fast`.
    - **Canal Lento (1Hz):** Envía datos de GPS, diagnóstico y acumulados a `solar/status/slow`.
4.  **Capa de Actuación (Silky Motion):** 
    - Control de servos con resolución de 16 bits.
    - Rampas de aceleración (15°/s) para proteger la mecánica.
    - Reactividad instantánea que permite interrumpir trayectorias ante nuevos comandos.
5.  **Guardián del Sistema (TWDT):** 
    - Task Watchdog Timer suscrito a todas las tareas críticas (Principal, GPS, INA).

## Lógica de Resiliencia
El sistema implementa tres niveles de protección para evitar el reinicio físico del procesador:
- **Nivel 1:** Reconexión de red no-bloqueante y rotación de SSIDs.
- **Nivel 2:** Recuperación en caliente de periféricos (Autocuración del bus I2C y búsqueda automática del sensor).
- **Nivel 3:** Reinicio suave via TWDT (10s) solo en caso de bloqueo total de tareas.

## Compilación y Carga
Para compilar este proyecto, debe tener instalado el entorno ESP-IDF:

```bash
# Navegar al directorio del proyecto
cd v2/codigo/esp32

# Configurar el target (si es la primera vez)
idf.py set-target esp32

# Compilar el proyecto
idf.py build

# Cargar el firmware y abrir el monitor serial
idf.py -p /dev/ttyUSB0 flash monitor
```

---
**Nota de Calibración:** El firmware incluye un escalamiento matemático `(* 1.238)` configurable para homologar la lectura de paneles solares dispares y poder realizar comparativas de eficiencia objetivas.

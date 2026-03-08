# SOLARTRACKER v2.0

## Descripción general
Esta versión 2.0 representa la evolución del sistema original basado en STM32 hacia una plataforma **IoT de alta disponibilidad** fundamentada en el **ESP32**. El enfoque ha migrado de un control local y visualización física, a un entorno de monitoreo científico remoto, con robustez ante fallos de red y una cinemática de precisión optimizada para la comparación de eficiencia energética.

Lo que en la v1.0 eran perspectivas de evolución, en la v2.0 es una realidad funcional:

| Característica | Versión 1.0 (STM32) | Versión 2.0 (ESP32) |
| :--- | :--- | :--- |
| **Procesador** | STM32F4 (100 MHz) | ESP32 dual-core (240 MHz) |
| **Interfaz** | Local (LCD 20x4 + consola serial) | Ubicua (app móvil + dashboard MQTT) |
| **Control** | Comandos por cable (UART) | Mando inalámbrico bidireccional |
| **Monitoreo** | Teórico (cálculo solar) | Científico (INA3221 — mW reales) |
| **Resiliencia** | Protección básica | Triple WiFi redundante + fail-over |
| **Cinemática** | Movimiento directo | Silky Motion (rampas + histéresis 0.4°) |
| **Análisis** | No disponible | Media móvil 24 h (solo horas de sol) |

## Características principales
- **Triple redundancia WiFi:** Gestión automática entre red principal y dos de respaldo, con fail-over transparente ante fallo de conectividad.
- **Backoff exponencial:** Estrategia de reintentos con intervalos crecientes (2 s a 64 s) para proteger la salud de la red.
- **Watchdog de sistema coordinado:** Timeouts de red coordinados (4 s) para evitar bloqueos del sistema ante fallos del broker MQTT, dimensionados por debajo del timeout del TWDT (10 s).
- **Auto-sanación I2C:** Detección y reconfiguración en caliente del sensor de energía INA3221 sin reinicio del sistema.
- **Inercia GPS:** Ante la pérdida de satélites, el sistema mantiene el seguimiento usando el último fix válido recuperado desde NVS (memoria no volátil).
- **Sistema Silky Motion:** Rampas de aceleración limitadas a 15°/s y zona muerta de 0.4° para movimiento suave, silencioso y sin estrés mecánico.
- **Análisis de eficiencia comparativa:** Media móvil de 24 horas con exclusión nocturna, para comparación objetiva entre panel móvil y panel estático sin sesgo por lecturas de 0 W.
- **Control IoT bidireccional:** Protocolo MQTT (`solar/sub`) con comandos JSON para ajuste remoto de posición, coordenadas y velocidad de simulación.
- **Persistencia NVS inteligente:** Almacenamiento de la última posición GPS válida con filtro antisgaste (~22 km de umbral) para proteger la vida útil de la memoria flash.

## Arquitectura del sistema
El firmware está estructurado sobre ESP-IDF v5.5.3 con arquitectura multitarea (FreeRTOS), destacando los siguientes módulos:

1.  **Capa de conectividad:** Gestión WiFi con reconexión automática por backoff exponencial y rotación entre tres SSIDs de respaldo. Comunicación MQTT bidireccional con timeout de red coordinado al TWDT.
2.  **Motor de procesamiento GPS:** Parseo de tramas NMEA ($GPRMC) con mecanismo de inercia: ante pérdida de señal, el sistema opera con el último fix válido rescatado desde NVS.
3.  **Sistema de medición energética:** Lectura del sensor INA3221 vía I2C con capacidad de auto-sanación ante fallos en el bus. Acumulación en buffer circular para cálculo de media móvil de 24 horas.
4.  **Capa de actuación (Silky Motion):** Generación de señales PWM de 16 bits a 50 Hz con lógica de rampas de velocidad y zona muerta configurable para eliminar jitter y proteger los actuadores.
5.  **Guardián del sistema (TWDT):** Task Watchdog Timer configurado a 10 s, suscrito a todas las tareas críticas, con alimentación periódica que garantiza la detección y recuperación ante bloqueos.

## Especificaciones técnicas (v2.0)
- **Microcontrolador:** ESP32 dual-core (240 MHz).
- **Framework:** ESP-IDF v5.5.3.
- **Periféricos:**
    - UART2 (GPS RX — GPIO 17).
    - Bus I2C (INA3221 — GPIO 21/22).
    - LEDC PWM: servo azimut (GPIO 19), servo elevación (GPIO 18).
- **Conectividad:** WiFi 802.11 b/g/n con triple SSID de respaldo.
- **Almacenamiento:** NVS en flash interna (filtro antisgaste activo).

## Especificaciones de seguimiento (v2.0)
El sistema mantiene la cobertura hemisférica completa de la v1.0, añadiendo suavidad cinemática y análisis energético:

*   **Rango de elevación:** $0^\circ$ a $90^\circ$.
    *   El algoritmo detecta elevaciones negativas (noche), deteniendo el movimiento en esta componente y excluyendo la medición del promedio diario de potencia.
*   **Rango de azimut:** $0^\circ$ a $360^\circ$.
    *   Cobertura circular completa mediante la lógica de inversión *back-flip* heredada de la v1.0.
*   **Resolución de cálculo:** Doble precisión (`double`) para coordenadas astronómicas.
*   **Velocidad máxima de rampa:** 15°/s para protección mecánica de los actuadores.
*   **Zona muerta (histéresis):** 0.4° para eliminar oscilaciones y jitter en posición de reposo.
*   **Frecuencia PWM:** 50 Hz con resolución de 16 bits.

## Lógica de resiliencia y conectividad
Para garantizar operación continua 24/7 ante fallos parciales o totales de conectividad, el firmware implementa una estrategia de alta disponibilidad en tres niveles:

*   **Nivel 1 — Reconexión automática:** Ante la pérdida de la red principal, el sistema intenta reconectar con backoff exponencial (2 s, 4 s, 8 s... hasta 64 s). Al agotar los reintentos, rota automáticamente al SSID de respaldo 1 y luego al de respaldo 2.
*   **Nivel 2 — Operación degradada:** Si la conectividad falla por completo, el sistema continúa operando en modo autónomo: calcula la posición solar con los datos GPS o NVS disponibles y mueve los servos con normalidad. La telemetría se reanuda en cuanto se restaura la conexión.
*   **Nivel 3 — Guardián de hardware:** El TWDT supervisa todas las tareas críticas con un timeout de 10 s. Si alguna tarea se bloquea (por ejemplo, ante un fallo del broker MQTT), el sistema ejecuta un reinicio controlado, garantizando la recuperación automática sin intervención humana.

## Estado del proyecto
Actualmente, esta versión 2.0 se encuentra en estado **estable y funcional**, validada para operación continua 24/7 en condiciones de campo. Ha cumplido con los objetivos de diseño principales:
*   **Conectividad robusta:** Triple redundancia WiFi con reconexión autónoma y fail-over transparente entre SSIDs.
*   **Operación autónoma:** Inercia GPS y persistencia NVS garantizan el seguimiento incluso ante pérdida total de señal satelital.
*   **Cinemática silenciosa:** Sistema Silky Motion elimina el estrés mecánico en los actuadores y suprime el jitter en posición de reposo.
*   **Monitoreo científico:** Medición real de potencia (mW) con promediado inteligente para análisis comparativo de eficiencia energética.

## Perspectivas de evolución
El mapa de ruta para la siguiente iteración (v3.0) se centra en la autonomía total y la movilidad del sistema:

*   **Actualización inalámbrica (OTA):** Implementación de carga de firmware vía WiFi para realizar mejoras y correcciones sin necesidad de acceso físico al dispositivo, facilitando el mantenimiento en instalaciones remotas.
*   **Sistema de referencia dinámico (fusión de sensores IMU):** Integración de acelerómetro, giroscopio y magnetómetro para operar en condiciones dinámicas (vehículos o embarcaciones), compensando en tiempo real la orientación de la base para mantener el apuntado solar independientemente del movimiento del soporte.
*   **Algoritmos de auto-calibración por barrido de potencia:** Uso del INA3221 para realizar escaneos de posición alrededor del óptimo calculado. Esta técnica de búsqueda de máximo corregirá desviaciones sistémicas como base no nivelada o errores de alineación con el norte geográfico, garantizando la máxima eficiencia real.

---
**Nota:** *Este software se proporciona "tal cual" bajo los términos de la licencia Apache 2.0 de Espressif Systems y está diseñado para aplicaciones de energía renovable y educación técnica.*

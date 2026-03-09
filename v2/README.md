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
| **Cinemática** | Movimiento directo | Silky Motion (rampas + reactividad manual instantánea) |
| **Telemetría** | Unificada (1 Hz) | **Multinivel (5 Hz Fast / 1 Hz Slow)** |
| **Integridad** | Ninguna | **Validación Checksum XOR (NMEA-0183)** |

## Características principales
- **Triple redundancia WiFi:** Gestión automática entre red principal y dos de respaldo, con fail-over transparente ante fallo de conectividad.
- **Backoff exponencial:** Estrategia de reintentos con intervalos crecientes (2 s a 64 s) para proteger la salud de la red.
- **Watchdog de sistema coordinado:** Timeouts de red coordinados (4 s) para evitar bloqueos del sistema ante fallos del broker MQTT, dimensionados por debajo del timeout del TWDT (10 s).
- **Auto-sanación I2C:** Detección y reconfiguración en caliente del sensor de energía INA3221 sin reinicio del sistema.
- **Inercia GPS:** Ante la pérdida de satélites, el sistema mantiene el seguimiento usando las últimas coordenadas válidas disponibles en RAM. Al arranque, si aún no hay señal GPS, se recuperan las coordenadas de la sesión anterior desde NVS (memoria no volátil).
- **Sistema Silky Motion:** Rampas de aceleración limitadas a 15°/s y zona muerta de 0.4° para movimiento suave, silencioso y sin estrés mecánico.
- **Análisis de eficiencia comparativa:** Integración absoluta de energía (mWh) en lugar de media móvil con exclusión nocturna, para comparación objetiva directa de energía útil generada.
- **Homologación de Paneles por Escalamiento:** Escalamiento matemático automatizado `(* 1.238)` inyectado en la lectura de corriente del panel móvil para compensar asimetrías de hardware (56 ohms vs 40.2 ohms, de 420 mW a 520 mW), permitiendo determinar eficiencias relativas a causa únicamente de la cinemática de seguimiento.
- **Telemetría Multinivel (Mqtt High-Speed):** División del flujo de datos en dos canales independientes: un canal rápido a 5 Hz para sincronización perfecta con clientes móviles (200 ms) y un canal lento a 1 Hz para información administrativa y de GPS.
- **Validación por Checksum XOR:** Blindaje total contra ruido eléctrico en el bus serie mediante la verificación de la suma de comprobación estándar NMEA-0183, descartando tramas GPS corruptas.
- **Reactividad Instantánea (Inyección Cinemática):** Cancelación inmediata de trayectorias en curso al recibir nuevas órdenes desde la App. Los destinos astronómicos o manuales se inyectan sin latencias directamente en el lazo de 50 Hz, permitiendo pivotes impecables.

## Arquitectura del sistema
El firmware está estructurado sobre ESP-IDF v5.5.3 con arquitectura multitarea (FreeRTOS), destacando los siguientes módulos:

1.  **Capa de conectividad:** Gestión WiFi con reconexión automática por backoff exponencial y rotación entre tres SSIDs de respaldo. Comunicación MQTT bidireccional con timeout de red coordinado al TWDT.
2.  **Motor de procesamiento GPS:** Parseo de tramas NMEA ($GPRMC) con mecanismo de inercia: ante pérdida de señal, el sistema opera con el último fix válido disponible en RAM. Si no hay fix previo en la sesión actual (arranque en frío), se utilizan las coordenadas rescatadas desde NVS.
3.  **Medición y Telemetría Multinivel:** Lectura del INA3221 a 10 Hz con auto-sanación de bus. Despacho de telemetría optimizado: un hilo rápido a 5 Hz (`solar/status/fast`) para control visual fluido de potencia y ángulos, y un hilo lento a 1 Hz (`solar/status/slow`) para GPS y comandos.
4.  **Capa de actuación (Silky Motion):** Generación de PWM de 16 bits a 50 Hz con lógica de rampas volátiles que permiten la interrupción inmediata de movimientos anteriores inyectando nuevos targets directamente al driver.
5.  **Guardián del sistema (TWDT):** Task Watchdog Timer configurado a 10 s, suscrito a todas las tareas críticas, garantizando la recuperación ante bloqueos catastróficos.

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
La filosofía central del firmware es **evitar el reinicio del procesador en todo momento**. Ante cualquier fallo de hardware o conectividad, el sistema intenta recuperar el módulo afectado de forma local y sin interrumpir el resto de las tareas ni el estado almacenado en RAM. Este diseño garantiza que el buffer de telemetría, la posición solar calculada y el historial de potencia permanezcan intactos durante cualquier evento de fallo. La estrategia opera en tres niveles:

*   **Nivel 1 — Reconexión de red sin bloqueo:** Cubre fallos de conectividad WiFi (pérdida de señal, caída del punto de acceso) y fallos del broker MQTT (timeout, rechazo o caída del servidor). La reconexión se gestiona mediante un temporizador no bloqueante con backoff exponencial (2 s, 4 s, 8 s... hasta 64 s), lo que permite que las demás tareas (seguimiento solar, medición INA, movimiento de servos) continúen operando sin interrupción. Al agotar los reintentos en la red actual, el sistema rota automáticamente al SSID de respaldo 1 y luego al de respaldo 2, sin reiniciar el procesador en ningún caso.
*   **Nivel 2 — Recuperación local de periféricos:** Ante un fallo del bus I2C o la pérdida del sensor INA3221, la tarea de medición entra en un modo de búsqueda automática: reintenta la detección y reconfiguración del sensor cada 5 s de forma independiente, sin afectar al resto del sistema. De igual forma, ante la pérdida de señal GPS, el sistema mantiene el seguimiento con las últimas coordenadas válidas en RAM (o las recuperadas de NVS al arranque). En todos estos casos, el estado global del sistema —incluyendo el historial de potencia y la posición solar— se conserva íntegramente en RAM.
*   **Nivel 3 — Última barrera (TWDT):** Reservado exclusivamente para situaciones catastróficas en las que una tarea queda completamente bloqueada y no puede recuperarse por los mecanismos anteriores. El Task Watchdog Timer (timeout de 10 s) dispara un reinicio suave (*soft reset*) del procesador. La memoria NVS en flash se conserva, garantizando que la última posición GPS persista para el reinicio inmediato del seguimiento.

## Estado del proyecto
Actualmente, esta versión 2.0 se encuentra en estado **estable y funcional**, validada para operación continua 24/7 en condiciones de campo. Ha cumplido con los objetivos de diseño principales:
*   **Conectividad robusta:** Triple redundancia WiFi con reconexión autónoma y fail-over transparente entre SSIDs.
*   **Operación autónoma:** La inercia GPS y la persistencia NVS garantizan el seguimiento de posición solar incluso ante pérdida total de señal satelital, sin interrumpir el resto de las tareas ni el estado del sistema en RAM.
*   **Cinemática silenciosa:** Sistema Silky Motion elimina el estrés mecánico en los actuadores y suprime el jitter en posición de reposo.
*   **Monitoreo científico:** Medición real y normalizada de potencia (mW) con escaneos homologados (compensando paneles estáticos y móviles dispares). Cuenta además con la generación de un acumulador integral ascendente (mWh) con filtros anti-congelamiento asistidos por hardware, logrando un análisis de gran fidelidad en investigación energética.

## Perspectivas de evolución
El mapa de ruta para la siguiente iteración (v3.0) se centra en la autonomía total y la movilidad del sistema:

*   **Actualización inalámbrica (OTA):** Implementación de carga de firmware vía WiFi para realizar mejoras y correcciones sin necesidad de acceso físico al dispositivo, facilitando el mantenimiento en instalaciones remotas.
*   **Sistema de referencia dinámico (fusión de sensores IMU):** Integración de acelerómetro, giroscopio y magnetómetro para operar en condiciones dinámicas (vehículos o embarcaciones), compensando en tiempo real la orientación de la base para mantener el apuntado solar independientemente del movimiento del soporte.
*   **Algoritmos de auto-calibración por barrido de potencia:** Uso del INA3221 para realizar escaneos de posición alrededor del óptimo calculado. Esta técnica de búsqueda de máximo corregirá desviaciones sistémicas como base no nivelada o errores de alineación con el norte geográfico, garantizando la máxima eficiencia real.

---
**Nota:** *Este software se proporciona "tal cual" bajo los términos de la licencia Apache 2.0 de Espressif Systems y está diseñado para aplicaciones de energía renovable y educación técnica.*

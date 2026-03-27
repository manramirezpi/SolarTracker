# SolarTracker v2.0 — Firmware ESP32

Firmware desarrollado con ESP-IDF v5.5.3 para el seguimiento solar astronómico de 2 ejes con monitoreo energético comparativo e integración IoT. Calcula la posición del sol en tiempo real a partir de coordenadas GPS y tiempo UTC, mientras monitorea dos canales de potencia y transmite telemetría vía MQTT.

---

## Especificaciones

| Parámetro | Valor |
|---|---|
| Framework | ESP-IDF v5.5.3 |
| MCU | ESP32-WROOM-32 — Dual-Core 240 MHz |
| Heap dinámico | Minimizado — uso intensivo de buffers estáticos |
| Núcleo de control | Core 1 — aislado del stack WiFi/BT |

---

## Pinout

| Función | GPIO | Detalle |
|---|---|---|
| Servo azimut | 19 | PWM — LEDC canal 0 |
| Servo elevación | 18 | PWM — LEDC canal 1 |
| GPS RX | 17 | UART2 — 9600 baud |
| GPS TX | 16 | UART2 — no utilizado |
| I2C SDA | 21 | Bus datos — INA3221 |
| I2C SCL | 22 | Bus reloj — INA3221 |

---

## Algoritmo de posición solar

Basado en los algoritmos de Jean Meeus (*Astronomical Algorithms*, 1998),
versión simplificada con los términos de corrección principales.
La implementación sigue ocho pasos:

1. **Tiempo decimal**: conversión de HH:MM:SS UTC a fracción continua
   del día para cálculo continuo sin discontinuidades horarias

2. **Día Juliano (J2000.0)**: contador universal de días referenciado
   al mediodía del 1 de enero de 2000, que simplifica las perturbaciones
   orbitales a largo plazo (`n = JD - 2451545.0`)

3. **Parámetros orbitales**: longitud media `L` y anomalía media `g`
   con corrección de módulo para mantener el rango [0°, 360°]

4. **Coordenadas eclípticas**: longitud eclíptica con dos términos de
   corrección por excentricidad orbital:
   `λ = L + 1.915·sin(g) + 0.020·sin(2g)`
   Oblicuidad dinámica de la eclíptica:
   `ε = 23.439° - 0.0000004·n`

5. **Coordenadas ecuatoriales**: declinación y ascensión recta mediante
   proyección trigonométrica desde el plano eclíptico

6. **Tiempo sidéreo (GMST/LMST)**: Greenwich Mean Sidereal Time por
   fórmula de un término, corregido por longitud geográfica GPS para
   obtener el tiempo sidéreo local

7. **Ángulo horario**: desplazamiento del sol respecto al meridiano
   local, normalizado al rango [-180°, +180°]

8. **Coordenadas horizontales**: elevación y azimut por trigonometría
   esférica. Convención de azimut: N=0°, E=90°, S=180°, O=270°

**Precisión estimada:** ±0.2° a ±0.4°. Las correcciones omitidas
(nutación, aberración, paralaje, términos superiores de GMST) aportarían
±0.05° adicional, irrelevante dado que la resolución mecánica del sistema
con servos analógicos es ±1° a ±2°. La limitante de precisión del
seguimiento es mecánica, no algorítmica.

---

## Arquitectura de tareas FreeRTOS

```
Tarea               Prioridad   Stack (B)    Periodo    Función
─────────────────────────────────────────────────────────────────
tarea_gps           4 (alta)    4096        continuo   parseo NMEA y cálculo solar
tarea_ina           3           2048        100ms      lectura INA3221 a 10 Hz
tarea_mqtt_fast     3           3072        250ms      publica ángulos y potencia
tarea_mqtt_slow     2           3072        1000ms     publica GPS y diagnóstico
tarea_servos        3           2048        20ms       actualización PWM con rampa
tarea_principal     1 (baja)    4096        continuo   watchdog y gestión WiFi
```

### Sincronización de tareas

| Mecanismo | Uso | Justificación |
|---|---|---|
| Cola `cola_gps` (longitud 2) | Comunicación GPS → tarea_principal mediante índice de buffer ping-pong | Evita copiar cadenas NMEA completas entre tareas — solo se transfiere el índice del buffer recién llenado |
| Event Group `wifi_event_group` | Sincronización de estado WiFi con bits `WIFI_CONNECTED_BIT` y `WIFI_FAIL_BIT` | Permite bloqueo sin consumo de CPU durante la inicialización de red |
| Variables `volatile` | `pos_obj_az` y `pos_obj_el` compartidas entre tarea_principal y callback de timer | El callback opera como ISR — `volatile` fuerza lectura desde RAM y previene valores cacheados por el compilador |
| Task pinning (Core 1) | Todas las tareas de control ancladas con `xTaskCreatePinnedToCore` | El Core 0 gestiona el stack WiFi/BT de ESP-IDF — el anclaje al Core 1 aísla el cálculo astronómico y el control de servos de picos de tráfico de red |
| TWDT | Cada tarea reporta actividad mediante `esp_task_wdt_reset()` | Detecta bloqueos individuales por tarea sin necesidad de reiniciar el sistema completo |

---

## Módulos principales

### Conectividad WiFi y MQTT
- **Soporte para múltiples redes WiFi** — hasta 3 redes configurables con conmutación automática ante fallo.
- **Reconexión no bloqueante con backoff exponencial** — reintentos inteligentes de 2s a 64s para evitar saturación del Access Point.
- **Comunicación MQTT bidireccional:**
  - **Publicación de telemetría rápida (4 Hz):** ángulos de azimut/elevación y potencia instantánea de ambos paneles.
  - **Publicación de telemetría lenta (1 Hz):** coordenadas GPS, tiempo UTC, promedios móviles y energía acumulada.
  - **Suscripción a comandos remotos:** ajustes manuales de ángulos, fecha, coordenadas, factor de simulación y solicitud de batch de calibración.
- **Last Will & Testament (LWT)** — mensaje automático de desconexión con código de estado JSON para monitoreo continuo de salud desde la app.

### Parseo GPS
- **Decodificación de tramas NMEA-0183 `$GPRMC`** con validación por checksum XOR.
- **Extracción de coordenadas, velocidad y tiempo UTC.**
- **Persistencia de coordenadas en NVS (Non-Volatile Storage):**
  - Al arrancar, el sistema recupera las últimas coordenadas válidas desde flash e inicia el seguimiento en cuanto el GPS sincroniza el reloj UTC, sin esperar a que se establezca un fix de posición completo.
  - Las coordenadas en NVS se actualizan solo cuando el cambio supera 0.2° para minimizar escrituras en flash y extender su vida útil.
- **Continuidad de operación ante pérdida de señal** — usa último fix válido hasta recuperar GPS.
- **Modo búsqueda inicial** — al arrancar sin coordenadas guardadas, los servos barren lentamente en azimut hasta obtener fix GPS.

### Medición energética
- **Lectura del INA3221 a 10 Hz por I2C** — dos canales activos: panel seguidor (CH1) y panel estático (CH2).
- **Recuperación autónoma del bus I2C** — ante fallo de comunicación con el INA3221, el firmware reinicializa automáticamente el bus sin detener el seguimiento.
- **Potencia promedio instantánea**: calculada mediante buffer circular como promedio móvil de los últimos 5 minutos, suaviza variaciones rápidas por nubosidad transitoria.
- **Energía diaria acumulada (mWh)**: integración continua de potencia a lo largo del día, se reinicia automáticamente al inicio de cada jornada. La comparación entre el acumulado del panel seguidor y el estático es la métrica principal para estimar la ganancia real del seguimiento.

### Control de servos
- **Resolución PWM de 16 bits** mediante periférico LEDC del ESP32 — 65,536 niveles de duty cycle (~0.3µs por paso).
- **Rango de pulsos:** 500µs (0°) a 2500µs (180°) a 50 Hz.
- **Rampa de aceleración limitada a 15°/s** para proteger la mecánica y evitar vibraciones.
- **Interrupción de trayectoria ante comando entrante** sin pérdida de posición — permite control remoto fluido desde la app.
- **Modo parking nocturno automático** — cuando la elevación solar es negativa, los servos se posicionan a 90° (posición horizontal segura).

### Watchdog
- **Task Watchdog Timer (TWDT)** suscrito a tareas críticas: GPS, INA y principal.
- **Timeout de 20 segundos** — cada tarea reporta actividad mediante `esp_task_wdt_reset()`.
- **Reinicio suave automático** como último recurso ante bloqueos.
- **Recuperación sin intervención manual** — el sistema vuelve a operar tras el reset sin configuración adicional.

### Modos de operación

El firmware implementa una **jerarquía de prioridad** para flexibilidad de control:

1. **Modo SERVO MANUAL** — Control directo de azimut y elevación mediante comandos MQTT (ignora el sol).
2. **Modo SIMULACIÓN** — Reloj interno acelerado por factor configurable (1x a 1440x) para validar trayectorias sin esperar el ciclo diario. Permite simular 1 día completo en 1 minuto.
3. **Modo MANUAL (Fechas/Coordenadas)** — Calcula posición solar con valores inyectados por el usuario (útil para pruebas).
4. **Modo AUTOMÁTICO (Default)** — Seguimiento solar en tiempo real con datos GPS.

---

## Normalización y comparación de paneles

### Punto óptimo de operación

Cada panel opera con una resistencia de carga fija correspondiente a su punto de máxima potencia (MPP), determinada experimentalmente mediante barrido de resistencia de carga:

| Panel | Potencia MPP | Resistencia de carga |
|---|---|---|
| Estático | 520 mW | 40.2 Ω |
| Seguidor | 420 mW | 56 Ω |

### Modelo de corrección cuadrático

Para la versión 2.0, se ha implementado una **estructura de corrección cuadrática** que permite normalizar la respuesta del panel seguidor ($P_{1}$) respecto al estático ($P_{2}$), compensando diferencias de carga o eficiencia:

$$P_{1\_norm} = a \cdot P_{1}^2 + b \cdot P_{1} + c$$

**Estado actual (v2.0):**
Se ha configurado una relación **1:1 ($a=0, b=1, c=0$)** por defecto. Esta decisión permite:
1. Visualizar los datos reales medidos sin procesamientos experimentales previos.
2. Contar con una infraestructura lista para inyectar coeficientes precisos ($a, b, c$) una vez se complete la caracterización definitiva en campo.

Esta expresión permitirá calcular la ganancia real del seguimiento en versiones futuras:
```
ganancia = (P1_norm − P2_real) / P2_real × 100%
```
donde $P_{1\_norm}$ es la potencia del panel móvil normalizada a la escala del panel fijo.

Si `ganancia > 0`, el seguidor está captando más energía de la que captaría un panel fijo bajo las mismas condiciones atmosféricas.

Actualizar los coeficientes es una operación directa en el firmware (`main.c` — líneas 260-262):
```c
static float coef_a = 0.0f;  // Término cuadrático
static float coef_b = 1.0f;  // Término lineal (1:1 por defecto)
static float coef_c = 0.0f;  // Término constante
```

---

## Integración MQTT

### Tópicos de publicación

| Tópico | Contenido | Frecuencia |
|---|---|---|
| `solar/status/fast` | Ángulos (azimut, elevación) y potencia instantánea (ambos paneles) | ~4 Hz |
| `solar/status/slow` | Coordenadas GPS, tiempo UTC, promedios móviles, energía acumulada, estado del sistema | ~1 Hz |
| `solar/data/batch` | Archivo batch de 150 muestras sincronizadas para calibración | Evento |

### Tópicos de suscripción

| Tópico | Contenido |
|---|---|
| `solar/sub` | Comandos de control: ángulos manuales, fecha estática, coordenadas, factor de simulación, solicitud de batch |

### Last Will & Testament (LWT)

El firmware publica un **mensaje LWT con código de estado JSON** al desconectarse inesperadamente:
```json
{
  "conn": 0,
  "nvs": 1,
  "gps": 1,
  "i2c": 1
}
```
Este formato permite a la app Android analizar el estado de salud y detectar qué componente falló específicamente.

---

## Características principales

- **Seguimiento astronómico de alta precisión** — Cálculo continuo de posición solar usando el algoritmo de Jean Meeus, con precisión de ±0.2° a ±0.4° (limitado por mecánica, no por algoritmo).
- **Movimiento suavizado mediante rampas de aceleración** — Velocidad máxima limitada a 15°/s con interpolación lineal en timer de 20 ms, protegiendo la mecánica y eliminando vibraciones.
- **Reconexión WiFi automática con backoff exponencial** — Soporte para 3 redes WiFi con reintentos inteligentes de 2s a 64s, asegurando conectividad robusta ante fallas transitorias.
- **Operación continua ante pérdida de GPS:**
  - Persistencia de coordenadas en memoria NVS (Non-Volatile Storage).
  - El sistema arranca con el último fix guardado y continúa operando hasta recuperar señal.
  - Actualización selectiva en NVS solo cuando el cambio supera 0.2° (reduce escrituras y desgaste de flash).
- **Watchdog por tarea (TWDT):**
  - Cada tarea crítica (GPS, INA, principal) reporta actividad mediante `esp_task_wdt_reset()`.
  - Timeout de 20 segundos — ante bloqueos, el sistema se reinicia automáticamente.
  - Recuperación sin intervención manual.
- **Recuperación autónoma del bus I2C:**
  - Ante fallo de comunicación con el INA3221, el firmware detecta el error y reinicializa el bus automáticamente.
  - El seguimiento solar continúa operando durante la recuperación del sensor.
- **Filtrado digital de dos etapas:**
  - **Promedio móvil de 5 minutos:** buffer circular que suaviza variaciones rápidas por nubes o sombras transitorias.
  - **Energía acumulada diaria (mWh):** integración continua con reinicio automático al cambio de día (detección por hora UTC < hora anterior).
- **Modo parking nocturno automático:**
  - Cuando la elevación solar es negativa (sol bajo el horizonte), los servos se posicionan a 90°.
  - Protege el panel de posiciones extremas y reduce consumo durante la noche.
- **Modo búsqueda inicial:**
  - Al arrancar sin coordenadas GPS previas, los servos barren lentamente en azimut (±0.5° cada 3 segundos).
  - Evita posiciones indefinidas hasta obtener fix GPS válido.
- **Arquitectura FreeRTOS multiproceso:**
  - Todas las tareas de control (GPS, servos, INA, MQTT) corren en **Core 1**.
  - El **Core 0** queda reservado para el stack WiFi/BT de ESP-IDF.
  - Elimina picos de latencia por tráfico de red en el cálculo astronómico.
- **Sincronización eficiente con doble buffer ping-pong:**
  - El parseo UART del GPS usa dos buffers estáticos alternados.
  - Solo se transfiere el índice del buffer recién llenado entre tareas mediante cola FreeRTOS.
  - Evita copias de cadenas NMEA completas (120 bytes cada una) y reduce latencia.
- **Control de posición dual:**
  - **Modo AUTO:** seguimiento solar automático con datos GPS en tiempo real.
  - **Modo MANUAL:** control remoto directo de azimut y elevación mediante comandos MQTT desde la app.
- **Modo simulación de tiempo:**
  - Factor de velocidad ajustable (1x a 1440x).
  - Reloj interno desacoplado del tiempo real para validar trayectorias solares sin esperar el ciclo diario.
  - Permite simular 1 día completo en 1 minuto (factor 1440x).
  - Útil para pruebas de algoritmo y validación de cobertura hemisférica.

---

## Normalización y comparación de paneles

### Punto óptimo de operación

Cada panel opera con una resistencia de carga fija correspondiente a su punto de máxima potencia (MPP), determinada experimentalmente mediante barrido de resistencia de carga:

| Panel | Potencia MPP | Resistencia de carga |
|---|---|---|
| Estático | 520 mW | 40.2 Ω |
| Seguidor | 420 mW | 56 Ω |

### Modelo de corrección cuadrático

Para la versión 2.0, se ha implementado una **estructura de corrección cuadrática** que permite normalizar la respuesta del panel seguidor ($P_{1}$) respecto al estático ($P_{2}$), compensando diferencias de carga o eficiencia:

$$P_{1\_norm} = a \cdot P_{1}^2 + b \cdot P_{1} + c$$

**Estado actual (v2.0):**
Se ha configurado una relación **1:1 ($a=0, b=1, c=0$)** por defecto. Esta decisión permite:
1. Visualizar los datos reales medidos sin procesamientos experimentales previos.
2. Contar con una infraestructura lista para inyectar coeficientes precisos ($a, b, c$) una vez se complete la caracterización definitiva en campo.

Esta expresión permitirá calcular la ganancia real del seguimiento en versiones futuras:
```
ganancia = (P1_norm − P2_real) / P2_real × 100%
```
donde $P_{1\_norm}$ es la potencia del panel móvil normalizada a la escala del panel fijo.

Si `ganancia > 0`, el seguidor está captando más energía de la que captaría un panel fijo bajo las mismas condiciones atmosféricas.

Actualizar los coeficientes es una operación directa en el firmware (`main.c` — líneas 260-262):
```c
static float coef_a = 0.0f;  // Término cuadrático
static float coef_b = 1.0f;  // Término lineal (1:1 por defecto)
static float coef_c = 0.0f;  // Término constante
```

---

## Compilación

**Requisitos:** ESP-IDF v5.5.3 instalado y configurado en el sistema.

```bash
cd v2/codigo/esp32

# 1. Configurar credenciales de red (solo la primera vez)
cp main/config.example.h main/config.h
# Editar main/config.h con el SSID, contraseña WiFi y datos del broker MQTT

# 2. Configurar el target (solo la primera vez)
idf.py set-target esp32

# 3. Compilar
idf.py build

# 4. Flashear y monitorear (ajustar el puerto según el sistema)
#    Linux/macOS: /dev/ttyUSB0 o /dev/ttyACM0
#    Windows:     COM3, COM4, etc.
idf.py -p /dev/ttyUSB0 flash monitor
```

Al iniciar, el sistema espera fix GPS (log `[GPS] Fix válido`). Una vez adquirido, el seguimiento astronómico arranca automáticamente.

---

## Licencia

MIT License — ver [LICENSE](../../../LICENSE)

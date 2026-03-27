# SolarTracker v2.0 — Firmware ESP32

Firmware desarrollado con ESP-IDF v5.5.3 para el seguimiento solar astronómico 
de 2 ejes con monitoreo energético e integración IoT.

Esta adaptación para v2.0 abandona la aproximación puramente algorítmica de la v1.0 en STM32, adoptando hardware ESP32 para dotar al seguimiento de comunicación MQTT, persistencia de variables y conectividad IoT nativa.

---

## Especificaciones

| Parámetro | Valor |
|---|---|
| Framework | ESP-IDF v5.5.3 |
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
## Sincronización de tareas

| Mecanismo | Uso | Justificación |
|---|---|---|
| Cola `cola_gps` (longitud 2) | Comunicación GPS → tarea_principal mediante índice de buffer ping-pong | Evita copiar cadenas NMEA completas entre tareas — solo se transfiere el índice del buffer recién llenado |
| Event Group `wifi_event_group` | Sincronización de estado WiFi con bits `WIFI_CONNECTED_BIT` y `WIFI_FAIL_BIT` | Permite bloqueo sin consumo de CPU durante la inicialización de red |
| Variables `volatile` | `pos_obj_az` y `pos_obj_el` compartidas entre tarea_principal y callback de timer | El callback opera como ISR — `volatile` fuerza lectura desde RAM y previene valores cacheados por el compilador |
| Task pinning (Core 1) | Todas las tareas de control ancladas con `xTaskCreatePinnedToCore` | El Core 0 gestiona el stack WiFi/BT de ESP-IDF — el anclaje al Core 1 aísla el cálculo astronómico y el control de servos de picos de tráfico de red |
| TWDT | Cada tarea reporta actividad mediante `esp_task_wdt_reset()` | Detecta bloqueos individuales por tarea sin necesidad de reiniciar el sistema completo |

---

## Características principales

### Conectividad WiFi y MQTT
- Soporte para tres redes WiFi con conmutación automática ante fallo
- Reconexión no bloqueante con backoff exponencial de 2s a 64s
- Comunicación MQTT bidireccional con soporte de Last Will & Testament (LWT) para monitoreo continuo de salud de red

### Parseo GPS
- Decodificación de tramas NMEA-0183 `$GPRMC` con validación por checksum XOR
- Extracción de coordenadas, velocidad y tiempo UTC
- Persistencia de coordenadas en NVS (Non-Volatile Storage): al arrancar, 
  el sistema recupera las últimas coordenadas válidas desde flash e inicia 
  el seguimiento en cuanto el GPS sincroniza el reloj UTC, sin esperar a 
  que se establezca un fix de posición completo. Las coordenadas en NVS 
  se actualizan solo cuando el cambio supera 0.2° para minimizar escrituras 
  en flash
- Continuidad de operación ante pérdida de señal usando último fix válido

### Medición energética
- Lectura del INA3221 a 10 Hz por I2C
- Tres canales simultáneos: panel seguidor, panel estático y reserva
- **Potencia promedio instantánea**: calculada mediante buffer circular como 
  promedio móvil, representa el comportamiento energético de los últimos 
  minutos y suaviza variaciones rápidas por nubosidad transitoria
- **Energía diaria acumulada (mWh)**: integración continua de potencia a lo 
  largo del día, se reinicia automáticamente al inicio de cada jornada. 
  La comparación entre el acumulado del panel seguidor y el estático es 
  la métrica principal para estimar la ganancia real del seguimiento

### Control de servos
- Resolución PWM de 16 bits mediante periférico LEDC del ESP32
- Rampa de aceleración limitada a 15°/s para proteger la mecánica
- Interrupción de trayectoria ante comando entrante sin pérdida de posición

### Watchdog
- Task Watchdog Timer (TWDT) suscrito a tareas críticas: GPS, INA y principal
- Timeout de 20 segundos con reinicio suave como último recurso
- Recuperación autónoma del bus I2C ante fallo de comunicación con el INA3221

---

## Normalización y comparación de paneles

### Punto óptimo de operación

Cada panel opera con una resistencia de carga fija correspondiente a su 
punto de máxima potencia (MPP), determinada experimentalmente mediante 
barrido de resistencia de carga:

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

Si `ganancia > 0`, el seguidor está captando más energía de la que captaría un panel fijo bajo las mismas condiciones. Actualizar los coeficientes es una operación directa en el firmware (`main.c`).

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

Al iniciar, el sistema espera fix GPS (LED o log `[GPS] Fix válido`). Una vez adquirido, el seguimiento astronómico arranca automáticamente.

---

## Licencia

MIT License — ver [LICENSE](../../../LICENSE)

# SolarTracker v2.0 — Firmware ESP32

Firmware desarrollado con ESP-IDF v5.5 para el seguimiento solar astronómico 
de 2 ejes con monitoreo energético e integración IoT.

---

## Especificaciones

| Parámetro | Valor |
|---|---|
| Microcontrolador | ESP32 dual-core 240 MHz |
| Framework | ESP-IDF v5.5 |
| RTOS | FreeRTOS multitarea |
| Gestión de memoria | Buffers estáticos para evitar fragmentación del heap |

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

### Algoritmo de posición solar

Basado en los algoritmos de Jean Meeus (*Astronomical Algorithms*, 1998). 
La implementación incluye los siguientes componentes:

- Día Juliano relativo al epoch J2000.0 para simplificar perturbaciones 
  orbitales a largo plazo
- Longitud media y anomalía media para posición teórica en la elipse orbital
- Coordenadas eclípticas con corrección de excentricidad y oblicuidad dinámica 
  de la eclíptica (`ε = 23.439° - 0.0000004·n`)
- Tiempo sidéreo local (LMST) calculado a partir de la longitud geográfica GPS
- Proyección mediante trigonometría esférica de coordenadas ecuatoriales 
  (ascensión recta y declinación) al plano horizontal local

**Precisión estimada:** ±0.2° a ±0.4°, suficiente para la resolución mecánica 
del sistema (±1-2° con servos analógicos). Las correcciones omitidas 
(nutación, aberración, paralaje) aportarían una mejora de ±0.05° adicional, 
irrelevante para esta aplicación.

---

## Arquitectura de tareas FreeRTOS
```
Tarea               Prioridad   Stack    Periodo    Función
─────────────────────────────────────────────────────────────────
tarea_gps           4 (alta)    4096B    continuo   parseo NMEA y cálculo solar
tarea_ina           3           2048B    100ms      lectura INA3221 a 10 Hz
tarea_mqtt_fast     3           3072B    250ms      publica ángulos y potencia
tarea_mqtt_slow     2           3072B    1000ms     publica GPS y diagnóstico
tarea_servos        3           2048B    20ms       actualización PWM con rampa
tarea_principal     1 (baja)    4096B    continuo   watchdog y gestión WiFi
```

Sincronización: [especificar — mutex, semáforos, colas entre tareas]

---

## Módulos principales

### Conectividad WiFi y MQTT
- Soporte para tres redes WiFi con conmutación automática ante fallo
- Reconexión no bloqueante con backoff exponencial de 2s a 64s
- Comunicación MQTT bidireccional con el broker

### Parseo GPS
- Decodificación de tramas NMEA-0183 `$GPRMC` con validación por checksum XOR
- Extracción de coordenadas, velocidad y tiempo UTC
- Continuidad de operación ante pérdida de señal usando último fix válido

### Medición energética
- Lectura del INA3221 a 10 Hz por I2C
- Tres canales simultáneos: panel seguidor, panel estático y reserva
- Cálculo de energía acumulada (mWh) en el firmware

### Control de servos
- Resolución PWM de 16 bits mediante periférico LEDC del ESP32
- Rampa de aceleración limitada a 15°/s para proteger la mecánica
- Interrupción de trayectoria ante comando entrante sin pérdida de posición

### Watchdog
- Task Watchdog Timer (TWDT) suscrito a tareas críticas: GPS, INA y principal
- Timeout de 10 segundos con reinicio suave como último recurso
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

### Caracterización de la relación entre paneles

Dado que los dos paneles tienen respuestas ligeramente diferentes a la 
irradiancia, una corrección lineal introduce error variable a lo largo 
del día. Para corregir esto, el sistema usa una aproximación polinomial 
obtenida experimentalmente:

**Procedimiento de caracterización:**
1. Ambos paneles se exponen a las mismas condiciones de irradiancia 
   simultáneamente a lo largo de un día con variabilidad alta (nubosidad 
   parcial, transiciones sol-sombra), con el objetivo de cubrir el mayor 
   rango posible de niveles de irradiancia
2. Se registran múltiples pares (P_estático, P_seguidor) distribuidos 
   a lo largo de ese rango
3. Se ajusta un polinomio de segundo orden por mínimos cuadrados sobre 
   esos pares

Un día de irradiancia estable produciría datos concentrados en un rango 
estrecho, resultando en un modelo no apto para condiciones 
variables. La variabilidad atmosférica durante la caracterización es 
por tanto una condición deseable, no un inconveniente.
```
Esta expresión representa la potencia que debería generar el panel 
seguidor si estuviera en las mismas condiciones que el estático. 
La ganancia real del seguimiento se calcula entonces como:
```
ganancia = (P_seguidor_real - P_seguidor_esperado) / P_seguidor_esperado × 100%
```

Si `ganancia > 0`, el seguidor está captando más energía de la que 
captaría un panel fijo bajo las mismas condiciones atmosféricas.

**Estado actual:** caracterización pendiente de medición en día despejado 
con irradiancia estable. Los coeficientes `a`, `b` y `c` están definidos 
como constantes configurables en el firmware.
---

## Compilación

Requiere ESP-IDF v5.5 instalado y configurado.
```bash
cd v2/codigo/esp32
idf.py set-target esp32   # solo primera vez
idf.py build
idf.py -p /dev/ttyUSB0 flash monitor
```



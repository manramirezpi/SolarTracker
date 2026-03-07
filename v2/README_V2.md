# Seguidor Solar IoT - Versión 2.0 (Resiliencia y Monitoreo Avanzado)

## 📝 Resumen del Proyecto
Sistema industrial de seguimiento solar basado en **ESP32**, diseñado para maximizar la eficiencia de paneles fotovoltaicos mediante control astronómico dinámico. Esta versión 2.0 consolida un firmware de **alta disponibilidad**, capaz de operar de forma autónoma ante fallos de red o sensores, integrando un monitoreo de energía de grado científico.

---

## 🛡️ Filosofía de Estabilidad y Resiliencia

### 1. Conectividad Auto-Recuperable (WiFi & MQTT)
*   **Triple Redundancia SSID:** El sistema rota automáticamente entre una red Principal y dos de Respaldo ante fallos de conexión.
*   **Backoff Exponencial:** Secuencia de reintentos inteligente (2s a 64s) para evitar la saturación de los puntos de acceso.
*   **Monitor de Estabilidad de Aplicación:** Si el enlace MQTT es inestable, el sistema fuerza un reinicio de la capa inalámbrica para garantizar la salida a Internet.
*   **Protección Anti-Pánico (Watchdog):** Timeout de red MQTT fijado en **4s**, asegurando que un servidor caído no bloquee el hilo principal (Watchdog de 10s).

### 2. Integridad Sensórica y Operación Degradada
*   **Modo Auto-Búsqueda I2C:** Si el bus se bloquea o el sensor INA3221 se desconecta, el sistema entra en búsqueda activa y se re-configura automáticamente al detectar el hardware de nuevo.
*   **Autonomía GPS:** Ante la pérdida de satélites, utiliza la última ubicación válida conocida. Si arranca sin datos, inicia un **Escaneo Azimutal de 360°** para evitar quedar en una posición de sombra.

---

## 🚀 Características Cinéticas y Energéticas

### 1. Movimiento "Silky Motion"
*   **Rampas de Velocidad:** Limitado a **15°/s** para proteger la integridad mecánica.
*   **Control de Jitter:** Histéresis de **0.4°** y redondeo PWM robótico para eliminar vibraciones en reposo.
*   **Algoritmo Backflip:** Rotación inteligente de 180° para seguir al sol cruzando el Norte sin enredar el cableado.

### 2. Monitoreo INA3221 Optimizado
*   **Configuración de Canales:** Canales 1 (Panel) y 2 (Sistema) activos (`0x6827`) para máxima velocidad de muestreo.
*   **Media Móvil de Producción:** Buffer de 24 horas (288 bloques) que **excluye la noche**. Calcula el promedio diario basándose solo en las horas de sol real (>0.1 µW), ideal para comparar rendimiento entre paneles.

---

## 🛠 Especificaciones Técnicas

### Asignación de Pines (GPIO)
| Periférico | Pin ESP32 | Función |
| :--- | :--- | :--- |
| **Servo Azimut** | 19 | Salida PWM |
| **Servo Elevación** | 18 | Salida PWM |
| **GPS RX** | 17 | UART2 |
| **I2C SDA / SCL** | 21 / 22 | Bus Sensores |

### Parámetros de Operación
*   **Frecuencia PWM:** 50 Hz
*   **Resolución:** 16-bit LEDC
*   **Zona Muerta:** 0.4°
*   **Umbral de Producción:** 0.1 µW

---

## 📡 Comandos de Control (Tópico: `solar/sub`)
| Comando | Rango | Efecto |
| :--- | :--- | :--- |
| `set_ser_az` | -90.1 a 90.1 | Ángulo físico de la base. |
| `set_ser_el` | -0.1 a 180.1 | Ángulo físico de inclinación. |
| `set_lat` | Decimal | Latitud manual (Sobrescribe GPS). |
| `set_vel` | 1 a 1440 | Factor de velocidad para simulación. |

**Desarrollado para operación continua 24/7 con el ecosistema ESP-IDF v5.5.3.**

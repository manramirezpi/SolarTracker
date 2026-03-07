# Seguidor Solar IoT - Versión 2.0 (Salto Tecnológico y Resiliencia)

## 📝 Resumen del Proyecto
Esta versión 2.0 representa la evolución del sistema original basado en STM32 hacia una plataforma **IoT de alta disponibilidad** basada en el **ESP32**. El enfoque ha migrado de un control local y visualización física, a un entorno de monitoreo científico remoto, robustez ante fallos de red y una cinemática de precisión optimizada para la comparación de eficiencia energética.

---

## 🔄 Evolución: De v1.0 (STM32) a v2.0 (ESP32)

Lo que en la v1.0 eran "Perspectivas de Evolución", en la v2.0 es ahora una realidad funcional:

| Característica | Versión 1.0 (STM32) | Versión 2.0 (ESP32 - Actual) |
| :--- | :--- | :--- |
| **Cerebro** | STM32F4 (100MHz) | ESP32 Dual-Core (240MHz) |
| **Interfaz** | Local (LCD 20x4 + Consola Serial) | **Ubícua (App Móvil + Dashboard MQTT)** |
| **Control** | Comandos por cable (UART) | **Mando inalámbrico bidireccional** |
| **Monitoreo** | Teórico (Cálculo solar) | **Científico (INA3221 - mW reales)** |
| **Resiliencia** | Protección básica | **Triple WiFi Redundante + Fail-over** |
| **Cinemática** | Movimiento directo | **Silky Motion (Rampas + Histéresis 0.4°)** |
| **Análisis** | No disponible | **Media Móvil 24h (Solo horas de sol)** |

---

## 🛡️ Estabilidad y Resiliencia (Firmware de Grado Industrial)

### 1. Conectividad Auto-Recuperable
*   **Triple Redundancia SSID:** Gestión automática entre red Principal y dos de Respaldo.
*   **Backoff Exponencial:** Estrategia de reintentos (2s a 64s) para proteger la salud de la red.
*   **Safety Watchdog:** Timeouts de red coordinados (4s) para evitar bloqueos del sistema ante fallos del broker MQTT.

### 2. Operación Degradada Elegante
*   **Auto-Sanación I2C:** Detección y re-configuración en caliente del sensor de energía sin reinicio del sistema.
*   **Inercia GPS:** Ante la pérdida de satélites, el sistema mantiene el seguimiento usando el último fix válido y su reloj interno sincronizado.

---

## 🚀 Innovaciones Energéticas y Cinéticas

### 1. Sistema "Silky Motion"
*   **Protección Mecánica:** Rampas de aceleración a **15°/s** que eliminan el estrés en los ejes.
*   **Eliminación de Jitter:** Filtro de zona muerta de **0.4°** y redondeo PWM robótico para un reposo absoluto y silencioso.

### 2. Análisis de Eficiencia Comparativa
*   **Promediado Inteligente:** El sistema calcula la potencia media **excluyendo la noche**. Esto permite comparar directamente el rendimiento del panel móvil frente a uno estático sin que el valor de 0W nocturno sesgue el resultado.
*   **Precisión Científica:** Reporte en milivatios (mW) con resolución de 6 decimales.

---

## 🛠 Especificaciones Técnicas

| Periférico | Pin ESP32 | Parámetro | Valor |
| :--- | :--- | :--- | :--- |
| **Azimut** | 19 | **Frecuencia PWM** | 50 Hz |
| **Elevación** | 18 | **Resolución PWM** | 16 bits |
| **GPS RX** | 17 | **Dead-band** | 0.4° |
| **I2C Bus** | 21 / 22 | **Umbral Captura** | > 0.1 µW |

---

## 📡 Protocolo de Control IoT (`solar/sub`)
Respuesta robusta a comandos JSON: `set_ser_az`, `set_ser_el`, `set_lat`, `set_vel`. Incluye validación de rangos permisivos (+/- 90.1°) para asegurar la compatibilidad con cualquier deslizador de aplicación móvil.

**Firmware desarrollado sobre ESP-IDF v5.5.3, optimizado para pruebas de campo 24/7.**

---

## 🔮 Perspectivas de Evolución: Versión 3.0

El mapa de ruta para la siguiente gran iteración se centra en la autonomía total y la movilidad del sistema:

1.  **Actualización Inalámbrica (OTA - Over-The-Air):** Implementación de carga de firmware vía WiFi. Esto permitirá realizar mejoras y correcciones en la lógica de control sin necesidad de acceso físico al dispositivo, facilitando el mantenimiento en instalaciones remotas o de difícil acceso.
2.  **Sistema de Referencia Dinámico (Fusión de Sensores IMU):** Integración de acelerómetro, giroscopio y magnetómetro (IMU). El objetivo es evolucionar el seguidor hacia una plataforma capaz de operar en **condiciones dinámicas** (ej. en vehículos o embarcaciones). El sistema compensará en tiempo real el balanceo y la orientación de la base para mantener el panel siempre apuntando al sol, independientemente del movimiento del soporte.
3.  **Algoritmos de Auto-Calibración por Barrido de Potencia:** Utilizando la alta precisión del INA3221, el seguidor realizará breves escaneos de posición alrededor del objetivo óptimo. Esta técnica de "búsqueda de máximo" servirá para corregir automáticamente desviaciones sistémicas, como una base no perfectamente nivelada o errores en la alineación con el Norte geográfico, garantizando la máxima eficiencia real.

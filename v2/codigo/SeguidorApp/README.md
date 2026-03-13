# SolarTracker Pro - Android App (v2.0)

Esta es la aplicación móvil diseñada para el monitoreo y control remoto del sistema SolarTracker v2.0. La App actúa como un centro de control científico, permitiendo visualizar en tiempo real el rendimiento energético y la posición del sistema.

## Características de la Interfaz
- **Visualización en Tiempo Real:** Dashboard con medidores (Gauges) para voltaje, corriente y potencia instantánea (actualización a 5 Hz).
- **Mando Inalámbrico:** Sliders de control manual para mover el seguidor directamente (Azimut y Elevación).
- **Modo Simulación:** Interfaz para ajustar la velocidad de simulación y realizar pruebas de trayectorias.
- **Gráficas de Rendimiento:** Visualización de la energía acumulada (mWh) y comparación entre paneles.

## Arquitectura de Software
La aplicación está construida sobre el patrón **MVC (Modelo-Vista-Controlador)** con un fuerte enfoque en el rendimiento:

- **Procesamiento de Telemetría (ProcesadorTelemetria):** Optimizado para evitar el Garbage Collector de Java. Lee los datos MQTT como texto puro para evitar el costo computacional de parsear JSON a alta frecuencia (5 Hz).
- **Comunicaciones (ClientePubSubMQTT):** Cliente MQTT asíncrono con gestión de colas concurrentes para no bloquear el hilo principal de la interfaz (UI Thread).
- **UI Reactiva:** Implementa pausas de bloqueo inteligente (3s) tras la intervención manual del usuario, evitando saltos visuales mientras se ajustan los parámetros.
- **Buffer Circular:** Utilizado para el cálculo de promedios móviles, garantizando que las agujas de los medidores se muevan con suavidad.

## Integración MQTT
La App se suscribe a dos canales de datos:
1.  `solar/status/fast`: Datos críticos (Ángulos y Potencia) para visualización fluida.
2.  `solar/status/slow`: Datos administrativos y de geolocalización.

Y publica comandos en:
- `solar/sub`: Envío de coordenadas manuales, fechas estáticas y comandos de modo.

## Requisitos de Desarrollo
- **IDE:** Android Studio (Koala o superior).
- **SDK Mínimo:** Android 7.0 (API 24).
- **Librerías Clave:**
    - Paho MQTT Client (para comunicaciones).
    - [Insertar librerías de gráficas si aplica, ej: MPAndroidChart].

---
**Nota de Diseño:** Los componentes visuales están aislados en la clase `GeneradorUI`, lo que facilita el rediseño estético sin afectar la lógica de comunicaciones o procesamiento de datos.

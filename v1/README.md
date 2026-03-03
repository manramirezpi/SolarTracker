# SolarTracker v1.0
Astronomical solar tracker - STM32 &amp; ESP32 versions

## Descripción General
Este proyecto consiste en un sistema de seguimiento solar de alta precisión basado en el microcontrolador **STM32**. A diferencia de los seguidores solares convencionales que utilizan fotorresistencias (LDR), este sistema emplea un **algoritmo de posicionamiento astronómico** para calcular la ubicación exacta del sol (Azimut y Elevación) en tiempo real.

El sistema utiliza datos de posición y tiempo global obtenidos mediante un módulo **GPS**, permitiendo una orientación óptima del panel incluso en condiciones de nubosidad total o interferencias lumínicas. El propósito principal es maximizar la eficiencia energética de sistemas fotovoltaicos mediante un control predictivo y preciso de dos ejes.

## Características Principales
- **Cálculo Solar de Alta Precisión:** Implementación de algoritmos astronómicos que incluyen cálculo de Día Juliano, longitud eclíptica, oblicuidad y ángulo horario.
- **Geolocalización Automática:** Integración con GPS (protocolo NMEA $GPRMC) para obtener latitud, longitud y tiempo UTC sin intervención del usuario.
- **Control de Doble Eje:** Gestión de movimientos de Azimut y Elevación mediante servomotores controlados por PWM (TIM10 y TIM11).
- **Interfaz de Comando (CLI):** Sistema robusto de control mediante consola serie (USART con DMA) que permite:
    - Consultar datos de sensores y cálculos.
    - Forzar coordenadas y fechas manualmente.
    - Ajustar la velocidad de simulación para pruebas de laboratorio.
- **Monitoreo de Energía:** Lectura de corriente generada por el panel solar mediante el ADC1 para supervisión de rendimiento.
- **Lógica de "Back-flip":** Algoritmo inteligente que gestiona los límites mecánicos de los servos (180°), invirtiendo la posición cuando el sol se encuentra fuera del rango físico del actuador.
- **Visualización en Tiempo Real:** Interfaz de usuario en pantalla LCD 20x4 vía I2C que muestra coordenadas, hora local (ajustada automáticamente por zona horaria) y estado del sistema.

## Arquitectura del Sistema
El firmware está estructurado sobre la capa de abstracción de hardware (HAL) de STM32, destacando los siguientes módulos:

1.  **Capa de Comunicación:** Uso intensivo de **DMA (Direct Memory Access)** para la recepción y transmisión de datos UART, garantizando que el procesamiento de comandos no bloquee el ciclo principal.
2.  **Motor de Procesamiento GPS:** Parseo manual de tramas NMEA para extraer información crítica de navegación.
3.  **Núcleo de Tiempo:** Gestión de tiempo interno capaz de alternar entre tiempo real (GPS) y un modo de simulación acelerada para validación de trayectorias.
4.  **Capa de Actuación:** Generación de señales PWM con restricciones de seguridad (*clamping*) para proteger la integridad de los servomotores.

## Especificaciones Técnicas (v1.0)
- **Microcontrolador:** Familia STM32F4 (Frecuencia de reloj a 100MHz).
- **Periféricos:** 
    - UART1 (GPS) / UART2 (Consola/Debug).
    - I2C1 (Display LCD).
    - ADC1 (Sensor de corriente).
    - TIM1 (Base de tiempo), TIM10/11 (PWM Servos).
- **Precisión Angular:** [PENDIENTE: Definir según resolución mecánica de los servos].
- **Rango de Operación:** Latitud/Longitud global; Elevación de 0° a 90°.

## Estado del Proyecto
Actualmente, el proyecto se encuentra en su **versión 1.0 funcional**. 
- [x] Implementación completa del algoritmo solar.
- [x] Gestión de comandos vía UART con DMA.
- [x] Control de servos con lógica de inversión.
- [x] Monitoreo por ADC operativo.
- [ ] **Próximos pasos:** Calibración fina de los factores de conversión PWM y pruebas de campo con carga real.

---

**Nota:** *Este software se proporciona "tal cual" bajo los términos de licencia de STMicroelectronics y está diseñado para aplicaciones de energía renovable y educación técnica.*

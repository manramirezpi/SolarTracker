# SOLARTRACKER v1.0

## Descripción general
Este proyecto consiste en un sistema de seguimiento solar de alta precisión basado en el microcontrolador **STM32**. A diferencia de los seguidores solares convencionales que utilizan fotorresistencias (LDR), este sistema emplea un **algoritmo de posicionamiento astronómico** para calcular la ubicación exacta del sol (Azimut y Elevación) en tiempo real.

El sistema utiliza datos de posición y tiempo global obtenidos mediante un módulo **GPS**, permitiendo una orientación óptima del panel incluso en condiciones de nubosidad total o interferencias lumínicas. El propósito principal es maximizar la eficiencia energética de sistemas fotovoltaicos mediante un control predictivo y preciso de dos ejes.

## Características principales
- **Cálculo solar de alta precisión:** Implementación de algoritmos astronómicos que incluyen cálculo de Día Juliano, longitud eclíptica, oblicuidad y ángulo horario.
- **Geolocalización automática:** Integración con GPS (protocolo NMEA $GPRMC) para obtener latitud, longitud y tiempo UTC sin intervención del usuario.
- **Control de doble eje:** Gestión de movimientos de Azimut y Elevación mediante servomotores controlados por PWM (TIM10 y TIM11).
- **Interfaz de comando (CLI):** Sistema robusto de control mediante consola serie (USART con DMA) que permite:
    - Consultar datos de cálculos.
    - Forzar coordenadas y fechas manualmente.
    - Ajustar la velocidad de simulación para pruebas de laboratorio.
- **Lógica de "Back-flip":** Algoritmo inteligente que gestiona los límites mecánicos de los servos (180°), invirtiendo la posición cuando el sol se encuentra fuera del rango físico del actuador.
- **Visualización en Tiempo Real:** Interfaz de usuario en pantalla LCD 20x4 vía I2C que muestra coordenadas, hora local (ajustada automáticamente por zona horaria) y estado del sistema.

## Arquitectura del Sistema
El firmware está estructurado sobre la capa de abstracción de hardware (HAL) de STM32, destacando los siguientes módulos:

1.  **Capa de comunicación:** Uso intensivo de **DMA (Direct Memory Access)** para la recepción y transmisión de datos UART, garantizando que el procesamiento de comandos no bloquee el ciclo principal.
2.  **Motor de procesamiento GPS:** Parseo manual de tramas NMEA para extraer información crítica de navegación.
3.  **Núcleo de tiempo:** Gestión de tiempo interno capaz de alternar entre tiempo real (GPS) y un modo de simulación acelerada para validación de trayectorias.
4.  **Capa de actuación:** Generación de señales PWM con restricciones de seguridad (*clamping*) para proteger la integridad de los servomotores.

## Especificaciones técnicas (v1.0)
- **Microcontrolador:** Familia STM32F4 (Frecuencia de reloj a 100MHz).
- **Periféricos:** 
    - UART1 (GPS) / UART2 (Consola/Debug).
    - I2C1 (Display LCD).
    - TIM1 (Base de tiempo), TIM10/11 (PWM Servos).

## Especificaciones de seguimiento (v1.0)
El sistema está diseñado para ofrecer una **cobertura hemisférica completa**, optimizando la posición del panel sin importar la ubicación geográfica del usuario:

*   **Rango de Elevación:** $0^\circ$ a $90^\circ$. 
    *   Calculado desde la horizontal (horizonte) hasta la vertical absoluta (cenit). El algoritmo detecta elevaciones negativas (noche), en cuyo caso el sistema restringe su movimiento en esta         componente.
*   **Rango de Azimut:** $0^\circ$ a $360^\circ$. 
    *   Cobertura circular completa. El sistema rastrea el sol desde el Norte ($0^\circ$), pasando por el Este ($90^\circ$), Sur ($180^\circ$) y Oeste ($270^\circ$).
*   **Resolución de Cálculo:** Doble precisión (`double`) para coordenadas astronómicas, permitiendo un error de cálculo despreciable frente a la resolución mecánica de los actuadores.

## Lógica de Control Dinámico (Back-flip)
Para solucionar la limitación física de los servomotores estándar (cuyo rango suele ser de $180^\circ$), el firmware implementa una **estrategia de inversión inteligente**:
*   **Modo Estándar:** Cuando el sol se encuentra en el sector Sur (entre $90^\circ$ y $270^\circ$ de Azimut), el seguidor apunta de forma directa.
*   **Modo Back-flip:** Si el sol transita por el sector Norte (fuera del rango mecánico del servo de azimut), el sistema rota la base $180^\circ$ en sentido opuesto e invierte el ángulo de elevación (suplementario). 
*   **Resultado:** Se logra un seguimiento efectivo de **360° de Azimut** utilizando hardware limitado a $180^\circ$, maximizando la simplicidad mecánica.

## Estado del Proyecto
Actualmente, esta **Versión 1.0** se encuentra en estado **Estable y Funcional**. Representa la base de control astronómico y mecánico del sistema, habiendo cumplido con los objetivos de diseño principales:
*   **Precisión Astronómica:** Implementación exitosa del algoritmo de posicionamiento solar con corrección por geolocalización GPS.
*   **Arquitectura No Bloqueante:** Gestión de periféricos (UART, ADC) mediante **DMA** para garantizar la integridad de los tiempos de ciclo del microcontrolador.
*   **Robustez Mecánica:** Control de doble eje mediante lógica de inversión (*Back-flip*) para superar limitaciones físicas de los actuadores.
*   **Monitoreo Local:** Visualización de parámetros y gestión de comandos en tiempo real a través de interfaz física (LCD) y consola serie (CLI).

## Perspectivas de Evolución
La arquitectura Lógica de este sistema permite proyectar el desarrollo hacia una segunda etapa de optimización y conectividad, buscando llevar la plataforma hacia un entorno **Smart Energy**:

*   **Telemetría y Análisis Energético:** Desarrollo de algoritmos para el cálculo de potencia generada y la implementación de sistemas de comparación de promedios históricos para evaluar la degradación o suciedad de los paneles.
*   **Transición a Interfaz IoT:** Migración de la visualización local hacia un ecosistema inalámbrico, sustituyendo la pantalla física por una comunicación bidireccional con aplicaciones móviles o tableros de control en la nube.
*   **Gestión Remota Ubicua:** Sustitución del control por comandos serie por una interfaz de usuario integrada en una App, permitiendo el mando, configuración y supervisión del seguidor desde cualquier ubicación vía IoT.
---
**Nota:** *Este software se proporciona "tal cual" bajo los términos de licencia de STMicroelectronics y está diseñado para aplicaciones de energía renovable y educación técnica.*

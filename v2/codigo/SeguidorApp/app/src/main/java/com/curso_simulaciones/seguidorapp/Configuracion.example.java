package com.curso_simulaciones.seguidorapp;

/**
 * Configuracion.example.java — Plantilla de configuración de red.
 *
 * INSTRUCCIONES:
 *   1. Copiar este archivo como "Configuracion.java" en el mismo directorio.
 *   2. Reemplazar cada valor de ejemplo con las credenciales reales.
 *   3. No agregar Configuracion.java al repositorio (ya está en .gitignore).
 */
public class Configuracion {

    // ─── Broker MQTT ──────────────────────────────────────────────────────────
    // Formato: "tcp://IP_O_DOMINIO:PUERTO"
    public static final String MQTT_HOST     = "tcp://IP_O_DOMINIO:1883";
    public static final String MQTT_USERNAME = "usuario_mqtt";
    public static final String MQTT_PASSWORD = "contraseña_mqtt";
}

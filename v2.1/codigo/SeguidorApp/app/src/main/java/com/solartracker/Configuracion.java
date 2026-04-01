package com.solartracker;

/**
 * Configuracion.java — Credenciales de red de la aplicación.
 *
 * IMPORTANTE: Este archivo contiene credenciales reales y está excluido del
 * repositorio mediante .gitignore. Para configurar en un entorno nuevo, copiar
 * Configuracion.example.java como Configuracion.java y completar los valores.
 */
public class Configuracion {

    // ─── Broker MQTT ──────────────────────────────────────────────────────────
    // Formato: "tcp://IP_O_DOMINIO:PUERTO"
    public static final String MQTT_HOST     = "tcp://45.56.74.248:1883";
    public static final String MQTT_USERNAME = "fisica";
    public static final String MQTT_PASSWORD = "iotfisica";
}

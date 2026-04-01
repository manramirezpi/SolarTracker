/*
 * config.example.h — Plantilla de configuración de red para el Seguidor Solar
 *
 * INSTRUCCIONES:
 *   1. Copiar este archivo como "config.h" en el mismo directorio.
 *   2. Reemplazar cada valor de ejemplo con las credenciales reales.
 *   3. No agregar config.h al repositorio (ya está en .gitignore).
 */

#ifndef CONFIG_H
#define CONFIG_H

// ─── Red WiFi principal ───────────────────────────────────────────────────────
#define WIFI_SSID          "NombreDeRed"
#define WIFI_PASSWORD      "ContraseñaWiFi"

// ─── Red WiFi de respaldo ─────────────────────────────────────────────────────
#define WIFI_BACKUP_SSID      "RedDeRespaldo"
#define WIFI_BACKUP_PASSWORD  "ContraseñaRespaldo"

// ─── Red WiFi de respaldo 2 ───────────────────────────────────────────────────
#define WIFI_BACKUP2_SSID     "OtraRed"
#define WIFI_BACKUP2_PASSWORD "OtraContraseña"

// ─── Broker MQTT ──────────────────────────────────────────────────────────────
// URL o IP del broker. Ej: "192.168.1.100" o "broker.hivemq.com"
#define MQTT_BROKER_URL  "IP_O_DOMINIO_DEL_BROKER"
#define MQTT_BROKER_PORT 1883
#define MQTT_USERNAME    "usuario_mqtt"
#define MQTT_PASSWORD    "contraseña_mqtt"

#endif // CONFIG_H

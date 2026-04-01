#ifndef CONFIG_H
#define CONFIG_H

// ═══════════════════════════════════════════════════════════════════════════
// CONFIGURACIÓN WiFi y MQTT
// ═══════════════════════════════════════════════════════════════════════════
#define WIFI_SSID "JP"
#define WIFI_PASSWORD "551964jp"

#define WIFI_BACKUP_SSID "321onx_REP"
#define WIFI_BACKUP_PASSWORD "71671878"

#define WIFI_BACKUP2_SSID "3210NX"
#define WIFI_BACKUP2_PASSWORD "71671878"

#define MQTT_BROKER_URL "45.56.74.248"
#define MQTT_BROKER_PORT 1883
#define MQTT_USERNAME "fisica"
#define MQTT_PASSWORD "iotfisica"

// Tópicos MQTT
#define MQTT_TOPIC_PUB_FAST    "solar/status/fast"
#define MQTT_TOPIC_PUB_SLOW    "solar/status/slow"
#define MQTT_TOPIC_SUB_CMD     "solar/cmd"
#define MQTT_TOPIC_APP_STATUS  "solar/app/status"
#define MQTT_TOPIC_ESP_STATUS  "solar/esp32/status"
#define MQTT_TOPIC_DATA_RECORD "solar/data/record"
#define MQTT_TOPIC_DATA_ACK    "solar/data/ack"
#define MQTT_TOPIC_DATA_DONE   "solar/data/done"

// ═══════════════════════════════════════════════════════════════════════════
// UMBRALES DE SALUD (HEALTH SYSTEM)
// ═══════════════════════════════════════════════════════════════════════════
#define HEALTH_INA_WARN_COUNT   3
#define HEALTH_INA_FAIL_COUNT   10

#define HEALTH_GPS_WARN_SEC     30
#define HEALTH_GPS_FAIL_SEC     300

#define HEALTH_MQTT_WARN_COUNT  3

#define HEALTH_SPIFFS_MIN_FREE_PERC 20
#define HEALTH_SPIFFS_WARN_FREE_PERC 10
#define HEALTH_SPIFFS_FAIL_FREE_PERC 5

// ═══════════════════════════════════════════════════════════════════════════
// DATALOGGER y SPIFFS
// ═══════════════════════════════════════════════════════════════════════════
#define SPIFFS_PARTITION_LABEL "storage"
#define SPIFFS_BASE_PATH       "/spiffs"
#define DATALOG_DIR_PATH       "/spiffs/data"
#define DATALOG_FILE_PATH_FMT  "/spiffs/data/%04d-%02d-%02d.csv"

#define SPIFFS_DANGER_FREE_PERC 15 // Borrar antiguo si < 15%

// Configuración de la tarea de descarga
#define DATALOG_STACK_SIZE        4096
#define DATALOG_PRIORITY          2
#define DATALOG_ACK_TIMEOUT_MS    3000
#define DATALOG_MAX_RETRIES       3
#define DATALOG_BATCH_INTERVAL_MS 200

#endif // CONFIG_H

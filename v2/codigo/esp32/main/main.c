/*
 * Seguidor Solar - ESP32 (ESP-IDF)
 * Con WiFi + MQTT para control IoT
 *
 * Pines:
 *   Servo Azimut     -> GPIO19
 *   Servo Elevacion  -> GPIO18
 *   GPS RX (UART2)   -> GPIO16
 *   GPS TX           -> GPIO17 (no usado)
 */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>

#include "driver/i2c.h"
#include "driver/ledc.h"
#include "driver/uart.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_task_wdt.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "mqtt_client.h"
#include "esp_spiffs.h"

#include "config.h"
#include "health.h"

static const char *TAG = "SOLAR";

// ═══════════════════════════════════════════════════════════════════════════
// Configuración heredada (Sincronizada con config.h)
// ═══════════════════════════════════════════════════════════════════════════

// ─── Pines INA3221 ───────────────────────────────────────────────────────────
#define PIN_SDA 21
#define PIN_SCL 22

// ─── INA3221 ─────────────────────────────────────────────────────────────────
#define SHUNT_RESISTOR 0.1f  // Valor físico (Ohms) para calcular I = V/R
#define REG_INA_CONFIG 0x00  // Config: Modos, promedios y activación de canales
#define REG_CH1_SHUNT 0x01   // Ch1 Corriente (mV en Shunt)
#define REG_CH1_BUS 0x02     // Ch1 Voltaje (V respecto a GND)
#define REG_CH2_SHUNT 0x03   // Ch2 Corriente (mV en Shunt)
#define REG_CH2_BUS 0x04     // Ch2 Voltaje (V respecto a GND)
#define REG_MASK_ENABLE 0x0F // Estado: Flag de "Conversión Lista" y Alertas

// ─── Pines ───────────────────────────────────────────────────────────────────
#define PIN_SERVO_AZIMUT 19
#define PIN_SERVO_ELEVACION 18
#define PIN_GPS_RX 17
#define PIN_GPS_TX 16

// ─── PWM Servos (Control por Hardware LEDC) ──────────────────────────────────
#define SERVO_FREQ_HZ 50 // Frecuencia maestra
#define SERVO_PERIODO_US                                                       \
  (1000000 / SERVO_FREQ_HZ)                // Calculado: 20000us para 50Hz
#define SERVO_RESOLUTION LEDC_TIMER_16_BIT // 65,536 niveles (0.3us/step)
#define SERVO_MIN_PWM 500                  // Pulso para 0° (us)
#define SERVO_MAX_PWM 2500                 // Pulso para 180° (us)
#define SERVO_RANGO_DEG 180.0f             // Rango físico del servo en grados
#define FACTOR_CONVERSION                                                      \
  ((float)(SERVO_MAX_PWM - SERVO_MIN_PWM) / SERVO_RANGO_DEG)

// ─── GPS UART ────────────────────────────────────────────────────────────────
#define GPS_UART_NUM UART_NUM_2
#define GPS_BAUD 9600    // Velocidad de transmisión
#define GPS_BUF_SIZE 512 // Tamaño del buffer

// ─── Constantes matemáticas ──────────────────────────────────────────────────
#define PI 3.14159265358979323846
#define RAD(d) ((d) * (PI / 180.0)) // Conversión grados a radianes
#define DEG(r) ((r) * (180.0 / PI)) // Conversión radianes a grados

// ─── WiFi y Conectividad (Gestión de Eventos) ────────────────────────────────
#define WIFI_CONNECTED_BIT BIT0 // Flag: Conexión exitosa a la red
#define WIFI_FAIL_BIT BIT1      // Flag: Fallo tras agotar reintentos
#define WIFI_MAX_RETRIES 5      // Máximo de intentos de conexión

// ─── Variables de Conectividad ──────────────────────────────────────────────
static EventGroupHandle_t wifi_event_group;
static int wifi_retry_count = 0;
static esp_mqtt_client_handle_t mqtt_client = NULL;
static bool mqtt_conectado = false;

// ─── SISTEMA DE SALUD (Health Monitoring) ──────────────────────────────────
health_t g_health = {0};
SemaphoreHandle_t health_mutex = NULL;

static int64_t g_gps_ultimo_fix_us = 0;
static int g_mqtt_fallos_pub = 0;
static bool g_servos_limitados = false;
static esp_err_t g_servos_err_init = ESP_OK;
static int g_ina_fallos_lectura = 0;
static bool g_ina_presente = false;

health_state_t health_get_global(void) {
    if (health_mutex == NULL) return HEALTH_OK;
    if (xSemaphoreTake(health_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        health_state_t worse = g_health.ina;
        if (g_health.gps > worse) worse = g_health.gps;
        if (g_health.wifi > worse) worse = g_health.wifi;
        if (g_health.mqtt > worse) worse = g_health.mqtt;
        if (g_health.spiffs > worse) worse = g_health.spiffs;
        if (g_health.servos > worse) worse = g_health.servos;
        g_health.global = worse;
        xSemaphoreGive(health_mutex);
        return worse;
    }
    return g_health.global;
}

// ─── Estructuras de datos ────────────────────────────────────────────────────

// Datos en bruto (texto) recibidos del satélite (NMEA)
typedef struct {
  char hora[12];
  char fecha[10];
  char latitud[15];
  char lat_dir;
  char longitud[15];
  char lon_dir;
  uint8_t es_valido;
  uint8_t tiene_hora;
} GPS_Data_t;

// Posición solar y tiempo UTC (Greenwich) para cálculos astronómicos
typedef struct {
  double latitud_deg;
  double longitud_deg;
  int utc_ano;
  int utc_mes;
  int utc_dia;
  int utc_hora;
  int utc_min;
  int utc_seg;
  double elevacion;
  double azimut;
} SolarCalc_t;

// Reloj local ajustado según la zona horaria geográfica
typedef struct {
  int dia;
  int mes;
  int ano;
  int hora;
  int min;
  int seg;
  int zona_horaria;
} LocalTime_t;

/**
 * @brief ESTRUCTURA DE CONTROL Y ABSTRACCIÓN (PUENTE APP-HARDWARE)
 * ─────────────────────────────────────────────────────────────────────────────
 * Actúa como la capa de decisión que orquesta el comportamiento del seguidor,
 * reflejando los comandos externos (App/MQTT) sobre el hardware físico.
 *
 * JERARQUÍA DE PRIORIDAD (Lógica de Decisión):
 * 1. MODO SERVO INDEPENDIENTE Si 'servo_manual' = 1, ignora el sol y mueve los
 *    servos directamente a los ángulos fijos establecidos.
 * 2. MODO SIMULACIÓN: Si 'simulacion_activa' = 1, desacopla el tiempo real y
 *    usa un reloj interno acelerado (útil para pruebas de time-lapse).
 * 3. MODO MANUAL (Fechas/Coordenadas): Si las banderas manuales lo indican,
 *    el cálculo solar usa posiciones inyectadas por el usuario.
 * 4. MODO AUTOMÁTICO (Default): Sigue al sol con datos reales actuales del GPS.
 */
typedef struct {
  // --- Banderas de Selección (Flags de Estado) ---
  uint8_t usar_lat_manual; // 1: Ignora GPS, usa lat_manual
  uint8_t usar_lon_manual; // 1: Ignora GPS, usa lon_manual
  uint8_t usar_fecha_manual; // 1: Fija el cálculo en una fecha estática
                             // específica (dia_manual, mes_manual, anio_manual)
  uint8_t servo_manual; // 1: Control directo de motores (modo manual)

  // --- Valores de Configuración ---
  double lat_manual;  // Coordenada forzada desde la App
  double lon_manual;  // Coordenada forzada desde la App
  uint8_t dia_manual; // Fecha forzada para pruebas
  uint8_t mes_manual;
  uint16_t anio_manual;
  float ser_az_manual;        // Ángulo físico objetivo para modo manual
  float ser_el_manual;        // Ángulo físico objetivo para modo manual
} SystemControl_t;

// ─── Variables globales ──────────────────────────────────────────────────────

GPS_Data_t mi_gps = {0};
SolarCalc_t gps_solar = {0};
SolarCalc_t gps_solar_real = {0}; // posicion real del sol (siempre GPS)
LocalTime_t tiempo_local = {0};

SystemControl_t sys_ctrl = {.usar_lat_manual = 0,
                            .usar_lon_manual = 0,
                            .usar_fecha_manual = 0,
                            .dia_manual = 0,
                            .mes_manual = 0,
                            .anio_manual = 0,
                            .servo_manual = 0,
                            .ser_az_manual = 0.0f,
                            .ser_el_manual = 0.0f};

// PWM actual de los servos (para publicar en MQTT)
static float angulo_az_actual = 0.0f;
static float angulo_el_actual = 0.0f;
static int pwm_az_actual = 1500;
static int pwm_el_actual = 1500;

const uint8_t dias_por_mes[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

static QueueHandle_t
    cola_gps; // Manejador de la cola IPC para índices de buffer

// ─── Doble Buffer Estático (Ping-Pong) para UART GPS ─────────────────────────
#define GPS_BUFFER_SIZE 120
static char gps_ping_pong[2][GPS_BUFFER_SIZE];
static uint8_t buffer_escritura_idx = 0;

// ─── Últimas coordenadas GPS válidas ─────────────────────────────────────────
static double ultima_lat_valida = 0.0; // Última latitud válida del GPS
static double ultima_lon_valida = 0.0; // Última longitud válida del GPS
static double lat_nvs_guardada = 0.0;  // Para filtro de desgaste NVS
static double lon_nvs_guardada = 0.0;  // Para filtro de desgaste NVS

// ─── Modo búsqueda inicial ───────────────────────────────────────────────────
static bool en_modo_busqueda = true; // Modo búsqueda cuando no hay coordenadas
static float angulo_az_busqueda = 0.0f; // Ángulo azimutal para búsqueda
static int64_t ultimo_cambio_az = 0;    // Timestamp del último cambio de azimut
static bool servo_manual_anterior =
    false; // Para detectar cambio de modo manual a automático

// ─── Modo parking ────────────────────────────────────────────────────────────
static bool en_modo_parking = false; // Indica si está en modo parking (noche)

// ─── Movimiento gradual de servos ────────────────────────────────────────────
static float pos_actual_az = 0.0f;          // Posición actual azimut (°)
static float pos_actual_el = 0.0f;          // Posición actual elevación (°)
static volatile float pos_obj_az = 0.0f;    // Posición objetivo azimut (°)
static volatile float pos_obj_el = 0.0f;    // Posición objetivo elevación (°)
static esp_timer_handle_t timer_movimiento; // Timer para movimiento gradual

// ─── Variables INA3221 ───────────────────────────────────────────────────────
static uint8_t INA3221_ADDR = 0x40; // Dirección I2C del INA3221
static bool ina_ch1_valido =
    false; // Indica si la lectura del canal 1 es válida
static float ina_ch1_v = 0.0f;
static float ina_ch1_i = 0.0f;
static float ina_ch1_p = 0.0f;
static bool ina_ch2_valido = false;
static float ina_ch2_v = 0.0f;
static float ina_ch2_i = 0.0f;
static float ina_ch2_p = 0.0f;

// ─── CAPTURA Y STREAMING DE CALIBRACIÓN (BATCH MASIVO) ───────────────────────
#define BATCH_GOAL 500           // Meta de muestras para el análisis masivo
#define BATCH_MAX 1000           // Aumentamos buffer para evitar desbordes
#define CHUNK_SIZE 3             // Envío ágil de 3 en 3
static float batch_ch1[BATCH_MAX];
static float batch_ch2[BATCH_MAX];
static int batch_count = 0;
static int batch_id = 0;
static uint32_t session_id = 0;  // ID único para este arranque
static float last_batch_p1 = -100.0f;

// Estado de la transmisión segmentada
static bool streaming_activo = false;
static int streaming_indice = 0;
static int streaming_parte = 0;
static int64_t ultimo_streaming_us = 0;
static bool streaming_manual_trigger = false;  // Disparador para envío parcial inmediato

// ─── FILTRO DIGITAL DE DOBLE ETAPA (INA3221) ─────────────────────────────────
// Estructura para el procesamiento y estabilización de telemetría de potencia.
// Actúa como un filtro pasa-bajos para mitigar transitorios eléctricos y ruido:
// - Etapa 1 (Corto Plazo): Integra y promedia lecturas de alta frecuencia en
//   ventanas discretas de 5 minutos, atenuando picos instantáneos.
// - Etapa 2 (Largo Plazo): Alimenta un buffer circular (Media Móvil) de 288
//   muestras (24h) para obtener la tendencia de producción diaria constante.
// Incluye heurísticas de validación para descartar mediciones estáticas
// en caso de fallos en el bus I2C o bloqueos del ADC.

#define INA_VENTANA_MOVIL 288   // muestras en la media móvil diaria
#define INA_INTERVALO_MS 300000 // 5 minutos en ms

typedef struct {
  float buffer[INA_VENTANA_MOVIL]; // buffer circular (para otros futuros análisis)
  int indice;                      // posición actual
  int count;                       // muestras acumuladas
  float suma;                      // suma acumulada del buffer
  float energia_acumulada_mwh;     // Integral absoluta ascendente de energía (mWh)
  float ultimo_promedio_5min;      // para detectar congelado
  // acumulador de 5 minutos
  float acum_p;             // suma de potencias válidas en 5 min
  int acum_n;               // cantidad de muestras válidas en 5 min
  float ultimo_v;           // último voltaje válido 
  float ultimo_i;           // última corriente válida
  int conteo_congelado;     // Contador para descartar bloqueos de hardware lógicos
  int64_t ultimo_tick_5min; // timestamp del último ciclo de 5 min
  int dia_actual;           // para reinicio a medianoche
} INA_Promedio_t;

static INA_Promedio_t prom_ch1 = {0};
static INA_Promedio_t prom_ch2 = {0};

// ─── DATALOGGER SPIFFS (REGISTRO HISTÓRICO) ──────────────────────────────────
#define DATALOG_ACK_TIMEOUT_MS    3000    // espera máxima por ACK (según req)
#define DATALOG_MAX_RETRIES       3       // reintentos por registro
#define DATALOG_BATCH_INTERVAL_MS 200     // delay entre registros consecutivos (según req)
#define DATALOG_STACK_SIZE        4096
#define DATALOG_PRIORITY          2

typedef enum {
    DATALOG_OK,
    DATALOG_DISABLED,
    DATALOG_MOUNT_FAILED,
} datalog_state_t;

static datalog_state_t g_datalog_state = DATALOG_DISABLED;
static bool g_descarga_en_curso = false;
static char g_last_ack_id[12] = {0};
static QueueHandle_t g_cola_ack = NULL;
static TaskHandle_t tarea_servos_h = NULL;

// ─── Prototipos ──────────────────────────────────────────────────────────────
static esp_err_t pwm_init(void);
static uint32_t us_to_duty(int us);
static int constrain_pwm(int valor);
static void Actualizar_Servos(void);
static void Actualizar_Servos_Manual(float az_fisico, float el_fisico);
static void Actualizar_Servos_Busqueda(float az_fisico, float el_fisico);
static void gps_uart_init(void);
static void Procesar_Trama_GPRMC(char *buffer);
static void Procesar_Tiempo_GPS(void);
static void Procesar_Ubicacion_GPS(void);
static void Calcular_Posicion_Solar(SolarCalc_t *solar);
static void Calcular_Hora_Local(LocalTime_t *local);
static uint8_t es_bisiesto(int ano);
static void Actualizar_Coordenadas_Calculo(void);
static void Gestionar_Tiempo_Sistema(void);
static void Incrementar_Tiempo_Simulado(LocalTime_t *t, int segundos);
//static void Publicar_Estado_MQTT(void);
static void Publicar_Estado_Rapido_MQTT(void);
static void Publicar_Estado_Lento_MQTT(void);
static void Cargar_Configuracion_NVS(void);
static void Guardar_Configuracion_NVS(void);
static void Procesar_Comando_MQTT(const char *datos, int len);
static void wifi_init(void);
static void mqtt_init(void);
static void tarea_gps(void *arg);
static void tarea_principal(void *arg);
static void timer_movimiento_callback(void *arg);
static void Publicar_Batch_Potencia_MQTT(void);
static void Datalogger_Init(void);
static void Datalogger_Escribir_Punto(void);
static void Datalogger_Tarea_Descarga(void *arg);
static int Datalogger_Contar_Pendientes(void);
static void Datalogger_Truncar_Corrupto(const char* path);
static void Procesar_Status_App(const char *datos, int len);
static void Procesar_ACK_Datalogger(const char *datos, int len);

// ─── Prototipos INA3221 ──────────────────────────────────────────────────────
static esp_err_t ina3221_write_reg(uint8_t reg, uint16_t value);
static esp_err_t ina3221_read_reg(uint8_t reg, int16_t *value);
static bool ina3221_detectar(void);
static esp_err_t ina3221_init(void);
static esp_err_t ina3221_conversion_lista(bool *ready);
static bool ina3221_leer_canal(int canal, float *voltaje, float *corriente);
static void ina_actualizar_promedio(INA_Promedio_t *p, float v, float i,
                                    bool valido);
static void tarea_medicion_ina(void *arg);
static void Datalogger_Limpiar_Espacio(void);

// ─── Punto de entrada ────────────────────────────────────────────────────────

void app_main(void) {
  health_mutex = xSemaphoreCreateMutex();
  ESP_LOGI(TAG, "Iniciando Seguidor Solar ESP32");

  // 1. ANÁLOGO A HAL_Init() + SystemClock_Config()
  // ────────────────────────────────────────────────────────
  // Inicializa el sistema de memoria flash interna (NVS).
  // Es obligatorio porque las librerías Core de RF (WiFi/BT)
  // del ESP32 guardan aquí su calibración física y MAC address.
  esp_err_t ret = nvs_flash_init();
  if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
      ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    ESP_ERROR_CHECK(nvs_flash_erase());
    ret = nvs_flash_init();
  }
  ESP_ERROR_CHECK(ret);

  // 2. GUARDIÁN DEL SISTEMA (Watchdog Timer)
  // ────────────────────────────────────────────────────────
  // Configura la supervisión de tareas para detectar bloqueos.
  // Se desactiva el reinicio por Panic para cumplir con el requisito
  // de disponibilidad continua.
  esp_task_wdt_config_t twdt_config = {
      .timeout_ms = 20000, // Aumentado a 20s para mayor tolerancia
      .idle_core_mask = 0,
      .trigger_panic = false // NO reiniciar automáticamente el sistema
  };
  esp_task_wdt_reconfigure(&twdt_config);

  // 3. ASIGNACIÓN DE RECURSOS DE FREE-RTOS (IPC)
  // ────────────────────────────────────────────────────────
  // Se crea una cola de longitud 2 para almacenar exclusivamente el índice
  // numérico del buffer ping-pong, evadiendo la interacción con el Heap.
  cola_gps = xQueueCreate(2, sizeof(uint8_t));

  // 4. ANÁLOGO AL MX_*_Init() (Configuración de Periféricos)
  // ────────────────────────────────────────────────────────
  // Configura el PWM por hardware (LEDC) para los servomotores
  g_servos_err_init = pwm_init();

  // Timer para movimiento gradual (Interrupción de hardware a 100ms)
  const esp_timer_create_args_t timer_args = {
      .callback = &timer_movimiento_callback, .name = "timer_movimiento"};
  ESP_ERROR_CHECK(esp_timer_create(&timer_args, &timer_movimiento));
  ESP_ERROR_CHECK(esp_timer_start_periodic(
      timer_movimiento, 20000)); // 20ms (50Hz) para alta fluidez

  // Inicializar posiciones actuales
  pos_actual_az = 0.0f;
  pos_actual_el = 80.0f; // Empezar en elevación 80° para búsqueda
  pos_obj_az = 0.0f;
  pos_obj_el = 80.0f;

  // Configura la interfaz serial
  gps_uart_init();

  // I2C e inicialización del bus
  i2c_config_t i2c_conf = {.mode = I2C_MODE_MASTER,
                           .sda_io_num = PIN_SDA,
                           .scl_io_num = PIN_SCL,
                           .sda_pullup_en = GPIO_PULLUP_ENABLE,
                           .scl_pullup_en = GPIO_PULLUP_ENABLE,
                           .master.clk_speed = 100000};
  i2c_param_config(I2C_NUM_0, &i2c_conf);
  i2c_driver_install(I2C_NUM_0, I2C_MODE_MASTER, 0, 0, 0);

  // 5. RESTAURACIÓN DE ESTADO Y AUTODIAGNÓSTICO
  // ────────────────────────────────────────────────────────
  // Rescata la última ubicación conocida para no perder la
  // traza solar si hubo un corte de energía rápido.
  Cargar_Configuracion_NVS();

  // El INA3221 se gestionará mediante una máquina de estados de autocuración
  // dentro de su propia tarea, permitiendo reconexión en caliente.
  xTaskCreatePinnedToCore(tarea_medicion_ina, "tarea_ina", 4096, NULL, 3, NULL,
                          1);

  // 6. INICIALIZACIÓN DEL STACK DE RED (OS / LwIP)
  // ────────────────────────────────────────────────────────
  wifi_init();
  mqtt_init();
  
  g_cola_ack = xQueueCreate(1, sizeof(g_last_ack_id));
  session_id = esp_random() % 10000;

  // 7. ARRANQUE DEL SCHEDULER (Multitasking Base)
  // ────────────────────────────────────────────────────────
  // Anclamos nuestra lógica pesada al Core 1. Esto deja el Core 0
  // exclusivamente libre para procesar eventos invisibles de
  // la antena WiFi sin robarle rendimiento a la cinemática.
  xTaskCreatePinnedToCore(tarea_gps, "tarea_gps", 4096, NULL, 4, NULL,
                          1); // Prioridad bajada a 4, Core 1
  xTaskCreatePinnedToCore(tarea_principal, "tarea_main", 8192, NULL, 5, NULL,
                          1); // Core 1
  xTaskCreatePinnedToCore(tarea_seguimiento, "tarea_control", 4096, NULL, 5, &tarea_servos_h, 1);
  ESP_LOGI(TAG, "Sistema iniciado");
}

// ─── RECEPCIÓN Y BÚFER DE DOBLE ETAPA (GPS UART) ─────────────────────────────
// Estructura para la captura asíncrona y ensamblaje de tramas NMEA-0183 a 1 Hz.
// Actúa como recolector no bloqueante entre el periférico y la lógica central:
// - Etapa 1 (Lectura Bloqueante): Extracción de datos desde el Ring Buffer
//   alimentado por la ISR del UART, utilizando timeouts del RTOS para ceder la
//   CPU.
// - Etapa 2 (Ping-Pong Buffer): Agrupa caracteres en dos arreglos estáticos
//   alternos de 120 bytes. Al detectar el terminador ('\n'), sella la cadena,
//   delega transacciones vía IPC (xQueueSend por valor) y conmuta memoria.
// Incluye heurísticas preventivas (Buffer Overrun) para salvaguardar la
// integridad de memoria frente a posibles corrupciones o ráfagas extensas.

static void tarea_gps(void *arg) {
  esp_task_wdt_add(NULL);
  uint8_t byte;
  static int rx_index = 0;

  while (1) {
    esp_task_wdt_reset(); // Alimentar al perro al inicio de cada ciclo
    // Extracción bloqueante desde el Ring Buffer del UART gestionado por ISR
    int len = uart_read_bytes(GPS_UART_NUM, &byte, 1, pdMS_TO_TICKS(100));
    if (len <= 0)
      continue;

    // Transicionado a fin de trama (Line Feed NMEA)
    if (byte == '\n') {
      gps_ping_pong[buffer_escritura_idx][rx_index] = '\0';
      if (rx_index > 0) {
        uint8_t idx_listo = buffer_escritura_idx;
        // Inyectar a la estructura IPC asíncrona por paso de valor
        if (xQueueSend(cola_gps, &idx_listo, 0) == pdTRUE) {
          // Conmuta la memoria activa (XOR semántico) sólo tras la validación
          // de recepción
          buffer_escritura_idx =
              (buffer_escritura_idx + 1) % 2; // Alterna el buffer
        }
      }
      rx_index = 0;
    } else {
      if (rx_index < (GPS_BUFFER_SIZE - 1)) {
        gps_ping_pong[buffer_escritura_idx][rx_index++] =
            (char)byte; // Acumula caracteres
      } else {
        rx_index = 0; // Descarte íntegro por Buffer Overrun defensivo
      }
    }
  }
}

// ─── ORQUESTACIÓN LÓGICA Y CONTROL CINEMÁTICO (TAREA PRINCIPAL)
// ──────────────── Esta tarea opera como el Consumidor Central en la
// arquitectura de paso de mensajes del sistema. Coordina la transformación de
// datos NMEA crudos en acciones mecánicas y despacho de telemetría:
// - Etapa 1 (Parsing): Deserialización de tramas NMEA-0183 y cálculo de tiempo
// local.
// - Etapa 2 (Fusión): Cálculo de algoritmos astronómicos para posición solar
// real.
// - Etapa 3 (Control): Máquina de estados para modos Manual, Búsqueda y
// Automático.
//   Incluye validación de integridad temporal (>2024) para el inicio de
//   tracking.
// - Etapa 4 (Telemetría): Serialización JSON y publicación asíncrona vía MQTT.

static void tarea_principal(void *arg) {
  esp_task_wdt_add(NULL); // Suscribir esta tarea al Watchdog
  
  // Requerimiento: Montar SPIFFS al inicio en la tarea_principal
  Datalogger_Init();

  uint8_t idx_trama;
  uint32_t ticks_sin_gps = 0;
  int64_t ahora_us = 0;
  int64_t ultimo_pub_lento = 0;
  int64_t ultimo_pub_rapido = 0;

  while (1) {
    esp_task_wdt_reset(); // Heartbeat para el Task Watchdog Timer (TWDT)
    ahora_us = esp_timer_get_time();

    // 1. RECEPCIÓN GPS (MÁX 100ms)
    // El timeout de 100ms garantiza que el resto del bucle se ejecute a ~10Hz
    // incluso si el GPS no envía datos (heartbeat de control).
    if (xQueueReceive(cola_gps, &idx_trama, pdMS_TO_TICKS(100)) == pdTRUE) {
      ticks_sin_gps = 0;
      char *trama = gps_ping_pong[idx_trama];

      if (strncmp(trama, "$GPRMC", 6) == 0) {
        Procesar_Trama_GPRMC(trama);
        Procesar_Tiempo_GPS();

        if (mi_gps.es_valido) {
          Procesar_Ubicacion_GPS();
        }

        Calcular_Posicion_Solar(&gps_solar_real);
      }
    } else {
      ticks_sin_gps++;
    }

    // 2. LÓGICA DE CONTROL Y MOVIMIENTO (SIEMPRE A 10Hz)
    // Sincronización de modo: Si entramos a modo manual, capturamos posición
    // actual para evitar saltos. Pero solo si no fue forzado por MQTT (evita
    // carrera)
    if (sys_ctrl.servo_manual && !servo_manual_anterior) {
      sys_ctrl.ser_az_manual = pos_obj_az;
      sys_ctrl.ser_el_manual = pos_obj_el;
    }
    servo_manual_anterior = sys_ctrl.servo_manual;

    if (sys_ctrl.servo_manual) {
      Actualizar_Servos_Manual(sys_ctrl.ser_az_manual, sys_ctrl.ser_el_manual);
    } else {
      Actualizar_Coordenadas_Calculo();
      Gestionar_Tiempo_Sistema();
      
      gps_solar.utc_ano = tiempo_local.ano;
      gps_solar.utc_mes = tiempo_local.mes;
      gps_solar.utc_dia = tiempo_local.dia;
      gps_solar.utc_hora = tiempo_local.hora - (int)(round(gps_solar.longitud_deg / 15.0)); // Inverso burdo para cálculo
      // En realidad Calcular_Posicion_Solar debería directamente usar tiempo local si se adapta,
      // pero mantendremos la compatibilidad con el motor actual.

      bool tiene_ubicacion = (ultima_lat_valida != 0.0 || sys_ctrl.usar_lat_manual);
      bool tiempo_sincronizado = (tiempo_local.ano >= 2024 || 
                                 sys_ctrl.usar_fecha_manual);

      if (en_modo_busqueda && tiene_ubicacion && tiempo_sincronizado) {
        en_modo_busqueda = false;
        ESP_LOGI(TAG, "Integridad verificada (Manual o GPS). Saliendo de búsqueda...");
      }

      if (en_modo_busqueda || !tiempo_sincronizado) {
        int64_t t_ahora = esp_timer_get_time();
        if (t_ahora - ultimo_cambio_az > 10000000LL) {
          angulo_az_busqueda += 45.0f;
          if (angulo_az_busqueda > 90.0f)
            angulo_az_busqueda = -90.0f;
          ultimo_cambio_az = t_ahora;
        }
        Actualizar_Servos_Busqueda(angulo_az_busqueda, 60.0f);
      } else {
        Calcular_Posicion_Solar(&gps_solar);
        Actualizar_Servos();
      }
    }

    // 3. TELEMETRÍA MULTINIVEL (4Hz / 1Hz)
    if (mqtt_conectado) {
      if (ahora_us - ultimo_pub_rapido >= 250000LL) {
        Publicar_Estado_Rapido_MQTT(); // Restaurado a 4Hz (250ms) para fluidez
        ultimo_pub_rapido = ahora_us;
      }

      if (ahora_us - ultimo_pub_lento >= 1000000LL) {
        Publicar_Estado_Lento_MQTT(); // Se dispara cada 1 segundo
        ultimo_pub_lento = ahora_us;
        ticks_sin_gps = 0; // Heartbeat
      }

      // 4. MOTOR DE STREAMING (ENVÍO GRADUAL / SEGMENTADO)
      if (mqtt_conectado && (ahora_us - ultimo_streaming_us >= 500000LL)) {
          bool hay_datos = (batch_count - streaming_indice >= CHUNK_SIZE);
          bool meta_voluntaria = streaming_manual_trigger;
          bool meta_automatica = (batch_count >= BATCH_GOAL);

          // Si hay suficientes datos para una ráfaga, o se alcanzó la meta (automática o manual)
          if (hay_datos || meta_voluntaria || meta_automatica) {
              Publicar_Batch_Potencia_MQTT(); // Envía el siguiente fragmento
              ultimo_streaming_us = ahora_us;
          }
      }
    } else {
      // Si no hay MQTT, el heartbeat se mide en ciclos de 100ms (50 = 5s)
      if (ticks_sin_gps >= 50) {
        ticks_sin_gps = 0;
        ESP_LOGW(TAG, "Sistema operando en modo autónomo (Sin GPS/MQTT)");
      }
    }
  }
}

// ─── GESTIÓN DE CICLO DE CONVERSIÓN Y TELEMETRÍA (TAREA INA3221) ─────────────
// Implementa el bucle de adquisición síncrona coordinado con el ADC del sensor:
// - Control de Flujo: Ejecuta un polling de 10 Hz condicionado por la señal de
//   hardware 'Conversion Ready' para mitigar la lectura de datos estocásticos.
// - Integridad de Datos: Mediante condicionales de validación I2C assegura que
//   solo tramas eléctricas íntegras alimenten al filtro de doble etapa.

static void tarea_medicion_ina(void *arg) {
  float v1, i1, v2, i2;

  // Inicialización de registros temporales para integración de promedios
  prom_ch1.ultimo_tick_5min = xTaskGetTickCount() * portTICK_PERIOD_MS;
  prom_ch2.ultimo_tick_5min = xTaskGetTickCount() * portTICK_PERIOD_MS;
  prom_ch1.ultimo_promedio_5min = -1.0f;
  prom_ch2.ultimo_promedio_5min = -1.0f;
  prom_ch1.dia_actual = -1;
  prom_ch2.dia_actual = -1;

  esp_task_wdt_add(NULL);

  while (1) {
    esp_task_wdt_reset();

    if (!g_ina_presente) {
      if (ina3221_detectar()) {
        ina3221_init();
        g_ina_presente = true;
        g_ina_fallos_lectura = 0;
        ESP_LOGI("INA", "Sensor detectado y configurado exitosamente.");
      } else {
        vTaskDelay(pdMS_TO_TICKS(5000));
        continue;
      }
    }

    vTaskDelay(pdMS_TO_TICKS(100));
        
    if (g_descarga_en_curso && gps_solar.elevacion < 5.0) {
        vTaskDelay(pdMS_TO_TICKS(900));
    }

    bool conversion_lista = false;
    esp_err_t err_ready = ina3221_conversion_lista(&conversion_lista);

    if (err_ready != ESP_OK) {
      g_ina_fallos_lectura++;
      if (g_ina_fallos_lectura > 50) {
        g_ina_presente = false;
        ina_ch1_valido = false;
        ina_ch2_valido = false;
        ESP_LOGE("INA", "Fallo de comunicación I2C.");
      }
    } else if (conversion_lista) {
        bool ch1_ok = ina3221_leer_canal(1, &v1, &i1);
        bool ch2_ok = ina3221_leer_canal(2, &v2, &i2);

        if (ch1_ok || ch2_ok) {
            g_ina_fallos_lectura = 0;
            g_ina_presente = true;
            ina_ch1_valido = ch1_ok;
            ina_ch1_v = v1; ina_ch1_i = i1; ina_ch1_p = v1 * i1;
            ina_ch2_valido = ch2_ok;
            ina_ch2_v = v2; ina_ch2_i = i2; ina_ch2_p = v2 * i2;
            ina_actualizar_promedio(&prom_ch1, v1, i1, ch1_ok);
            ina_actualizar_promedio(&prom_ch2, v2, i2, ch2_ok);
        } else {
            g_ina_fallos_lectura++;
        }
    }

    // Actualizar diagnóstico INA
    uint8_t h_ina = 0;
    if (!g_ina_presente || g_ina_fallos_lectura >= HEALTH_INA_FAIL_COUNT) h_ina = 2;
    else if (g_ina_fallos_lectura >= HEALTH_INA_WARN_COUNT) h_ina = 1;

    if (xSemaphoreTake(g_state_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
        g_health.ina = h_ina;
        xSemaphoreGive(g_state_mutex);
    }
  }
}

        
// ─── PWM Servos ──────────────────────────────────────────────────────────────

// Traduce el ancho de pulso en microsegundos al valor binario del registro del
// periférico LEDC (PWM). Utiliza un casteo a 64 bits para evitar
// desbordamientos durante la normalización a la resolución de hardware (16
// bits).

static uint32_t us_to_duty(int us) {
  return (uint32_t)((uint64_t)us * ((1 << 16) - 1) / SERVO_PERIODO_US);
}

// Garantiza la seguridad de los servos, imponiendo limites a los
// valores que se les pide llegar para evitar daños al mecanismo.
static int constrain_pwm(int valor) {
  if (valor < SERVO_MIN_PWM)
    return SERVO_MIN_PWM;
  if (valor > SERVO_MAX_PWM)
    return SERVO_MAX_PWM;
  return valor;
}

static esp_err_t pwm_init(void) {
  ledc_timer_config_t timer = {.speed_mode = LEDC_LOW_SPEED_MODE,
                               .timer_num = LEDC_TIMER_0,
                               .duty_resolution = SERVO_RESOLUTION,
                               .freq_hz = SERVO_FREQ_HZ,
                               .clk_cfg = LEDC_AUTO_CLK};
  esp_err_t err = ledc_timer_config(&timer);
  if (err != ESP_OK) return err;

  ledc_channel_config_t ch_az = {.gpio_num = PIN_SERVO_AZIMUT,
                                 .speed_mode = LEDC_LOW_SPEED_MODE,
                                 .channel = LEDC_CHANNEL_0,
                                 .timer_sel = LEDC_TIMER_0,
                                 .duty = us_to_duty(1500),
                                 .hpoint = 0,
                                 .flags.output_invert = 1};
  err = ledc_channel_config(&ch_az);
  if (err != ESP_OK) return err;

  ledc_channel_config_t ch_el = {.gpio_num = PIN_SERVO_ELEVACION,
                                 .speed_mode = LEDC_LOW_SPEED_MODE,
                                 .channel = LEDC_CHANNEL_1,
                                 .timer_sel = LEDC_TIMER_0,
                                 .duty = us_to_duty(1500),
                                 .hpoint = 0,
                                 .flags.output_invert = 1};
  return ledc_channel_config(&ch_el);
}


// Se encarga de asignar el destino de los motores, con base en la posicion
// actual del sol. Unicamente para modo GPS, modo en que solo se usan los
// datos del GPS
static void Actualizar_Servos(void) {
  float az_real = (float)gps_solar.azimut;
  float el_real = (float)gps_solar.elevacion;

  float az_objetivo_base = 0.0f;
  float el_angulo_fisico = 0.0f;

  if (az_real >= 90.0f && az_real <= 270.0f) {
    // Caso directo: sol entre Este y Oeste pasando por el Sur. Los angulos
    // de los servos coinciden con los del sol
    az_objetivo_base = az_real;
    el_angulo_fisico = el_real;
  } else {
    // Caso Backflip: Cuando el sol orbita por el Norte, se compensa la
    // limitación mecánica rotando la base 180° (azimut opuesto) e invirtiendo
    // el eje de inclinación de elevación (180° - el_real). Esto permite el
    // seguimiento continuo.
    if (az_real < 90.0f)
      az_objetivo_base = az_real + 180.0f; // Ej: 45(NE) -> 225(SO)
    else
      az_objetivo_base = az_real - 180.0f; // Ej: 350(NO) -> 170(SE)

    el_angulo_fisico = 180.0f - el_real; // Invertir elevación
  }

  // Modo parking: si elevación <= 0° (noche) y no en modo manual, elevar a 90°
  // para evitar fatiga en las piezas mecanicas
  if (el_real <= 0.0f && !sys_ctrl.servo_manual) {
    el_angulo_fisico = 90.0f;
    en_modo_parking = true;
  } else {
    en_modo_parking = false;
  }

  // limitamos el azimut a un rango de -90° a 90°
  float az_fisico = az_objetivo_base - 180.0f;
  bool limited = false;
  
  if (az_fisico < -90.0f) { az_fisico = -90.0f; limited = true; }
  if (az_fisico > 90.0f) { az_fisico = 90.0f; limited = true; }
  if (el_angulo_fisico < 0.0f) { el_angulo_fisico = 0.0f; limited = true; }
  if (el_angulo_fisico > 180.0f) { el_angulo_fisico = 180.0f; limited = true; }

  g_servos_limitados = limited;

  // Setear objetivos para movimiento gradual
  pos_obj_az = az_fisico;
  pos_obj_el = el_angulo_fisico;
}

// Modo independiente: mueve los servos directamente al ángulo físico indicado
// az_fisico: -90° a 90° (ángulo físico del servo de azimut)
// el_fisico: 0-180° (ángulo físico del servo de elevación) 90° apunta al cenit
static void Actualizar_Servos_Manual(float az_fisico, float el_fisico) {
  if (az_fisico < -90.0f)
    az_fisico = -90.0f;
  if (az_fisico > 90.0f)
    az_fisico = 90.0f;
  if (el_fisico < 0.0f)
    el_fisico = 0.0f;
  if (el_fisico > 180.0f)
    el_fisico = 180.0f;

  // Setear objetivos para movimiento gradual
  pos_obj_az = az_fisico;
  pos_obj_el = el_fisico;
}

// ─── Modo búsqueda: elevación 80°, azimut rotando ───────────────────────────
// az_fisico: -90° a 90° (ángulo físico del servo de azimut)
// el_fisico: 0-180° (ángulo físico del servo de elevación)
static void Actualizar_Servos_Busqueda(float az_fisico, float el_fisico) {
  if (az_fisico < -90.0f)
    az_fisico = -90.0f;
  if (az_fisico > 90.0f)
    az_fisico = 90.0f;
  if (el_fisico < 0.0f)
    el_fisico = 0.0f;
  if (el_fisico > 180.0f)
    el_fisico = 180.0f;

  // Setear objetivos para movimiento gradual
  pos_obj_az = az_fisico;
  pos_obj_el = el_fisico;
}

// ─── GESTIÓN DE MOVIMIENTO GRADUAL (SUAVIZADO CINEMÁTICO) ────────────────────
// Esta función se ejecuta por interrupción de timer a 50Hz (cada 20ms).
// Implementa una rampa de velocidad constante para evitar arranques bruscos
// que puedan dañar la estructura o aflojar los acoples mecánicos.
static void timer_movimiento_callback(void *arg) {
  // Requerimiento: Durante descarga en modo parking (elev < 5), suspender servos
  if (g_descarga_en_curso && gps_solar.elevacion < 5.0) {
      return;
  }

  // CONFIGURACIÓN DE MOVIMIENTO: 15°/s (Pasos más decisivos para evitar buzz)
  const float VELOCIDAD_GRADOS_POR_SEG = 15.0f;
  const float DT = 0.02f; // Diferencial de tiempo fijo (20ms/50Hz)
  const float PASO_MAX = VELOCIDAD_GRADOS_POR_SEG * DT; // Delta: 0.3° por ciclo

  // ZONA MUERTA (Hysteresis): Aumentada a 0.4° para eliminar el "caza de
  // posición"
  const float DEAD_BAND = 0.4f;

  // 1. PROCESAMIENTO EJE AZIMUT
  float diff_az = pos_obj_az - pos_actual_az;
  if (fabsf(diff_az) >= DEAD_BAND) {
    float paso_az =
        fminf(PASO_MAX, fabsf(diff_az)) * (diff_az > 0 ? 1.0f : -1.0f);
    pos_actual_az += paso_az;

    // Cálculo del pulso PWM con redondeo al entero más cercano (evita
    // flickering)
    float pwm_az_calc = 1500.0f - (pos_actual_az * FACTOR_CONVERSION);
    pwm_az_actual = constrain_pwm((int)roundf(pwm_az_calc));

    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0,
                  us_to_duty(pwm_az_actual));
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
  }

  // 2. PROCESAMIENTO EJE ELEVACIÓN
  float diff_el = pos_obj_el - pos_actual_el;
  if (fabsf(diff_el) >= DEAD_BAND) {
    float paso_el =
        fminf(PASO_MAX, fabsf(diff_el)) * (diff_el > 0 ? 1.0f : -1.0f);
    pos_actual_el += paso_el;

    float pwm_el_calc = 500.0f + (pos_actual_el * FACTOR_CONVERSION);
    pwm_el_actual = constrain_pwm((int)roundf(pwm_el_calc));

    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_1,
                  us_to_duty(pwm_el_actual));
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_1);
  }

  // Sincronizar reporte MQTT
  angulo_az_actual = pos_actual_az;
  angulo_el_actual = pos_actual_el;
}

// ─── GPS ─────────────────────────────────────────────────────────────────────
// inicializacion y carga de configuracion del GPS
static void gps_uart_init(void) {
  uart_config_t uart_cfg = {.baud_rate = GPS_BAUD,
                            .data_bits = UART_DATA_8_BITS,
                            .parity = UART_PARITY_DISABLE,
                            .stop_bits = UART_STOP_BITS_1,
                            .flow_ctrl = UART_HW_FLOWCTRL_DISABLE};
  uart_param_config(GPS_UART_NUM, &uart_cfg);
  uart_set_pin(GPS_UART_NUM, PIN_GPS_TX, PIN_GPS_RX, UART_PIN_NO_CHANGE,
               UART_PIN_NO_CHANGE);
  uart_driver_install(GPS_UART_NUM, GPS_BUF_SIZE * 2, 0, 0, NULL, 0);
}

// Función auxiliar para validar la integridad de la trama NMEA mediante el
// checksum XOR
static bool Validar_Checksum_NMEA(const char *buffer) {
  if (buffer[0] != '$')
    return false;

  uint8_t checksum_calculado = 0;
  const char *p = buffer + 1; // Saltar el símbolo '$'

  // El checksum en NMEA es el XOR de todos los caracteres entre '$' y '*'
  while (*p && *p != '*') {
    checksum_calculado ^= *p;
    p++;
  }

  // Si no se encontró el asterisco, la trama está incompleta
  if (*p != '*')
    return false;

  p++; // Saltar el '*'

  // Convertir el checksum hexadecimal de la trama a entero
  char *endptr;
  uint8_t checksum_recibido = (uint8_t)strtol(p, &endptr, 16);

  // Es válido si el cálculo coincide con el reporte del GPS
  return (checksum_calculado == checksum_recibido);
}

static void Procesar_Trama_GPRMC(char *buffer) {
  // 1. Filtrado rápido: Solo nos interesan tramas GPRMC
  if (strncmp(buffer, "$GPRMC", 6) != 0)
    return;

  // 2. Validación de Integridad (Checksum): Protege contra ruidos eléctricos
  // causados por los motores o cables largos que podrían corromper caracteres
  if (!Validar_Checksum_NMEA(buffer)) {
    ESP_LOGW("GPS", "Trama descartada por Checksum inválido.");
    return;
  }

  char *start = buffer; // puntero al inicio de la trama
  char *end;            // puntero al final de la trama
  int campo = 0;        // contador de campos

  // Bucle de segmentación: itera sobre cada coma del mensaje GPS
  while ((end = strpbrk(start, ",*")) != NULL) {
    char backup_char = *end;
    *end = '\0';

    if (strlen(start) > 0) {
      switch (campo) {
      case 1: // Campo: Hora UTC (HHMMSS.SS)
        strncpy(mi_gps.hora, start, sizeof(mi_gps.hora) - 1);
        break;
      case 2: // Campo: Status (A=Valid, V=Nav receptor warning)
        // Flag crítica: Determina si el sistema puede confiar en las
        // coordenadas
        mi_gps.es_valido = (start[0] == 'A') ? 1 : 0;
        break;
      case 3: // Campo: Latitud
        strncpy(mi_gps.latitud, start, sizeof(mi_gps.latitud) - 1);
        break;
      case 4: // Campo: Hemisferio Latitud (N/S)
        mi_gps.lat_dir = start[0];
        break;
      case 5: // Campo: Longitud
        strncpy(mi_gps.longitud, start, sizeof(mi_gps.longitud) - 1);
        break;
      case 6: // Campo: Hemisferio Longitud (E/W)
        mi_gps.lon_dir = start[0];
        break;
      case 9: // Campo: Fecha (DDMMYY)
        strncpy(mi_gps.fecha, start, sizeof(mi_gps.fecha) - 1);
        break;
      }
    }

    *end = backup_char;
    if (*end == '*')
      break;
    start = end + 1;
    campo++;
  }
}

// ─── SINCRONIZACIÓN TEMPORAL (GATILLO DE ÉPOCA) ──────────────────────────────
// Realiza el casteo de cadenas a enteros y valida la integridad del tiempo.
// Establece la bandera 'tiene_hora' que autoriza los cálculos astronómicos.
static void Procesar_Tiempo_GPS(void) {
  if (strlen(mi_gps.hora) < 6)
    return;

  double h_val = atof(mi_gps.hora);
  gps_solar.utc_hora = (int)(h_val / 10000);
  gps_solar.utc_min = (int)((h_val - (gps_solar.utc_hora * 10000)) / 100);
  gps_solar.utc_seg = (int)h_val % 100;

  gps_solar_real.utc_hora = gps_solar.utc_hora;
  gps_solar_real.utc_min = gps_solar.utc_min;
  gps_solar_real.utc_seg = gps_solar.utc_seg;

  if (strlen(mi_gps.fecha) == 6) {
    int f_val = atoi(mi_gps.fecha);
    gps_solar.utc_dia = f_val / 10000;
    gps_solar.utc_mes = (f_val % 10000) / 100;
    gps_solar.utc_ano = 2000 + (f_val % 100);
    gps_solar_real.utc_dia = gps_solar.utc_dia;
    gps_solar_real.utc_mes = gps_solar.utc_mes;
    gps_solar_real.utc_ano = gps_solar.utc_ano;

    // Flag de sincronía: Indica que el RTC interno ya tiene una base real
    mi_gps.tiene_hora = 1;
  }
}

// Convierte el formato NMEA (DDMM.MMMM) a Grados Decimales matemáticos
static void Procesar_Ubicacion_GPS(void) {
  // 1. PROCESAMIENTO DE LATITUD
  double raw = atof(mi_gps.latitud);
  int deg = (int)(raw / 100); // Extrae los grados (primeros 2 o 3 dígitos)
  // Convierte minutos decimales a fracción de grado (minutos / 60)
  gps_solar.latitud_deg = deg + ((raw - (deg * 100)) / 60.0);
  gps_solar_real.latitud_deg = gps_solar.latitud_deg;

  // Ajuste de signo hemisférico: Sur es negativo en cartografía digital
  if (mi_gps.lat_dir == 'S') {
    gps_solar.latitud_deg *= -1.0;
    gps_solar_real.latitud_deg *= -1.0;
  }

  // 2. PROCESAMIENTO DE LONGITUD
  raw = atof(mi_gps.longitud);
  deg = (int)(raw / 100); // Extrae los grados
  gps_solar.longitud_deg = deg + ((raw - (deg * 100)) / 60.0);
  gps_solar_real.longitud_deg = gps_solar.longitud_deg;

  // Ajuste de signo hemisférico: Oeste (West) es negativo
  if (mi_gps.lon_dir == 'W') {
    gps_solar.longitud_deg *= -1.0;
    gps_solar_real.longitud_deg *= -1.0;
  }

  // Actualizar últimas coordenadas válidas
  ultima_lat_valida = gps_solar_real.latitud_deg;
  ultima_lon_valida = gps_solar_real.longitud_deg;

  // Las coordenadas nuevas ya están en ultima_lat_valida y se usarán
  // en el próximo ciclo de tarea_principal para evaluar el modo búsqueda.

  // Guardar en NVS con filtro de distancia (~22km)
  Guardar_Configuracion_NVS();
}

// ─── Cálculo Solar ───────────────────────────────────────────────────────────
// Recibe puntero para poder calcular tanto el solar real como el de control
// ─── ALGORITMO DE POSICIONAMIENTO SOLAR (NOAA / MEEUS) ───────────────────────
// Esta función calcula las coordenadas horizontales del sol basándose en la
// ubicación GPS y el tiempo UTC. Utiliza una aproximación del algoritmo de
// Jean Meeus con precisión de 0.01°.
static void Calcular_Posicion_Solar(SolarCalc_t *solar) {
  int Y = solar->utc_ano;
  int M = solar->utc_mes;
  int D = solar->utc_dia;

  // 1. CONVERSIÓN DE TIEMPO A ESCALA DECIMAL
  // Convierte HH:MM:SS en una fracción continua del día (0.0 a 24.0)
  double H_decimal =
      solar->utc_hora + (solar->utc_min / 60.0) + (solar->utc_seg / 3600.0);

  // 2. CÁLCULO DEL DÍA JULIANO (Epoch J2000)
  // El Día Juliano es un contador universal de días que simplifica la
  // astronomía.
  if (M <= 2) {
    Y -= 1;
    M += 12;
  }
  int A = Y / 100;
  int B = 2 - A + (A / 4);
  double d_juliano = (int)(365.25 * (Y + 4716)) + (int)(30.6001 * (M + 1)) + D +
                     B - 1524.5 + H_decimal / 24.0;

  // n_dia: Número de días transcurridos desde el mediodía del 1 de enero de
  // 2000
  double n_dia = d_juliano - 2451545.0;

  // 3. PARÁMETROS ORBITALES (Longitud y Anomalía Media)
  // Determina la posición teórica de la Tierra en su elipse alrededor del sol.
  double l_media = fmod(280.460 + 0.9856474 * n_dia, 360.0);
  double g_media = fmod(357.528 + 0.9856003 * n_dia, 360.0);
  if (l_media < 0)
    l_media += 360;
  if (g_media < 0)
    g_media += 360;

  // 4. COORDENADAS ECLÍPTICAS
  // L_ecliptica: Posición real del sol vista desde la tierra (considerando
  // excentricidad)
  double l_ecliptica =
      l_media + 1.915 * sin(RAD(g_media)) + 0.020 * sin(RAD(2 * g_media));
  // Oblicuidad: La inclinación actual del eje terrestre (~23.4°)
  double oblicuidad = 23.439 - 0.0000004 * n_dia;

  // 5. CONVERSIÓN A COORDENADAS ECUATORIALES
  // Traduce la posición del sol al sistema de Declinación (Latitud celeste)
  // y Ascensión Recta (Longitud celeste).
  double sin_l = sin(RAD(l_ecliptica));
  double cos_l = cos(RAD(l_ecliptica));
  double cos_eps = cos(RAD(oblicuidad));
  double sin_eps = sin(RAD(oblicuidad));

  double declinacion = DEG(asin(sin_eps * sin_l));
  double ascension_recta = DEG(atan2(cos_eps * sin_l, cos_l));

  // 6. CÁLCULO DEL TIEMPO SIDERAL (LMST)
  // Relaciona la rotación de la tierra con la posición de las estrellas y el
  // sol.
  double gmst = fmod(6.697375 + 0.0657098242 * n_dia + H_decimal, 24.0);
  if (gmst < 0)
    gmst += 24.0;

  // LMST: Tiempo sideral local basado en la longitud geográfica del GPS
  double lmst = fmod(gmst + (solar->longitud_deg / 15.0), 24.0);
  if (lmst < 0)
    lmst += 24.0;

  // 7. ÁNGULO HORARIO (Hour Angle)
  // Indica cuántos grados se ha desplazado el sol desde el mediodía solar
  // local.
  double angulo_horario = (lmst * 15.0) - ascension_recta;
  while (angulo_horario > 180)
    angulo_horario -= 360;
  while (angulo_horario < -180)
    angulo_horario += 360;

  // 8. TRANSFORMACIÓN A COORDENADAS HORIZONTALES (Elevación y Azimut)
  // Trigonometría esférica para proyectar la posición en el cielo local.
  double lat_rad = RAD(solar->latitud_deg);
  double dec_rad = RAD(declinacion);
  double omega_rad = RAD(angulo_horario);

  // Elevación: Altura sobre el horizonte (0° a 90°)
  double sin_el = sin(lat_rad) * sin(dec_rad) +
                  cos(lat_rad) * cos(dec_rad) * cos(omega_rad);
  solar->elevacion = DEG(asin(sin_el));

  // Azimut: Orientación cardinal (N=0, E=90, S=180, O=270)
  double y = -sin(omega_rad);
  double x = tan(dec_rad) * cos(lat_rad) - sin(lat_rad) * cos(omega_rad);
  solar->azimut = DEG(atan2(y, x));
  if (solar->azimut < 0)
    solar->azimut += 360.0;
}

static uint8_t es_bisiesto(int ano) {
  return ((ano % 4 == 0 && ano % 100 != 0) || (ano % 400 == 0));
}

// convierte el tiempo universal (UTC) que se recibe del GPS
// a la hora local de mi posicion
static void Calcular_Hora_Local(LocalTime_t *local) {
  double lon_ref =
      sys_ctrl.usar_lon_manual ? sys_ctrl.lon_manual : gps_solar.longitud_deg;
  // Calcular zona horaria basada en longitud (15 grados = 1 hora)
  // Utilizamos lon_ref que ya considera manual -> GPS actual -> NVS rescatada
  local->zona_horaria = (int)(round(lon_ref / 15.0));

  local->seg = gps_solar.utc_seg;
  local->min = gps_solar.utc_min;
  local->hora = gps_solar.utc_hora + local->zona_horaria;
  local->dia = gps_solar.utc_dia;
  local->mes = gps_solar.utc_mes;
  local->ano = gps_solar.utc_ano;

  if (local->hora < 0) {
    local->hora += 24;
    local->dia--;
    if (local->dia == 0) {
      local->mes--;
      if (local->mes == 0) {
        local->mes = 12;
        local->ano--;
      }
      local->dia = (local->mes == 2 && es_bisiesto(local->ano))
                       ? 29
                       : dias_por_mes[local->mes - 1];
    }
  } else if (local->hora >= 24) {
    local->hora -= 24;
    local->dia++;
    int dias_este_mes = dias_por_mes[local->mes - 1];
    if (local->mes == 2 && es_bisiesto(local->ano))
      dias_este_mes = 29;
    if (local->dia > dias_este_mes) {
      local->dia = 1;
      local->mes++;
      if (local->mes > 12) {
        local->mes = 1;
        local->ano++;
      }
    }
  }
}

// ─── GESTOR DE PRIORIDAD DE COORDENADAS ──────────────────────────────────────
// Esta función decide qué coordenadas usar para el cálculo solar (Sol_Calc).
// La jerarquía de prioridad es: Manual (App/MQTT) > GPS (Real/NVS Histórica).
static void Actualizar_Coordenadas_Calculo(void) {
  // Latitud: Prioriza la entrada manual de la App si está activa
  gps_solar.latitud_deg =
      sys_ctrl.usar_lat_manual ? sys_ctrl.lat_manual : ultima_lat_valida;

  // Longitud: Prioriza la entrada manual de la App si está activa
  gps_solar.longitud_deg =
      sys_ctrl.usar_lon_manual ? sys_ctrl.lon_manual : ultima_lon_valida;
}

// ─── GESTOR DE RELOJ DEL SISTEMA (TICKER 1HZ) ────────────────────────────────
// Esta función avanza el reloj interno en 1 segundo cada vez que se llama,
// sirviendo como respaldo si se pierde la señal GPS temporalmente.
static void Gestionar_Tiempo_Sistema(void) {
  int64_t tick_actual_us = esp_timer_get_time();

  if (tick_actual_us - sys_ctrl.ultimo_tick_us >= 1000000LL) {
    sys_ctrl.ultimo_tick_us = tick_actual_us;
    
    // Avanzar reloj local 1 segundo
    tiempo_local.seg++;
    if (tiempo_local.seg >= 60) {
        tiempo_local.seg = 0;
        tiempo_local.min++;
        if (tiempo_local.min >= 60) {
            tiempo_local.min = 0;
            tiempo_local.hora++;
            if (tiempo_local.hora >= 24) {
                tiempo_local.hora = 0;
                // No avanzamos el día por software para evitar desincronización grave
            }
        }
    }
  }
}

// ─── MQTT: Publicar estado ─── Publica un JSON con:
//   - Posicion real del sol (siempre GPS real)
//   - Posicion de los servos
//   - Posicion usada para control (puede ser manual/simulada)
//   - Hora, fecha, coordenadas GPS
//   - Modo actual del sistema
//   - Estado del modo parking
//   - Lecturas del INA3221

static void Publicar_Estado_Rapido_MQTT(void) {
  if (!mqtt_conectado)
    return;

  // Ángulos reales calculados a partir del PWM actual (Silky Motion feedback)
  float servo_az_deg = (1500.0f - (float)pwm_az_actual) / FACTOR_CONVERSION;
  float servo_el_deg = ((float)(pwm_el_actual - 500)) / FACTOR_CONVERSION;

  char json[256]; // JSON compacto para alta frecuencia (10Hz)
  snprintf(json, sizeof(json),
           "{"
           "\"sol\":{\"az\":%.2f,\"el\":%.2f},"
           "\"servos\":{\"az\":%.2f,\"el\":%.2f},"
           "\"p\":{\"c1\":%.2f,\"a1\":%.2f,\"c2\":%.2f,\"a2\":%.2f}"
           "}",
           gps_solar_real.azimut, gps_solar_real.elevacion, servo_az_deg,
           servo_el_deg, (ina_ch1_p * 1000.0f),
           prom_ch1.energia_acumulada_mwh, (ina_ch2_p * 1000.0f),
           prom_ch2.energia_acumulada_mwh);

  esp_mqtt_client_publish(mqtt_client, MQTT_TOPIC_PUB_FAST, json, 0, 0, 0);
}

static void Publicar_Estado_Lento_MQTT(void) {
  if (!mqtt_conectado)
    return;

  size_t total = 0, used = 0;
  uint8_t h_spiffs = 2;
  if (g_datalog_state == DATALOG_OK) {
      esp_spiffs_info("storage", &total, &used);
      int disk_usage = (total > 0) ? (used * 100 / total) : 0;
      int free_perc = 100 - disk_usage;
      if (free_perc > 20) h_spiffs = 0;
      else if (free_perc >= 10) h_spiffs = 1;
      else h_spiffs = 2;
  }
  
  // Calcular salud GPS
  int64_t diff_gps = (esp_timer_get_time() - g_gps_ultimo_fix_us) / 1000000;
  uint8_t h_gps = 0;
  if (diff_gps > HEALTH_GPS_FAIL_SEC) h_gps = 2;
  else if (diff_gps > HEALTH_GPS_WARN_SEC) h_gps = 1;
  
  // Calcular salud WiFi y MQTT
  uint8_t h_wifi = 0;
  EventBits_t bits = xEventGroupGetBits(wifi_event_group);
  if (bits & WIFI_CONNECTED_BIT) {
      h_wifi = mqtt_conectado ? 0 : 1;
  } else {
      h_wifi = 2;
  }
  
  uint8_t h_mqtt = 0;
  if (!mqtt_conectado) h_mqtt = 2;
  else if (g_mqtt_fallos_pub > HEALTH_MQTT_WARN_COUNT) h_mqtt = 1;
  
  // Calcular salud Servos
  uint8_t h_servos = 0;
  if (g_servos_err_init != ESP_OK) h_servos = 2;
  else if (g_servos_limitados) h_servos = 1;

  // Actualizar struct global
  xSemaphoreTake(g_state_mutex, portMAX_DELAY);
  g_health.gps = h_gps;
  g_health.wifi = h_wifi;
  g_health.mqtt = h_mqtt;
  g_health.spiffs = h_spiffs;
  g_health.servos = h_servos;
  
  // Calcular global (peor caso)
  uint8_t global = g_health.ina;
  if (g_health.gps > global) global = g_health.gps;
  if (g_health.wifi > global) global = g_health.wifi;
  if (g_health.mqtt > global) global = g_health.mqtt;
  if (g_health.spiffs > global) global = g_health.spiffs;
  if (g_health.servos > global) global = g_health.servos;
  g_health.global = global;

  char json[512];
  snprintf(json, sizeof(json),
           "{"
           "\"hora\":\"%02d:%02d:%02d\","
           "\"fecha\":\"%02d/%02d/%04d\","
           "\"gps\":{\"lat\":%.5f,\"lon\":%.5f,\"val\":%s},"
           "\"health\":\"INA:%d,GPS:%d,WIFI:%d,MQTT:%d,SPIFFS:%d,SERVOS:%d\","
           "\"global\":\"%d\","
           "\"modo\":\"%s\","
           "\"parking\":%s"
           "}",
           tiempo_local.hora, tiempo_local.min, tiempo_local.seg,
           tiempo_local.dia, tiempo_local.mes, tiempo_local.ano,
           gps_solar_real.latitud_deg, gps_solar_real.longitud_deg,
           mi_gps.es_valido ? "true" : "false",
           g_health.ina, g_health.gps, g_health.wifi, g_health.mqtt, g_health.spiffs, g_health.servos,
           g_health.global,
           (sys_ctrl.servo_manual ? "MAN" : (sys_ctrl.usar_lat_manual || sys_ctrl.usar_lon_manual ? "SET" : "AUTO")),
           en_modo_parking ? "true" : "false");

  xSemaphoreGive(g_state_mutex);
  
  esp_err_t err = esp_mqtt_client_publish(mqtt_client, MQTT_TOPIC_PUB_SLOW, json, 0, 1, 0);
  if (err < 0) g_mqtt_fallos_pub++;
  else g_mqtt_fallos_pub = 0;
}

// ─── FUNCIÓN BATCH: ENVÍO DE DATOS FRAGMENTADO (STREAMING GRADUAL) ──────────
static void Publicar_Batch_Potencia_MQTT(void) {
    if (!mqtt_client || !mqtt_conectado) return;

    // ¿Cuántos datos pendientes hay realmente?
    int pendiente = batch_count - streaming_indice;
    if (pendiente <= 0 && !streaming_manual_trigger) return;

    size_t buf_size = 1024;
    char *json_buff = malloc(buf_size);
    if (!json_buff) return;

    // Calculamos el tamaño de la ráfaga actual
    int a_enviar = (pendiente > CHUNK_SIZE) ? CHUNK_SIZE : pendiente;
    if (a_enviar < 0) a_enviar = 0;

    // Verificamos si es el ÚLTIMO fragmento disponible en el buffer
    bool es_ultimo_del_buffer = (streaming_indice + a_enviar >= batch_count);
    
    // El archivo SOLO se cierra si es el último fragmento Y (se llegó a la meta O fue manual)
    bool msg_last = es_ultimo_del_buffer && (batch_count >= BATCH_GOAL || streaming_manual_trigger);
    bool msg_temp = streaming_manual_trigger;

    int pos = snprintf(json_buff, buf_size, 
              "{\"sid\":%lu,\"id\":%d,\"part\":%d,\"last\":%s,\"temp\":%s,\"data\":\"", 
              (unsigned long)session_id, batch_id, streaming_parte, 
              msg_last ? "true" : "false",
              msg_temp ? "true" : "false");

    for (int i = 0; i < a_enviar; i++) {
        int idx = streaming_indice + i;
        int written = snprintf(json_buff + pos, buf_size - pos, "%.2f,%.2f\\n", batch_ch1[idx], batch_ch2[idx]);
        if (written > 0 && written < (buf_size - pos)) pos += written;
    }

    snprintf(json_buff + pos, buf_size - pos, "\"}");

    esp_mqtt_client_publish(mqtt_client, "solar/data/batch", json_buff, 0, 0, 0);
    free(json_buff);

    // Actualización de estado
    if (msg_last) {
        ESP_LOGI(TAG, "Lote %d finalizado (Manual: %s, Muestras: %d).", 
                 batch_id, msg_temp ? "SI" : "NO", batch_count);
        batch_count = 0;
        streaming_indice = 0;
        streaming_parte = 0;
        batch_id++;
        streaming_manual_trigger = false;
        streaming_activo = false;
        last_batch_p1 = -100.0f;
    } else {
        streaming_indice += a_enviar;
        streaming_parte++;
        streaming_activo = true; // Indica que hay una ráfaga en curso
    }
}

// ─── ESTRATEGIA DE PERSISTENCIA NVS (PROTECCIÓN DE FLASH) ────────────────────
// Esta función gestiona el guardado inteligente de coordenadas en la memoria no
// volátil del ESP32. Implementa un filtro de histéresis para evitar el desgaste
// prematuro de la memoria Flash (soporta ~100k ciclos).
static void Guardar_Configuracion_NVS(void) {
  // 1. FILTRO DE DESGASTE: Solo guarda si el equipo se movió > 0.2° (~22km)
  // o si es la primera vez que se dispone de una señal GPS válida.
  if (fabs(ultima_lat_valida - lat_nvs_guardada) < 0.2 &&
      fabs(ultima_lon_valida - lon_nvs_guardada) < 0.2 &&
      lat_nvs_guardada != 0.0) {
    return; // Aborta para proteger la vida útil de la memoria Flash
  }

  nvs_handle_t my_handle;
  // Abre el namespace "storage" en modo lectura/escritura
  esp_err_t err = nvs_open("storage", NVS_READWRITE, &my_handle);
  if (err != ESP_OK)
    return;

  // Almacena las coordenadas como bloques de datos binarios (Blobs)
  nvs_set_blob(my_handle, "u_lat_gps", &ultima_lat_valida, sizeof(double));
  nvs_set_blob(my_handle, "u_lon_gps", &ultima_lon_valida, sizeof(double));

  // Commit: Fuerza la escritura física en la memoria flash
  nvs_commit(my_handle);
  nvs_close(my_handle);

  // Actualiza los espejos en RAM para el siguiente ciclo del filtro
  lat_nvs_guardada = ultima_lat_valida;
  lon_nvs_guardada = ultima_lon_valida;
  ESP_LOGI(TAG, "NVS: Posición GPS persistida (Distancia significativa)");
}

// ─── RECUPERACIÓN DE ESTADO AL ARRANQUE (BOOTSTRAP) ──────────────────────────
// Recupera las últimas coordenadas conocidas de la memoria Flash.
// Esto permite que el sistema empiece a calcular el sol INSTANTÁNEAMENTE,
// sin esperar el 'Warm-Up' de 60 segundos del receptor GPS.
static void Cargar_Configuracion_NVS(void) {
  nvs_handle_t my_handle;
  esp_err_t err = nvs_open("storage", NVS_READONLY, &my_handle);
  if (err != ESP_OK) {
    ESP_LOGI(TAG, "NVS: Primera ejecución o memoria vacía");
    return;
  }

  size_t size = sizeof(double);
  // Reinstaura la Latitud y Longitud en las estructuras de cálculo
  if (nvs_get_blob(my_handle, "u_lat_gps", &ultima_lat_valida, &size) ==
      ESP_OK) {
    lat_nvs_guardada = ultima_lat_valida;
    gps_solar_real.latitud_deg = ultima_lat_valida;
    gps_solar.latitud_deg = ultima_lat_valida;

    if (ultima_lat_valida != 0.0) {
      ESP_LOGI(TAG,
               "NVS: Coordenadas recuperadas. Iniciando pre-seguimiento...");
    }
  }

  if (nvs_get_blob(my_handle, "u_lon_gps", &ultima_lon_valida, &size) ==
      ESP_OK) {
    lon_nvs_guardada = ultima_lon_valida;
    gps_solar_real.longitud_deg = ultima_lon_valida;
    gps_solar.longitud_deg = ultima_lon_valida;
  }

  nvs_close(my_handle);
}

// ─── MQTT: Procesar comandos
// Comandos que llegan desde la app:
//
//  {"cmd":"reset"}
//      Vuelve todo al modo GPS real
//
//  {"cmd":"set_lat","valor":6.22}
//      Fija latitud manual en grados decimales (positivo=N, negativo=S)
//
//  {"cmd":"set_lon","valor":-75.58}
//      Fija longitud manual en grados decimales (positivo=E, negativo=W)
//
//  {"cmd":"set_vel","factor":60}
//      Activa simulacion acelerada. factor entre 1 y 1440
//      (1=tiempo real, 60=1min/seg, 1440=1dia/min)

// ─── DESPACHADOR DE COMANDOS REMOTOS (MQTT INTERFACE) ────────────────────────
// Esta función actúa como el intérprete entre la App y el hardware.
// Implementa un parser JSON ligero ("zero-copy") para minimizar la RAM.
static void Procesar_Comando_MQTT(const char *datos, int len) {
  char buf[256];
  if (len >= (int)sizeof(buf))
    return;
  memcpy(buf, datos, len);
  buf[len] = '\0';

  // 1. EXTRACCIÓN DEL COMANDO ("cmd")
  char *cmd_ptr = strstr(buf, "\"cmd\"");
  if (!cmd_ptr)
    return;
  cmd_ptr = strchr(cmd_ptr, ':');
  if (!cmd_ptr)
    return;
  cmd_ptr++; // Saltar ':'
  while (*cmd_ptr == ' ' || *cmd_ptr == '"')
    cmd_ptr++; // Limpiar espacios y comillas

  // 1. COMANDO: GET_TEMP (Trigger para descarga del Datalogger)
  if (strncmp(cmd_ptr, "get_temp", 8) == 0) {
      if (!g_descarga_en_curso) {
          xTaskCreate(Datalogger_Tarea_Descarga, "descarga_task", 4096, NULL, 2, NULL);
      }
      streaming_manual_trigger = true; // También dispara snapshot de calibración
  }
  // 2. COMANDO: RESET (RESTAURAR MODO AUTO/GPS)
  else if (strncmp(cmd_ptr, "reset", 5) == 0) {
    sys_ctrl.usar_lat_manual = 0;
    sys_ctrl.usar_lon_manual = 0;
    sys_ctrl.usar_fecha_manual = 0;
    sys_ctrl.servo_manual = 0;
    ESP_LOGI(TAG, "MQTT: Reset a modo AUTOMÁTICO");
  }
  // 3. COMANDO: SET_MAN (FORZAR MODO MANUAL)
  else if (strncmp(cmd_ptr, "set_man", 7) == 0) {
    sys_ctrl.servo_manual = 1;
    sys_ctrl.ser_az_manual = pos_actual_az;
    sys_ctrl.ser_el_manual = pos_actual_el;
    pos_obj_az = pos_actual_az;
    pos_obj_el = pos_actual_el;
    servo_manual_anterior = true;
    ESP_LOGI(TAG, "MQTT: Modo MANUAL activado por usuario");
  }  
    // Recálculo INMEDIATO: Forzamos la actualización astronómica en el mismo ciclo de CPU
    // saltando la cola de la tarea principal.
    Actualizar_Coordenadas_Calculo();
    Calcular_Posicion_Solar(&gps_solar);
    Actualizar_Servos();
    
    ESP_LOGI(TAG, "MQTT: Reset a modo GPS. Nuevo destino calculado al instante.");
  }
  // 3. COMANDOS: FIJAR UBICACIÓN VIRTUAL (TESTING EN INTERIORES)
  else if (strncmp(cmd_ptr, "set_lat", 7) == 0) {
    char *val_ptr = strstr(buf, "\"valor\"");
    if (!val_ptr)
      return;
    val_ptr = strchr(val_ptr, ':');
    if (!val_ptr)
      return;
    double valor = atof(val_ptr + 1);
    // Validación de límites físicos (Hemisferio Norte/Sur)
    if (valor >= -90.0 && valor <= 90.0) {
      sys_ctrl.lat_manual = valor;
      sys_ctrl.usar_lat_manual = 1;
      sys_ctrl.servo_manual = 0; // salir de modo servo manual

      // Recálculo INMEDIATO para asegurar pivote cinemático sin desfase
      Actualizar_Coordenadas_Calculo();
      Calcular_Posicion_Solar(&gps_solar);
      Actualizar_Servos();

      ESP_LOGI(TAG, "MQTT: Latitud manual = %.5f", valor);
    }
  } else if (strncmp(cmd_ptr, "set_lon", 7) == 0) {
    char *val_ptr = strstr(buf, "\"valor\"");
    if (!val_ptr)
      return;
    val_ptr = strchr(val_ptr, ':');
    if (!val_ptr)
      return;
    double valor = atof(val_ptr + 1);
    // Validación de límites físicos (Hemisferio Este/Oeste)
    if (valor >= -180.0 && valor <= 180.0) {
      sys_ctrl.lon_manual = valor;
      sys_ctrl.usar_lon_manual = 1;
      sys_ctrl.servo_manual = 0;

      // Recálculo INMEDIATO para asegurar pivote cinemático sin desfase
      Actualizar_Coordenadas_Calculo();
      Calcular_Posicion_Solar(&gps_solar);
      Actualizar_Servos();

      ESP_LOGI(TAG, "MQTT: Longitud manual = %.5f", valor);
    }
  }
  // 4. COMANDO: ACTIVAR SIMULACIÓN ACELERADA
  else if (strncmp(cmd_ptr, "set_vel", 7) == 0) {
    char *val_ptr = strstr(buf, "\"factor\"");
    if (!val_ptr)
      return;
    val_ptr = strchr(val_ptr, ':');
    if (!val_ptr)
      return;
    int factor = atoi(val_ptr + 1);
    // El factor multiplica el paso del tiempo real
    // (segundos_simulados / seg_real)
    if (factor >= 1 && factor <= 1440) {
      sys_ctrl.factor_velocidad = factor;
      sys_ctrl.simulacion_activa = 1;
      sys_ctrl.servo_manual = 0;
      sys_ctrl.tiempo_interno = tiempo_local;
      sys_ctrl.ultimo_tick_us = esp_timer_get_time();
      ESP_LOGI(TAG, "MQTT: Simulación temporal activada (x%d)", factor);
    }
  }

  // 5. COMANDOS: CONTROL MANUAL DE SERVOS (JOYSTICK VIRTUAL)
  else if (strncmp(cmd_ptr, "set_ser_az", 10) == 0) {
    char *val_ptr = strstr(buf, "\"valor\"");
    if (!val_ptr)
      return;
    val_ptr = strchr(val_ptr, ':');
    if (!val_ptr)
      return;
    float valor = (float)atof(val_ptr + 1);

    // Rango permisivo (90.1) para evitar que el slider falle en los bordes
    if (valor >= -90.1f && valor <= 90.1f) {
      // Snapshot universal continuo al ángulo FÍSICO ACTUAL (Frenado en seco)
      sys_ctrl.ser_el_manual = pos_actual_el;
      
      sys_ctrl.ser_az_manual = valor;
      pos_obj_az = valor; // Inyectar directamente al lazo cinemático de 50Hz
      
      // Primero actualizamos el valor, luego el modo para que tarea_principal
      // lo encuentre listo
      sys_ctrl.servo_manual = 1;
      // Marcamos como sincronizado para evitar que tarea_principal sobreescriba
      // el joystick con la posición anterior en el primer ciclo
      servo_manual_anterior = true;
      ESP_LOGI(TAG, "MQTT: Servo AZ manual = %.2f (Pivote forzado)", valor);
    }
  } else if (strncmp(cmd_ptr, "set_ser_el", 10) == 0) {
    char *val_ptr = strstr(buf, "\"valor\"");
    if (!val_ptr)
      return;
    val_ptr = strchr(val_ptr, ':');
    if (!val_ptr)
      return;
    float valor = (float)atof(val_ptr + 1);

    if (valor >= -0.1f && valor <= 180.1f) {
      // Snapshot universal continuo al ángulo FÍSICO ACTUAL (Frenado en seco)
      sys_ctrl.ser_az_manual = pos_actual_az;
      
      sys_ctrl.ser_el_manual = valor;
      pos_obj_el = valor; // Inyectar directamente al lazo cinemático de 50Hz
      
      sys_ctrl.servo_manual = 1;
      servo_manual_anterior = true;
      ESP_LOGI(TAG, "MQTT: Servo EL manual = %.2f (Pivote forzado)", valor);
    }
  }
  else if (strncmp(cmd_ptr, "clear_batch", 11) == 0) {
    batch_count = 0;
    last_batch_p1 = -100.0f;
    ESP_LOGI(TAG, "MQTT: Batch de potencia reiniciado.");
  }
}

// ─── CONTROLADOR DE BAJO NIVEL INA3221 (I2C) ─────────────────────────────────
// Escribe un valor de 16 bits en un registro específico del INA3221.
// Sigue el protocolo I2C: [START] -> [ADDR+W] -> [REG] -> [MSB] -> [LSB] ->
// [STOP]
static esp_err_t ina3221_write_reg(uint8_t reg, uint16_t value) {
  // El INA3221 espera los datos en formato Big-Endian (Byte alto primero)
  uint8_t buf[3] = {reg, (value >> 8) & 0xFF, value & 0xFF};
  i2c_cmd_handle_t cmd = i2c_cmd_link_create();
  i2c_master_start(cmd);
  i2c_master_write_byte(cmd, (INA3221_ADDR << 1) | I2C_MASTER_WRITE, true);
  i2c_master_write(cmd, buf, 3, true);
  i2c_master_stop(cmd);

  // Tiempo de espera corto para escritura de registro
  esp_err_t ret = i2c_master_cmd_begin(I2C_NUM_0, cmd, pdMS_TO_TICKS(10));
  i2c_cmd_link_delete(cmd);
  return ret;
}

// Lee un valor de 16 bits desde un registro del INA3221.
// Requiere un "Restart" para cambiar de modo Escritura (puntero) a Lectura.
static esp_err_t ina3221_read_reg(uint8_t reg, int16_t *value) {
  uint8_t buf[2];
  i2c_cmd_handle_t cmd = i2c_cmd_link_create();
  i2c_master_start(cmd);
  // Fase 1: Enviar la dirección del registro que queremos leer
  i2c_master_write_byte(cmd, (INA3221_ADDR << 1) | I2C_MASTER_WRITE, true);
  i2c_master_write_byte(cmd, reg, true);

  // Fase 2: Reiniciar el bus en modo lectura
  i2c_master_start(cmd);
  i2c_master_write_byte(cmd, (INA3221_ADDR << 1) | I2C_MASTER_READ, true);
  i2c_master_read_byte(cmd, &buf[0], I2C_MASTER_ACK);  // Leer MSB
  i2c_master_read_byte(cmd, &buf[1], I2C_MASTER_NACK); // Leer LSB y terminar
  i2c_master_stop(cmd);

  // Tolerancia aumentada a 50ms para evitar fallos por ruido en el bus
  esp_err_t ret = i2c_master_cmd_begin(I2C_NUM_0, cmd, pdMS_TO_TICKS(50));
  i2c_cmd_link_delete(cmd);

  if (ret == ESP_OK) {
    // Recomponer el valor de 16 bits (Big-Endian)
    *value = (int16_t)((buf[0] << 8) | buf[1]);
  }
  return ret;
}

// Escanea el bus I2C en busca del sensor y verifica su autenticidad.
static bool ina3221_detectar(void) {
  uint8_t direcciones[] = {0x40, 0x41, 0x42,
                           0x43}; // Direcciones posibles por hardware
  for (int i = 0; i < 4; i++) {
    uint8_t addr = direcciones[i];
    i2c_cmd_handle_t cmd = i2c_cmd_link_create();
    i2c_master_start(cmd);
    i2c_master_write_byte(cmd, (addr << 1) | I2C_MASTER_WRITE, true);
    i2c_master_stop(cmd);
    esp_err_t ret = i2c_master_cmd_begin(I2C_NUM_0, cmd, pdMS_TO_TICKS(50));
    i2c_cmd_link_delete(cmd);

    if (ret == ESP_OK) {
      // VERIFICACIÓN DE FIRMA: El ID del fabricante debe ser 0x5449 ('TI' en
      // ASCII)
      int16_t manuf_id = 0;
      INA3221_ADDR = addr;
      if (ina3221_read_reg(0xFE, &manuf_id) == ESP_OK && manuf_id == 0x5449) {
        ESP_LOGI(TAG,
                 "INA3221 detectado: Firma de fabricante verificada en 0x%02X",
                 addr);
        return true;
      }
    }
  }
  ESP_LOGE(TAG, "INA3221 NO encontrado o firma inválida");
  return false;
}

// Configura el modo de operación del sensor.
static esp_err_t ina3221_init(void) {
  // Configuración 0x6827:
  // - Habilita Canales 1 y 2.
  // - 64 muestras promediadas para filtrar ruido eléctrico.
  // - Modo continuo de medición (Shunt y Bus).
  esp_err_t ret = ina3221_write_reg(REG_INA_CONFIG, 0x6827);
  vTaskDelay(pdMS_TO_TICKS(100)); // Esperar estabilización del chip
  return ret;
}

// Verifica si el ADC del INA3221 ha terminado de procesar una nueva muestra.
static esp_err_t ina3221_conversion_lista(bool *ready) {
  int16_t raw;
  esp_err_t err = ina3221_read_reg(REG_MASK_ENABLE, &raw);
  if (err != ESP_OK) {
    *ready = false;
    return err;
  }
  // El Bit 0 (CVRF) indica si hay datos frescos disponibles
  *ready = (raw & 0x0001);
  return ESP_OK;
}

// Extrae las mediciones físicas de un canal específico (1, 2 o 3).
// Realiza la conversión de niveles lógicos de los registros a Voltios y
// Amperios.
static bool ina3221_leer_canal(int canal, float *voltaje, float *corriente) {
  if (canal < 1 || canal > 3)
    return false;

  // Los registros del INA3221 están organizados en pares secuenciales
  uint8_t reg_shunt = REG_CH1_SHUNT + ((canal - 1) * 2);
  uint8_t reg_bus = REG_CH1_BUS + ((canal - 1) * 2);

  int16_t raw;
  *corriente = 0.0f;
  *voltaje = 0.0f;

  // 1. LECTURA DE CORRIENTE (Vía Voltaje en la Resistencia Shunt)
  if (ina3221_read_reg(reg_shunt, &raw) != ESP_OK)
    return false;

  // ALINEACIÓN: Los datos están en los bits [15:3]. Desplazamos 3 bits a la
  // derecha. ESCALA: Cada bit (LSB) equivale a 40 microVoltios.
  float shunt_uv = (float)(raw >> 3) * 40.0f;
  // LEY DE OHM (I = V / R): Convertimos uV a V y dividimos por la resistencia
  // shunt.
  *corriente = (shunt_uv / 1000000.0f) / SHUNT_RESISTOR;

  // 2. LECTURA DE VOLTAJE DE BUS (Voltaje de la carga respecto a GND)
  if (ina3221_read_reg(reg_bus, &raw) != ESP_OK)
    return false;

  // ALINEACIÓN: Igual que el anterior, bits [15:3].
  // ESCALA: Cada bit equivale a 8 miliVoltios.
  *voltaje = (float)(raw >> 3) * 8.0f / 1000.0f;

  return true;
}

static void ina_actualizar_promedio(INA_Promedio_t *p, float v, float i,
                                    bool valido) {
  int64_t ahora = xTaskGetTickCount() * portTICK_PERIOD_MS;

  // Reinicio diario: Se dispara ante cualquier cambio de día, no solo a la medianoche.
  // Esto protege contra acumulaciones residuales si el sistema se apaga de noche.
  if (p->dia_actual != -1 && tiempo_local.dia != p->dia_actual) {
    memset(p->buffer, 0, sizeof(p->buffer));
    p->indice = 0;
    p->count = 0;
    p->suma = 0.0f;
    p->energia_acumulada_mwh = 0.0f;
    p->ultimo_promedio_5min = -1.0f;
    p->acum_p = 0.0f;
    p->acum_n = 0;
    p->dia_actual = tiempo_local.dia;
    ESP_LOGI("INA", "Promedio diario reiniciado por cambio de fecha");
  }

  // Sincronización inicial del día
  if (p->dia_actual == -1 && tiempo_local.dia > 0) {
    p->dia_actual = tiempo_local.dia;
  }

  // Nivel 1: ACUMULACIÓN SELECTIVA
  // Solo promediamos si hay producción real (>0.1uW)
  float p_inst = v * i;
  if (valido && p_inst > 0.0000001f) {
    bool mismo_dato = (v == p->ultimo_v && i == p->ultimo_i);
    
    // Si el bus I2C devuelve exactamente los mismos bits de V e I
    if (mismo_dato) {
        p->conteo_congelado++;
    } else {
        p->conteo_congelado = 0; // Se recuperó, cambió aunque sea 1 LSB
        p->ultimo_v = v;
        p->ultimo_i = i;
    }

    // Tolerancia robusta: En días muy nublados el ADC puede reportar valores
    // idénticos varias veces, confiamos en ellos gracias al bit 'Conversion Ready'.
    // PERO si se repiten más de 20 veces (~2 segundos de datos repetidos a 10Hz), 
    // asumimos que el I/O interno del sensor se trabó congelando buffers.
    if (p->conteo_congelado < 20) {
      p->acum_p += p_inst;
      p->acum_n++;
    }
  } else {
      p->conteo_congelado = 0; // Reset si entra en noche o data errónea
  }

  // Nivel 2: Cada 5 minutos calcular promedio del bloque y alimentar media
  // móvil
  if ((ahora - p->ultimo_tick_5min) >= INA_INTERVALO_MS) {
    p->ultimo_tick_5min = ahora;

    // Solo procesamos bloques donde hubo producción real detectada (>0.1uW)
    // Esto asegura que la media NO sea "tirada al piso" por los valores de 0W
    // nocturnos.
    if (p->acum_n > 0) {
      float promedio_5min = p->acum_p / (float)p->acum_n;

      // Insertar en buffer circular (Media Móvil de 24h para comparación de
      // eficiencia) Cada entrada representa 5 minutos reales de sol.
      float valor_saliente = p->buffer[p->indice];
      p->buffer[p->indice] = promedio_5min;
      p->indice = (p->indice + 1) % INA_VENTANA_MOVIL;

      if (p->count < INA_VENTANA_MOVIL) {
        // Llenado inicial del buffer
        p->suma += promedio_5min;
        p->count++;
      } else {
        // Buffer completo: Shift temporal
        p->suma = p->suma - valor_saliente + promedio_5min;
      }

      // INTEGRACIÓN ABSOLUTA A ENERGÍA (mWh):
      // A diferencia del promedio móvil, la energía total generada en un día
      // nunca "sale de la ventana", solo se acumula iterativamente.
      // E_Tramo = P_Media_Tramo * tiempo_tramo
      // tiempo_tramo = 5 minutos = 1/12 horas.
      float energia_tramo_mwh = (promedio_5min * 1000.0f) / 12.0f;
      p->energia_acumulada_mwh += energia_tramo_mwh;
      p->ultimo_promedio_5min = promedio_5min * 1000.0f;
    } else {
      p->ultimo_promedio_5min = 0.0f;
    }

    // Reiniciar acumulador para el próximo bloque de 5 minutos
    p->acum_p = 0.0f;
    p->acum_n = 0;

    if (p == &prom_ch1) {
        Datalogger_Escribir_Punto();
    }
  }
}

// ─── GESTIÓN DE CONECTIVIDAD WIFI (REDUNDANCIA TRIPLE Y BACKOFF)
// ──────────────

// ─── Variables para Reconexión WiFi Exponencial y Respaldo ───────────────────
static uint8_t indice_red_wifi = 0; // 0=Principal, 1=Respaldo1, 2=Respaldo2
static uint32_t wifi_reconnect_delay_sec = 0;
static esp_timer_handle_t wifi_reconnect_timer = NULL;

// Temporizador de reconexión: implementa la lógica de rotación de redes.
// Se dispara después de que expire el tiempo de espera del backoff exponencial.
static void wifi_reconnect_timer_cb(void *arg) {
  // ESTRATEGIA DE REDUNDANCIA: Si agotamos los intentos en la red actual...
  if (wifi_retry_count >= WIFI_MAX_RETRIES) {
    wifi_retry_count = 0;
    wifi_reconnect_delay_sec = 0; // Reiniciar backoff para la nueva red

    // ROTACIÓN CIRCULAR (Primary -> Backup 1 -> Backup 2 -> Primary)
    indice_red_wifi = (indice_red_wifi + 1) % 3;

    wifi_config_t wifi_config = {0};
    if (indice_red_wifi == 1) {
      ESP_LOGW(TAG, "Cambiando a red de RESPALDO 1: %s", WIFI_BACKUP_SSID);
      strncpy((char *)wifi_config.sta.ssid, WIFI_BACKUP_SSID,
              sizeof(wifi_config.sta.ssid));
      strncpy((char *)wifi_config.sta.password, WIFI_BACKUP_PASSWORD,
              sizeof(wifi_config.sta.password));
    } else if (indice_red_wifi == 2) {
      ESP_LOGW(TAG, "Cambiando a red de RESPALDO 2: %s", WIFI_BACKUP2_SSID);
      strncpy((char *)wifi_config.sta.ssid, WIFI_BACKUP2_SSID,
              sizeof(wifi_config.sta.ssid));
      strncpy((char *)wifi_config.sta.password, WIFI_BACKUP2_PASSWORD,
              sizeof(wifi_config.sta.password));
    } else {
      ESP_LOGW(TAG, "Regresando a red PRINCIPAL: %s", WIFI_SSID);
      strncpy((char *)wifi_config.sta.ssid, WIFI_SSID,
              sizeof(wifi_config.sta.ssid));
      strncpy((char *)wifi_config.sta.password, WIFI_PASSWORD,
              sizeof(wifi_config.sta.password));
    }
    esp_wifi_set_config(WIFI_IF_STA, &wifi_config);
  }

  wifi_retry_count++;
  esp_wifi_connect();
}

// Manejador central de eventos de red (WiFi e IP).
static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data) {
  if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
    wifi_retry_count = 1;
    esp_wifi_connect();
  } else if (event_base == WIFI_EVENT &&
             event_id == WIFI_EVENT_STA_DISCONNECTED) {
    mqtt_conectado = false; // Invalidar estado de telemetría inmediatamente

    // LIBERACIÓN DE BOOT: Si falla el primer intento, app_main puede seguir
    // su ejecución para operar en modo local (GPS + Servos) sin Internet.
    if (wifi_retry_count == WIFI_MAX_RETRIES && indice_red_wifi == 0) {
      xEventGroupSetBits(wifi_event_group, WIFI_FAIL_BIT);
    }

    // ALGORITMO DE BACKOFF EXPONENCIAL:
    // Evita saturar el punto de acceso y ahorra energía durante apagones de
    // red. Secuencia (segundos): 2, 4, 8, 16, 32, 64 (máximo).
    if (wifi_reconnect_delay_sec == 0) {
      wifi_reconnect_delay_sec = 2;
    } else if (wifi_reconnect_delay_sec < 64) {
      wifi_reconnect_delay_sec *= 2; // Duplica si no ha llegado a 64s
    }

    ESP_LOGW(TAG, "WiFi desconectado (Intento %d/%d). Reconectando en %lu s...",
             wifi_retry_count, WIFI_MAX_RETRIES,
             (unsigned long)wifi_reconnect_delay_sec);

    // Arrancar temporizador no bloqueante
    if (wifi_reconnect_timer == NULL) {
      const esp_timer_create_args_t timer_args = {
          .callback = &wifi_reconnect_timer_cb, .name = "wifi_reconnect_tmr"};
      esp_timer_create(&timer_args, &wifi_reconnect_timer);
    }
    esp_timer_start_once(wifi_reconnect_timer,
                         (uint64_t)wifi_reconnect_delay_sec * 1000000ULL);

  } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
    // ÉXITO: Reiniciar contadores de salud de red
    wifi_retry_count = 0;
    wifi_reconnect_delay_sec = 0; // Reiniciar backoff porque fue existoso
    xEventGroupSetBits(wifi_event_group, WIFI_CONNECTED_BIT);
    ESP_LOGI(TAG, "WiFi: IP Obtenida. Sistema listo para telemetría.");
  }
}

// Inicializa el stack de red y arranca la interfaz inalámbrica.
static void wifi_init(void) {
  wifi_event_group = xEventGroupCreate();

  ESP_ERROR_CHECK(esp_netif_init());
  ESP_ERROR_CHECK(esp_event_loop_create_default());
  esp_netif_create_default_wifi_sta();

  wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
  ESP_ERROR_CHECK(esp_wifi_init(&cfg));

  // Registro de manejadores para eventos globales
  esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                      &wifi_event_handler, NULL, NULL);
  esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                      &wifi_event_handler, NULL, NULL);

  wifi_config_t wifi_config = {
      .sta =
          {
              .ssid = WIFI_SSID,
              .password = WIFI_PASSWORD,
          },
  };

  ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
  ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
  ESP_ERROR_CHECK(esp_wifi_start());

  // ESPERA NO BLOQUEANTE: Aguarda por conexión o fallo para notificar a
  // app_main.
  EventBits_t bits =
      xEventGroupWaitBits(wifi_event_group, WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
                          pdFALSE, pdFALSE, portMAX_DELAY);
  if (bits & WIFI_CONNECTED_BIT) {
    ESP_LOGI(TAG, "WiFi OK");
  } else {
    ESP_LOGE(TAG, "WiFi: no se pudo conectar");
  }
}

// ─── GESTIÓN DE TELEMETRÍA Y CONTROL REMOTO (MQTT) ───────────────────────────

// Manejador de eventos MQTT: Gestiona el ciclo de vida de la telemetría.
static void mqtt_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data) {
  esp_mqtt_event_handle_t event = (esp_mqtt_event_handle_t)event_data;
  static int64_t ultimo_mqtt_connect_us = 0;
  static int fallos_mqtt_consecutivos = 0;

  switch (event_id) {
  case MQTT_EVENT_CONNECTED:
    mqtt_conectado = true;
    ultimo_mqtt_connect_us = esp_timer_get_time();
    ESP_LOGI(TAG, "MQTT conectado");
    esp_mqtt_client_subscribe(mqtt_client, MQTT_TOPIC_SUB, 1);
    esp_mqtt_client_subscribe(mqtt_client, MQTT_TOPIC_APP_STATUS, 1);
    esp_mqtt_client_subscribe(mqtt_client, MQTT_TOPIC_DATA_ACK, 1);
    break;

  case MQTT_EVENT_DISCONNECTED:
    mqtt_conectado = false;
    ESP_LOGW(TAG, "MQTT: Conexión perdida.");

    // MONITOR DE ESTABILIDAD DE CAPA DE APLICACIÓN:
    // Si la conexión dura menos de 30s de forma repetida (3 veces), asumimos
    // que la red WiFi actual no tiene salida real a Internet o hay bloqueo de
    // puertos. En tal caso, forzamos un cambio de SSID.
    if (esp_timer_get_time() - ultimo_mqtt_connect_us < 30000000LL) {
      fallos_mqtt_consecutivos++;
    } else {
      fallos_mqtt_consecutivos = 1;
    }

    if (fallos_mqtt_consecutivos >= 3) {
      ESP_LOGE(
          TAG,
          "Broker inalcanzable persistentemente. Forzando rotación de SSID...");
      fallos_mqtt_consecutivos = 0;
      wifi_retry_count = WIFI_MAX_RETRIES; // Gatillo manual para el timer WiFi
      wifi_reconnect_delay_sec = 0;
      esp_wifi_disconnect(); // Corte físico para gatillar reconexión
    }
    break;

  case MQTT_EVENT_DATA:
    // RECEPCIÓN DE COMANDOS: Desvía el payload al parser de comandos remoto.
    if (strncmp(event->topic, MQTT_TOPIC_SUB, event->topic_len) == 0) {
        Procesar_Comando_MQTT(event->data, event->data_len);
    } else if (strncmp(event->topic, MQTT_TOPIC_APP_STATUS, event->topic_len) == 0) {
        Procesar_Status_App(event->data, event->data_len);
    } else if (strncmp(event->topic, MQTT_TOPIC_DATA_ACK, event->topic_len) == 0) {
        Procesar_ACK_Datalogger(event->data, event->data_len);
    }
    break;

  default:
    break;
  }
}

// Configura y arranca el cliente MQTT.
static void mqtt_init(void) {
  esp_mqtt_client_config_t mqtt_cfg = {
      .broker.address.hostname = MQTT_BROKER_URL,
      .broker.address.port = MQTT_BROKER_PORT,
      .broker.address.transport = MQTT_TRANSPORT_OVER_TCP,
      .credentials.username = MQTT_USERNAME,
      .credentials.authentication.password = MQTT_PASSWORD,
      
      // Requerimiento: Last Will "offline" en solar/app/status
      .session.last_will.topic = MQTT_TOPIC_APP_STATUS,
      .session.last_will.msg = "offline",
      .session.last_will.qos = 1,
      .session.last_will.retain = true,

      // CONFIGURACIÓN DE SEGURIDAD CONTRA BLOQUEOS DE TAREA:
      // El timeout de red (4s) es CRÍTICO. Debe ser menor al Watchdog (10s).
      // Si el socket se bloquea intentando escribir en el broker caído,
      // retiene un Mutex interno que bloquea a 'tarea_main' en
      // Publicar_Estado_MQTT, lo que causaba reinicios por pánico del sistema.
      .network.timeout_ms = 4000,
      .network.reconnect_timeout_ms = 5000,
  };

  mqtt_client = esp_mqtt_client_init(&mqtt_cfg);
  esp_mqtt_client_register_event(mqtt_client, ESP_EVENT_ANY_ID,
                                 mqtt_event_handler, NULL);
  esp_mqtt_client_start(mqtt_client);
}

// ─── DATALOGGER: IMPLEMENTACIÓN DE ESTRATEGIA SPIFFS ─────────────────────────

static void Datalogger_Init(void) {
    if (g_datalog_state == DATALOG_OK) return;
    
    ESP_LOGI("DATALOG", "Inicializando SPIFFS...");
    
    esp_vfs_spiffs_conf_t conf = {
      .base_path = SPIFFS_BASE_PATH,
      .partition_label = SPIFFS_PARTITION_LABEL,
      .max_files = 5,
      .format_if_mount_failed = false
    };

    esp_err_t ret = esp_vfs_spiffs_register(&conf);

    if (ret != ESP_OK) {
        ESP_LOGW("DATALOG", "Fallo montaje inicial. Intentando reparar...");
        if (esp_spiffs_check(conf.partition_label) == ESP_OK) {
             ret = esp_vfs_spiffs_register(&conf);
        }
        
        if (ret != ESP_OK) {
             ESP_LOGW("DATALOG", "Formateando partición 'storage'...");
             conf.format_if_mount_failed = true;
             ret = esp_vfs_spiffs_register(&conf);
        }
    }

    if (ret == ESP_OK) {
        size_t total = 0, used = 0;
        esp_spiffs_info(SPIFFS_PARTITION_LABEL, &total, &used);
        ESP_LOGI("DATALOG", "SPIFFS OK. Capacidad: %d KB, Usado: %d KB", (int)total/1024, (int)used/1024);
        g_datalog_state = DATALOG_OK;
        
        mkdir(DATALOG_DIR_PATH, 0775);
    } else {
        g_datalog_state = DATALOG_MOUNT_FAILED;
        ESP_LOGE("DATALOG", "Fallo crítico al montar SPIFFS");
        if (xSemaphoreTake(health_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
            g_health.spiffs = HEALTH_FAIL;
            xSemaphoreGive(health_mutex);
        }
    }
}

static void Datalogger_Truncar_Corrupto(const char* path) {
    FILE* f = fopen(path, "r+");
    if (!f) return;
    fseek(f, -1, SEEK_END);
    int last_char = fgetc(f);
    if (last_char != '\n' && last_char != EOF) {
        ESP_LOGW("DATALOG", "Truncando registro corrupto en %s", path);
        // En SPIFFS truncar puede requerir reescribir, simplificamos cerrando
        // pero validamos en la apertura para el próximo append.
    }
    fclose(f);
}

static void Datalogger_Limpiar_Espacio(void) {
    size_t total = 0, used = 0;
    if (esp_spiffs_info(SPIFFS_PARTITION_LABEL, &total, &used) == ESP_OK) {
        int free_perc = (total > 0) ? ((total - used) * 100 / total) : 100;
        if (free_perc < SPIFFS_DANGER_FREE_PERC) {
            ESP_LOGW("DATALOG", "Espacio crítico (%d%%). Aplicando rotación...", free_perc);
            DIR *dir = opendir(DATALOG_DIR_PATH);
            if (!dir) return;
            struct dirent *ent;
            char oldest[128] = {0};
            while ((ent = readdir(dir)) != NULL) {
                if (ent->d_type == DT_REG && strstr(ent->d_name, ".csv")) {
                    if (oldest[0] == 0 || strcmp(ent->d_name, oldest) < 0) {
                        strcpy(oldest, ent->d_name);
                    }
                }
            }
            closedir(dir);
            if (oldest[0] != 0) {
                char full_path[160];
                snprintf(full_path, sizeof(full_path), "%s/%s", DATALOG_DIR_PATH, oldest);
                ESP_LOGW("DATALOG", "Borrando archivo antiguo: %s", full_path);
                unlink(full_path);
            }
        }
    }
}

static void Datalogger_Escribir_Punto(void) {
    if (g_datalog_state != DATALOG_OK) return;
    if (tiempo_local.ano < 2024) return;
    
    Datalogger_Limpiar_Espacio();

    char path[128];
    snprintf(path, sizeof(path), DATALOG_FILE_PATH_FMT, 
             tiempo_local.ano, tiempo_local.mes, tiempo_local.dia);

    bool exists = (access(path, F_OK) == 0);
    if (exists) Datalogger_Truncar_Corrupto(path);

    FILE* f = fopen(path, "a");
    if (!f) {
        ESP_LOGE("DATALOG", "Error de apertura en %s", path);
        return;
    }

    if (!exists) {
        fprintf(f, "# lat=%.4f,lon=%.4f,fecha=%04d-%02d-%02d\n",
                gps_solar_real.latitud_deg, gps_solar_real.longitud_deg,
                tiempo_local.ano, tiempo_local.mes, tiempo_local.dia);
        fprintf(f, "hora_utc,p1_avg_mw,p2_avg_mw,enviado\n");
    }

    fprintf(f, "%02d:%02d:%02d,%.2f,%.2f,0\n",
            tiempo_local.hora, tiempo_local.min, tiempo_local.seg,
            prom_ch1.ultimo_promedio_5min, prom_ch2.ultimo_promedio_5min);

    fclose(f);
    ESP_LOGI("DATALOG", "Punto guardado en disco.");
}

static int Datalogger_Contar_Pendientes(void) {
    int total_pendientes = 0;
    DIR* dr = opendir("/spiffs");
    if (!dr) return 0;

    struct dirent* de;
    while ((de = readdir(dr)) != NULL) {
        if (strstr(de->d_name, ".csv")) {
            char path[64];
            snprintf(path, sizeof(path), "/spiffs/%s", de->d_name);
            FILE* f = fopen(path, "r");
            if (f) {
                char line[128];
                while (fgets(line, sizeof(line), f)) {
                    int len = strlen(line);
                    if (len > 2 && line[len-2] == '0') total_pendientes++;
                }
                fclose(f);
            }
        }
    }
    closedir(dr);
    return total_pendientes;
}

static void Datalogger_Tarea_Descarga(void *arg) {
    esp_task_wdt_add(NULL);
    g_descarga_en_curso = true;
    ESP_LOGI("DATALOG", "Iniciando tarea de descarga...");

    DIR* dr = opendir("/spiffs/data");
    if (!dr) {
        // Intentar root si data prefix falló
        dr = opendir("/spiffs");
    }
    
    if (!dr) {
        g_descarga_en_curso = false;
        vTaskDelete(NULL);
        return;
    }

    struct dirent* de;
    while ((de = readdir(dr)) != NULL && g_descarga_en_curso) {
        if (!strstr(de->d_name, ".csv")) continue;
        
        char path[64];
        if (strstr(de->d_name, "data/")) snprintf(path, sizeof(path), "/spiffs/%s", de->d_name);
        else snprintf(path, sizeof(path), "/spiffs/data/%s", de->d_name);
        
        // Si no existe con prefix data, intentar directo
        if (access(path, F_OK) != 0) snprintf(path, sizeof(path), "/spiffs/%s", de->d_name);

        FILE* f = fopen(path, "r+");
        if (!f) continue;

        char line[128];
        long line_pos = 0;
        
        // Saltar cabecera y columnas (2 líneas)
        fgets(line, sizeof(line), f); // cabecera
        fgets(line, sizeof(line), f); // columnas
        line_pos = ftell(f);

        while (fgets(line, sizeof(line), f) && g_descarga_en_curso) {
            esp_task_wdt_reset();
            int len = strlen(line);
            
            // Buscar si enviado=0 (último carácter antes de \n es '0')
            // Formato: hora,p1,p2,0\n
            if (len > 2 && line[len-2] == '0') {
                char hora[10];
                float p1, p2;
                if (sscanf(line, "%9[^,],%f,%f,0", hora, &p1, &p2) == 3) {
                    char record[128];
                    snprintf(record, sizeof(record), "%s,%.2f,%.2f", hora, p1, p2);
                    
                    bool success = false;
                    for (int r = 0; r < DATALOG_MAX_RETRIES && !success; r++) {
                        if (!g_descarga_en_curso) break;
                        
                        esp_mqtt_client_publish(mqtt_client, MQTT_TOPIC_DATA_RECORD, record, 0, 1, 0);
                        
                        // Esperar ACK con timeout de 3 segundos
                        if (xSemaphoreTake(g_sem_ack, pdMS_TO_TICKS(DATALOG_ACK_TIMEOUT_MS)) == pdTRUE) {
                            if (strcmp(g_last_ack_id, hora) == 0) {
                                success = true;
                            }
                        }
                        
                        if (!success && g_descarga_en_curso) {
                            ESP_LOGW("DATALOG", "Fallo ACK para %s (Intento %d)", hora, r+1);
                            vTaskDelay(pdMS_TO_TICKS(200)); // Breve espera antes de reintentar
                        }
                    }
                    
                    if (success) {
                        // Marcar como enviado=1 directamente en el archivo
                        fseek(f, line_pos + len - 2, SEEK_SET);
                        fputc('1', f);
                        fflush(f);
                        ESP_LOGD("DATALOG", "Registro %s confirmado", hora);
                    } else {
                        ESP_LOGE("DATALOG", "Registro %s descartado tras reintentos", hora);
                    }
                }
                vTaskDelay(pdMS_TO_TICKS(DATALOG_BATCH_INTERVAL_MS)); // 200ms entre registros
            }
            line_pos = ftell(f);
        }
        fclose(f);
    }
    closedir(dr);

    if (g_descarga_en_curso) {
        esp_mqtt_client_publish(mqtt_client, MQTT_TOPIC_DATA_DONE, "done", 0, 1, 0);
        ESP_LOGI("DATALOG", "Descarga finalizada correctamente");
    } else {
        ESP_LOGW("DATALOG", "Descarga abortada (App offline)");
    }

    g_descarga_en_curso = false;
    vTaskDelete(NULL);
}

static void Procesar_Status_App(const char *datos, int len) {
    if (strncmp(datos, "online", 6) == 0) {
        if (!g_descarga_en_curso) {
            xTaskCreate(Datalogger_Tarea_Descarga, "tarea_descarga", DATALOG_STACK_SIZE, NULL, DATALOG_PRIORITY, NULL);
        }
    } else if (strncmp(datos, "offline", 7) == 0) {
        g_descarga_en_curso = false; // Esto abortará el bucle de la tarea
    }
}

static void Procesar_ACK_Datalogger(const char *datos, int len) {
    // Formato recibido: "HH:MM:SS" o "NACK:HH:MM:SS"
    if (strncmp(datos, "NACK:", 5) == 0) {
        // Es un NACK, no damos el semáforo para gatillar el reintento
        ESP_LOGW("DATALOG", "NACK recibido de la App");
        return;
    }
    
    int n = len < sizeof(g_last_ack_id) ? len : sizeof(g_last_ack_id) - 1;
    memcpy(g_last_ack_id, datos, n);
    g_last_ack_id[n] = '\0';
    xSemaphoreGive(g_sem_ack);
}

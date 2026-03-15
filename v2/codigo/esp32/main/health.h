#ifndef HEALTH_H
#define HEALTH_H

#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

typedef enum { 
    HEALTH_OK = 0, 
    HEALTH_WARN = 1, 
    HEALTH_FAIL = 2 
} health_state_t;

typedef struct {
    health_state_t ina;
    health_state_t gps;
    health_state_t wifi;
    health_state_t mqtt;
    health_state_t spiffs;
    health_state_t servos;
    health_state_t global;
} health_t;

// Variable global instanciada en main.c
extern health_t g_health;
extern SemaphoreHandle_t health_mutex;

/**
 * @brief Calcula el estado de salud global basado en los componentes individuales.
 * @return health_state_t El peor estado detectado.
 */
health_state_t health_get_global(void);

#endif // HEALTH_H

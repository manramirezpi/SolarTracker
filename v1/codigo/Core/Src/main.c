/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Main program body
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2025 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */
#include "LiquidCrystal_I2C.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */
#define PI 3.14159265358979323846
#define DEFAULT_TIMEZONE -5 // Asumimos Colombia mientras busca satélites
#define RAD(d) ((d) * (PI / 180.0))
#define DEG(r) ((r) * (180.0 / PI))
#define MAXBUFFER 256 // define el tamaño maximo del buffer de comandos

//servos
#define SERVO_MIN_PWM 500  // Equivalente a 0° mecánicos
#define SERVO_MAX_PWM 2500 // Equivalente a 180° mecánicos
#define SERVO_RANGO_PWM (SERVO_MAX_PWM - SERVO_MIN_PWM)
#define UMBRAL_ANTIJUEGO_PWM 1

#define FACTOR_CONVERSION 11.111111f
/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/
I2C_HandleTypeDef hi2c1;

TIM_HandleTypeDef htim1;
TIM_HandleTypeDef htim10;
TIM_HandleTypeDef htim11;

UART_HandleTypeDef huart1;
UART_HandleTypeDef huart2;
DMA_HandleTypeDef hdma_usart2_rx;
DMA_HandleTypeDef hdma_usart2_tx;

/* USER CODE BEGIN PV */
LiquidCrystal_I2C_t lcd = {0};
uint8_t rx_byte;                    // Byte temporal de recepción
uint8_t rx_buffer[250];             // Buffer para la frase completa
uint8_t gps_processing_buffer[250]; // Buffer seguro para procesar en main
uint8_t rx_index = 0;               // Índice del buffer
volatile uint8_t flag_gps = 0;      // Bandera de aviso

uint16_t contador = 0;

// variables para control de servos
uint16_t anchoAzimut = 150;
uint16_t anchoElevacion = 150;


// estructuras de datos
char buffer0[40];          // buffer para la hora
char buffer1[40];          // buffer para la fecha
char buffer2[40];          // buffer para la latitud
char buffer3[40];          // buffer para la longitud
char buffer4[40];          // buffer para la elevacion
char buffer5[40];          // buffer para el azimut
char txConsoleBuffer[100]; // buffer para la transmision por consola

typedef struct {
  char hora[12];     // Formato: HHMMSS.SS
  char fecha[10];    // Formato: DDMMYY
  char latitud[15];  // Formato: DDMM.MMMM
  char lat_dir;      // N o S
  char longitud[15]; // Formato: DDDMM.MMMM
  char lon_dir;      // E o W
  uint8_t es_valido; // 1 si el GPS tiene señal (A), 0 si no (V)
  uint8_t
      tiene_hora; // bandera para ver si ya procesamos la hora en algun momento
} GPS_Data_t;

typedef struct {
  // Entradas (Convertidas desde el GPS)
  double latitud_deg;  // Grados decimales (Norte+, Sur-)
  double longitud_deg; // Grados decimales (Este+, Oeste-)
  int utc_ano;         // Ej: 2025
  int utc_mes;
  int utc_dia;
  int utc_hora;
  int utc_min;
  int utc_seg;
  // Salidas (Resultados del algoritmo solar)
  double elevacion; // Grados (0° horizonte, 90° zenit)
  double azimut;    // Grados (0° Norte, 90° Este, 180° Sur...)
} SolarCalc_t;

typedef struct {
  int dia;
  int mes;
  int ano;
  int hora;
  int min;
  int seg;
  int zona_horaria;
} LocalTime_t;

typedef struct {
  const char *commandName; // Nombre del comando (ej. "SETTIME")
  uint8_t commandId;       // Un ID único para cada comando
} CommandEntry;

typedef struct {
  // Banderas de control (0 = Usar GPS, 1 = Usar Manual)
  uint8_t usar_lat_manual;
  uint8_t usar_lon_manual;
  uint8_t simulacion_activa; // 0 = Tiempo Real, 1 = Acelerado

  // Valores manuales
  double lat_manual;
  double lon_manual;

  uint8_t dia_manual;
  uint8_t mes_manual;
  uint16_t anio_manual;
  uint8_t usar_fecha_manual; // 0 = Usar GPS, 1 = Usar Manual

  // Control de tiempo
  int factor_velocidad;       // Ej: 1, 60, 3600...
  LocalTime_t tiempo_interno; // Este es el reloj que corre en el sistema
  uint32_t ultimo_tick;       // Para calcular el delta de tiempo real
} SystemControl_t;

SystemControl_t sys_ctrl = {
    .factor_velocidad = 1,
    .tiempo_interno = {0},
    .ultimo_tick = 0,
    .usar_fecha_manual = 0,
    .dia_manual = 0,
    .mes_manual = 0,
    .anio_manual = 0
    // El resto de campos se ponen a 0 automáticamente si no los listas
};

// Instancia global
//SystemControl_t sys_ctrl = {0, 0, 0, 0.0, 0.0, 1, {0}, 0};

// enumeracion que almacena las identidades de los comandos y les asigna un
// natural
typedef enum {
  COMMANDIDGETLAT,
  COMMANDIDGETLON,
  COMMANDIDGETFEC,
  COMMANDIDGETHOR,
  COMMANDIDGETAZI,
  COMMANDIDGETELE,
  COMMANDIDSETLAT,
  COMMANDIDSETLON,
  COMMANDIDSETFEC,
  COMMANDIDSETVEL,
  COMMANDIDMODEGPS,
  COMMANDIDCLEAR,
  COMMANDIDHELP,
} CommandId;

const CommandEntry commandTable[] = {{"GetLatitud", COMMANDIDGETLAT},   //
                                     {"GetLongitud", COMMANDIDGETLON},  //
                                     {"GetFecha", COMMANDIDGETFEC},     //
                                     {"GetHora", COMMANDIDGETHOR},      //
                                     {"GetAzimut", COMMANDIDGETAZI},    //
                                     {"GetElevacion", COMMANDIDGETELE}, //
                                     {"SetLatitud", COMMANDIDSETLAT},   //
                                     {"SetLongitud", COMMANDIDSETLON},  //
									 {"SetFecha", COMMANDIDSETFEC},
                                     {"SetVelocidad", COMMANDIDSETVEL}, //
                                     {"ModeGPS", COMMANDIDMODEGPS},     //
                                     {"Clear", COMMANDIDCLEAR},         //
                                     {"Help", COMMANDIDHELP}};          //

const char *helpText = // texto del menu de ayuda que se imprime con el comando
                       // HELP o cuando se inicializa el sistema
    "--------------- GUIA DE COMANDOS ---------------\r\n"
    "Los comandos disponibles son:\r\n"
    "  - GetLatitud\r\n"
    "  - GetLongitud\r\n"
    "  - GetFecha\r\n"
    "  - GetHora\r\n"
    "  - GetAzimut\r\n"
    "  - GetElevacion\r\n"
    "  - SetLatitud <Grados decimales> <N/S>\r\n"
    "  - SetLongitud <Grados decimales> <E/W>\r\n"
	"  - SetFecha <dd> <mm> <aaaa>\r\n"
    "  - SetVelocidad <Factor>\r\n"
    "  - ModeGPS\r\n"
    "  - Clear\r\n"
    "  - Help\r\n"
    "------------------------------------------------\r\n";

const uint8_t dias_por_mes[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
const uint8_t numberOfCommands = sizeof(commandTable) / sizeof(commandTable[0]);
typedef void (*CommandHandlerFunction)(char *parameters[],uint8_t numParameters); // va a guardar las caracteristicas del comando.
                            // Base y arreglo de parametros

GPS_Data_t mi_gps;        // Aqui se guardarán tus resultados
SolarCalc_t gps_solar;    // Aqui guardan los cálculos
LocalTime_t tiempo_local; // Aqui se guarda la hora local en tiempo real

uint8_t dataBufferA[MAXBUFFER]; // buffer de recepcion A
uint8_t dataBufferB[MAXBUFFER]; // buffer de recepcion B
volatile uint8_t currentRxBuffer = 0; // variable que indica el buffer disponilbel.
//0 para dataBufferA, 1 para dataBufferB
volatile uint16_t receivedDataSizeA = 0; // Tamaño datos buffer A
volatile uint16_t receivedDataSizeB = 0; // Tamaño datos buffer B
volatile uint8_t dataBufferAready = 0; // bandera que indica que el buffer A esta listo
volatile uint8_t dataBufferBready = 0; // bandera que indica que el buffer B esta listo
static char *commandParameters[5]; // arreglo que guarda los parametros que vienen con el comando
uint16_t last_pwm_azimut = 150;
uint16_t last_pwm_elevacion = 150;

/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_DMA_Init(void);
static void MX_I2C1_Init(void);
static void MX_TIM10_Init(void);
static void MX_TIM11_Init(void);
static void MX_USART1_UART_Init(void);
static void MX_USART2_UART_Init(void);
static void MX_TIM1_Init(void);
/* USER CODE BEGIN PFP */
void Procesar_Trama_GPRMC(char *buffer);
void Procesar_Tiempo_GPS(void);
void Procesar_Ubicacion_GPS(void);
void Gestionar_Pantalla(void);
void Calcular_Posicion_Solar(void);
void Calcular_Hora_Local(LocalTime_t *local);
uint8_t es_bisiesto(int ano);
void parseCommand(uint8_t *dataBuffer, uint16_t dataSize);
void processReceivedData(void);
void Actualizar_Coordenadas_Calculo(void);
void Gestionar_Tiempo_Sistema(void);
void Incrementar_Tiempo_Simulado(LocalTime_t *t, int segundos_a_sumar);
void Actualizar_Servos(void);
int constrain_pwm(int valor);
/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */

/* USER CODE END 0 */

/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{
  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();

  /* USER CODE BEGIN Init */

  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_DMA_Init();
  MX_I2C1_Init();
  MX_TIM10_Init();
  MX_TIM11_Init();
  MX_USART1_UART_Init();
  MX_USART2_UART_Init();
  MX_TIM1_Init();
  /* USER CODE BEGIN 2 */
  __HAL_DMA_DISABLE_IT(&hdma_usart2_rx, DMA_IT_HT);
  HAL_Delay(50);
  HAL_UART_Transmit_DMA(&huart2, (uint8_t *)helpText, strlen(helpText)); // imprimir el menu de ayuda
  HAL_UARTEx_ReceiveToIdle_DMA(&huart2, dataBufferA, MAXBUFFER); // empezar a escuchar  y almacenar en el buffer A

  LiquidCrystal_I2C_init(&lcd, &hi2c1, 0x23, 20, 4); // se llama a la funcion que inicializa la pantalla
  // se alimenta con el registro de identifiacion: 0x24, 20 caracteres por fila y 4 filas

  LiquidCrystal_I2C_clear(&lcd);
  LiquidCrystal_I2C_setCursor(&lcd, 0, 0); // posiciona el cursor en el primer caracter de la tercera fila
  LiquidCrystal_I2C_print(&lcd, "Se estan recibiendo"); // Longitud: 14 car

  LiquidCrystal_I2C_setCursor(&lcd, 0, 1); // posiciona el cursor en el primer caracter de la cuarta fila
  LiquidCrystal_I2C_print(&lcd, "los datos. Espere."); // Longitud: 8 car

  LiquidCrystal_I2C_setCursor(&lcd, 0, 2); // posiciona el cursor en el primer caracter de la primera fila
  LiquidCrystal_I2C_print(&lcd, "Esto puede tardar"); // Longitud: 12 car

  LiquidCrystal_I2C_setCursor(&lcd, 0, 3); // posiciona el cursos en el primer caracter de la segunda fila
  LiquidCrystal_I2C_print(&lcd, "algunos minutos."); // Longitud: 7 car

  HAL_UART_Receive_IT(&huart1, &rx_byte, 1);

  /* USER CODE END 2 */

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  while (1)
  {
	    if (flag_gps == 1) {
	      flag_gps = 0; // Reset bandera

	      // 1. Parsear el texto crudo
	      // Usamos el buffer de procesamiento seguro, no el rx_buffer que puede estar cambiando
	      Procesar_Trama_GPRMC((char *)gps_processing_buffer);

	      // 2. PROCESAMIENTO DE TIEMPO
	      Procesar_Tiempo_GPS(); // el gps manda la hora incluso sin coordednadas espaciales

	      if (!sys_ctrl.simulacion_activa) {
	        Calcular_Hora_Local(&tiempo_local);}

	      // Esto asegura que tanto la pantalla como el cálculo solar usen la fecha
	      if (sys_ctrl.usar_fecha_manual) {
	          tiempo_local.dia = sys_ctrl.dia_manual;
	          tiempo_local.mes = sys_ctrl.mes_manual;
	          tiempo_local.ano = sys_ctrl.anio_manual;}

	      // 3. PROCESAMIENTO DE ESPACIO (Solo si es válido 'A')
	      if (mi_gps.es_valido) {
	        Procesar_Ubicacion_GPS();} // Convertir texto a double

	      Actualizar_Coordenadas_Calculo();
	      Gestionar_Tiempo_Sistema();
	      Calcular_Posicion_Solar();
	      Gestionar_Pantalla();
	      processReceivedData();
	      Actualizar_Servos();
	    }
    /* USER CODE END WHILE */

    /* USER CODE BEGIN 3 */
  }
  /* USER CODE END 3 */
}

/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};

  /** Configure the main internal regulator output voltage
  */
  __HAL_RCC_PWR_CLK_ENABLE();
  __HAL_PWR_VOLTAGESCALING_CONFIG(PWR_REGULATOR_VOLTAGE_SCALE1);

  /** Initializes the RCC Oscillators according to the specified parameters
  * in the RCC_OscInitTypeDef structure.
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_HSI;
  RCC_OscInitStruct.HSIState = RCC_HSI_ON;
  RCC_OscInitStruct.HSICalibrationValue = RCC_HSICALIBRATION_DEFAULT;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_ON;
  RCC_OscInitStruct.PLL.PLLSource = RCC_PLLSOURCE_HSI;
  RCC_OscInitStruct.PLL.PLLM = 8;
  RCC_OscInitStruct.PLL.PLLN = 100;
  RCC_OscInitStruct.PLL.PLLP = RCC_PLLP_DIV2;
  RCC_OscInitStruct.PLL.PLLQ = 4;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the CPU, AHB and APB buses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV2;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_3) != HAL_OK)
  {
    Error_Handler();
  }
}

/**
  * @brief I2C1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_I2C1_Init(void)
{

  /* USER CODE BEGIN I2C1_Init 0 */

  /* USER CODE END I2C1_Init 0 */

  /* USER CODE BEGIN I2C1_Init 1 */

  /* USER CODE END I2C1_Init 1 */
  hi2c1.Instance = I2C1;
  hi2c1.Init.ClockSpeed = 100000;
  hi2c1.Init.DutyCycle = I2C_DUTYCYCLE_2;
  hi2c1.Init.OwnAddress1 = 0;
  hi2c1.Init.AddressingMode = I2C_ADDRESSINGMODE_7BIT;
  hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
  hi2c1.Init.OwnAddress2 = 0;
  hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
  hi2c1.Init.NoStretchMode = I2C_NOSTRETCH_DISABLE;
  if (HAL_I2C_Init(&hi2c1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN I2C1_Init 2 */

  /* USER CODE END I2C1_Init 2 */

}

/**
  * @brief TIM1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_TIM1_Init(void)
{

  /* USER CODE BEGIN TIM1_Init 0 */

  /* USER CODE END TIM1_Init 0 */

  TIM_ClockConfigTypeDef sClockSourceConfig = {0};
  TIM_MasterConfigTypeDef sMasterConfig = {0};

  /* USER CODE BEGIN TIM1_Init 1 */

  /* USER CODE END TIM1_Init 1 */
  htim1.Instance = TIM1;
  htim1.Init.Prescaler = 10000;
  htim1.Init.CounterMode = TIM_COUNTERMODE_UP;
  htim1.Init.Period = 5000;
  htim1.Init.ClockDivision = TIM_CLOCKDIVISION_DIV1;
  htim1.Init.RepetitionCounter = 0;
  htim1.Init.AutoReloadPreload = TIM_AUTORELOAD_PRELOAD_DISABLE;
  if (HAL_TIM_Base_Init(&htim1) != HAL_OK)
  {
    Error_Handler();
  }
  sClockSourceConfig.ClockSource = TIM_CLOCKSOURCE_INTERNAL;
  if (HAL_TIM_ConfigClockSource(&htim1, &sClockSourceConfig) != HAL_OK)
  {
    Error_Handler();
  }
  sMasterConfig.MasterOutputTrigger = TIM_TRGO_RESET;
  sMasterConfig.MasterSlaveMode = TIM_MASTERSLAVEMODE_DISABLE;
  if (HAL_TIMEx_MasterConfigSynchronization(&htim1, &sMasterConfig) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN TIM1_Init 2 */
  HAL_TIM_Base_Start_IT(&htim1);
  /* USER CODE END TIM1_Init 2 */

}

/**
  * @brief TIM10 Initialization Function
  * @param None
  * @retval None
  */
static void MX_TIM10_Init(void)
{

  /* USER CODE BEGIN TIM10_Init 0 */

  /* USER CODE END TIM10_Init 0 */

  TIM_OC_InitTypeDef sConfigOC = {0};

  /* USER CODE BEGIN TIM10_Init 1 */

  /* USER CODE END TIM10_Init 1 */
  htim10.Instance = TIM10;
  htim10.Init.Prescaler = 100;
  htim10.Init.CounterMode = TIM_COUNTERMODE_UP;
  htim10.Init.Period = 20000;
  htim10.Init.ClockDivision = TIM_CLOCKDIVISION_DIV1;
  htim10.Init.AutoReloadPreload = TIM_AUTORELOAD_PRELOAD_ENABLE;
  if (HAL_TIM_Base_Init(&htim10) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_TIM_PWM_Init(&htim10) != HAL_OK)
  {
    Error_Handler();
  }
  sConfigOC.OCMode = TIM_OCMODE_PWM1;
  sConfigOC.Pulse = 1500;
  sConfigOC.OCPolarity = TIM_OCPOLARITY_LOW;
  sConfigOC.OCFastMode = TIM_OCFAST_DISABLE;
  if (HAL_TIM_PWM_ConfigChannel(&htim10, &sConfigOC, TIM_CHANNEL_1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN TIM10_Init 2 */
  HAL_TIM_PWM_Start(&htim10, TIM_CHANNEL_1);
  /* USER CODE END TIM10_Init 2 */
  HAL_TIM_MspPostInit(&htim10);

}

/**
  * @brief TIM11 Initialization Function
  * @param None
  * @retval None
  */
static void MX_TIM11_Init(void)
{

  /* USER CODE BEGIN TIM11_Init 0 */

  /* USER CODE END TIM11_Init 0 */

  TIM_OC_InitTypeDef sConfigOC = {0};

  /* USER CODE BEGIN TIM11_Init 1 */

  /* USER CODE END TIM11_Init 1 */
  htim11.Instance = TIM11;
  htim11.Init.Prescaler = 100;
  htim11.Init.CounterMode = TIM_COUNTERMODE_UP;
  htim11.Init.Period = 20000;
  htim11.Init.ClockDivision = TIM_CLOCKDIVISION_DIV1;
  htim11.Init.AutoReloadPreload = TIM_AUTORELOAD_PRELOAD_ENABLE;
  if (HAL_TIM_Base_Init(&htim11) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_TIM_PWM_Init(&htim11) != HAL_OK)
  {
    Error_Handler();
  }
  sConfigOC.OCMode = TIM_OCMODE_PWM1;
  sConfigOC.Pulse = 1500;
  sConfigOC.OCPolarity = TIM_OCPOLARITY_LOW;
  sConfigOC.OCFastMode = TIM_OCFAST_DISABLE;
  if (HAL_TIM_PWM_ConfigChannel(&htim11, &sConfigOC, TIM_CHANNEL_1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN TIM11_Init 2 */
  HAL_TIM_PWM_Start(&htim11, TIM_CHANNEL_1);
  /* USER CODE END TIM11_Init 2 */
  HAL_TIM_MspPostInit(&htim11);

}

/**
  * @brief USART1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_USART1_UART_Init(void)
{

  /* USER CODE BEGIN USART1_Init 0 */

  /* USER CODE END USART1_Init 0 */

  /* USER CODE BEGIN USART1_Init 1 */

  /* USER CODE END USART1_Init 1 */
  huart1.Instance = USART1;
  huart1.Init.BaudRate = 9600;
  huart1.Init.WordLength = UART_WORDLENGTH_8B;
  huart1.Init.StopBits = UART_STOPBITS_1;
  huart1.Init.Parity = UART_PARITY_NONE;
  huart1.Init.Mode = UART_MODE_TX_RX;
  huart1.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart1.Init.OverSampling = UART_OVERSAMPLING_16;
  if (HAL_UART_Init(&huart1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART1_Init 2 */
  HAL_UART_Receive_IT(&huart1, &rx_byte, 1);
  /* USER CODE END USART1_Init 2 */

}

/**
  * @brief USART2 Initialization Function
  * @param None
  * @retval None
  */
static void MX_USART2_UART_Init(void)
{

  /* USER CODE BEGIN USART2_Init 0 */

  /* USER CODE END USART2_Init 0 */

  /* USER CODE BEGIN USART2_Init 1 */

  /* USER CODE END USART2_Init 1 */
  huart2.Instance = USART2;
  huart2.Init.BaudRate = 9600;
  huart2.Init.WordLength = UART_WORDLENGTH_8B;
  huart2.Init.StopBits = UART_STOPBITS_1;
  huart2.Init.Parity = UART_PARITY_NONE;
  huart2.Init.Mode = UART_MODE_TX_RX;
  huart2.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart2.Init.OverSampling = UART_OVERSAMPLING_16;
  if (HAL_UART_Init(&huart2) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART2_Init 2 */

  /* USER CODE END USART2_Init 2 */

}

/**
  * Enable DMA controller clock
  */
static void MX_DMA_Init(void)
{

  /* DMA controller clock enable */
  __HAL_RCC_DMA1_CLK_ENABLE();

  /* DMA interrupt init */
  /* DMA1_Stream5_IRQn interrupt configuration */
  HAL_NVIC_SetPriority(DMA1_Stream5_IRQn, 0, 0);
  HAL_NVIC_EnableIRQ(DMA1_Stream5_IRQn);
  /* DMA1_Stream6_IRQn interrupt configuration */
  HAL_NVIC_SetPriority(DMA1_Stream6_IRQn, 0, 0);
  HAL_NVIC_EnableIRQ(DMA1_Stream6_IRQn);

}

/**
  * @brief GPIO Initialization Function
  * @param None
  * @retval None
  */
static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
/* USER CODE BEGIN MX_GPIO_Init_1 */
/* USER CODE END MX_GPIO_Init_1 */

  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOH_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOH, GPIO_PIN_1, GPIO_PIN_RESET);

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(LD2_GPIO_Port, LD2_Pin, GPIO_PIN_RESET);

  /*Configure GPIO pin : PH1 */
  GPIO_InitStruct.Pin = GPIO_PIN_1;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOH, &GPIO_InitStruct);

  /*Configure GPIO pin : LD2_Pin */
  GPIO_InitStruct.Pin = LD2_Pin;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(LD2_GPIO_Port, &GPIO_InitStruct);

/* USER CODE BEGIN MX_GPIO_Init_2 */
/* USER CODE END MX_GPIO_Init_2 */
}

/* USER CODE BEGIN 4 */

// Función para extraer Hora, Fecha y Coordenadas de $GPRMC
void Procesar_Trama_GPRMC(char *buffer) {
  // 1. Verificamos encabezado
  if (strncmp(buffer, "$GPRMC", 6) != 0)
    return;

  // Punteros para recorrer la cadena manualmente
  char *start = buffer;
  char *end;
  int campo = 0;

  // Recorremos tod el buffer buscando comas o el asterisco final
  while ((end = strpbrk(start, ",*")) != NULL) {
    // Temporalmente convertimos la coma en un final de string '\0' para poder leer solo esa parte
    char backup_char = *end;
    *end = '\0';

    // 'start' ahora apunta al texto del campo actual
    // Calculamos longitud para no copiar vacíos
    if (strlen(start) > 0) {
      switch (campo) {
      case 1: // Hora
        strncpy(mi_gps.hora, start, sizeof(mi_gps.hora) - 1);
        // Aseguramos terminación nula por seguridad
        mi_gps.hora[sizeof(mi_gps.hora) - 1] = '\0';
        break;
      case 2: // Estado (A/V)
        mi_gps.es_valido = (start[0] == 'A') ? 1 : 0;
        break;
      case 3: // Latitud
        strncpy(mi_gps.latitud, start, sizeof(mi_gps.latitud) - 1);
        break;
      case 4: // Latitud N/S
        mi_gps.lat_dir = start[0];
        break;
      case 5: // Longitud
        strncpy(mi_gps.longitud, start, sizeof(mi_gps.longitud) - 1);
        break;
      case 6: // Longitud E/W
        mi_gps.lon_dir = start[0];
        break;
      case 9: // Fecha (Ahora sí llegará aquí aunque la velocidad esté vacía)
        strncpy(mi_gps.fecha, start, sizeof(mi_gps.fecha) - 1);
        break;
      }
    } else {
      // Si el campo está vacío (ej. velocidad), no hacemos nada, pero el contador 'campo' aumentará igual.
    }

    // Restauramos el caracter
    *end = backup_char;

    // Si encontramos el asterisco, terminamos
    if (*end == '*')
      break;

    // Avanzamos al siguiente caracter después de la coma
    start = end + 1;
    campo++;
  }
}

void Gestionar_Pantalla(void) {
  if (!mi_gps.tiene_hora)
    return;
  // mostrar hora y fecha (cuando se tengan datos de la hora unicamente)
  LiquidCrystal_I2C_setCursor(&lcd, 0, 0);
  if (mi_gps.es_valido) {
    sprintf(buffer0, "Hora local: %02d:%02d:%02d", tiempo_local.hora, tiempo_local.min, tiempo_local.seg);}
  else {
    sprintf(buffer0, "Hora col: %02d:%02d:%02d  ", tiempo_local.hora, tiempo_local.min, tiempo_local.seg);}

  LiquidCrystal_I2C_print(&lcd, buffer0);
  LiquidCrystal_I2C_setCursor(&lcd, 0, 1);

  char fuente_tiempo = sys_ctrl.simulacion_activa ? 'S' : (sys_ctrl.usar_fecha_manual ? 'M' : 'G');
  sprintf(buffer1, "Fecha: %02d/%02d/%04d %c ", tiempo_local.dia,  tiempo_local.mes, tiempo_local.ano, fuente_tiempo);
  LiquidCrystal_I2C_print(&lcd, buffer1);

  if (mi_gps.es_valido) {
    LiquidCrystal_I2C_setCursor(&lcd, 0, 2);
    // [S] = Simulado/Acelerado, [G] = GPS Real
    char src_lon = sys_ctrl.usar_lon_manual ? 'M' : 'G';
    char src_lat = sys_ctrl.usar_lat_manual ? 'M' : 'G';
    char src_time = sys_ctrl.simulacion_activa ? 'S' : 'G';
    char src_fecha = sys_ctrl.usar_fecha_manual ? 'M' : 'G';
    char src_calc = 'G'; // Asumimos GPS por defecto
    if (src_time == 'S' || src_lat == 'M' || src_lon == 'M' || src_fecha == 'M') {
      src_calc = 'M';
    } // El resultado es matemático/manual

    sprintf(buffer5, "Azimut: %06.2f%c %c   ", gps_solar.azimut, 0xDF, src_calc);
    LiquidCrystal_I2C_print(&lcd, buffer5);

    LiquidCrystal_I2C_setCursor(&lcd, 0, 3);
    sprintf(buffer4, "Elevacion: %06.2f%c %c", gps_solar.elevacion, 0xDF, src_calc);
    LiquidCrystal_I2C_print(&lcd, buffer4);}
}

void Procesar_Tiempo_GPS(void) {
  // Solo procedemos si la cadena de hora tiene datos
  if (strlen(mi_gps.hora) < 6)
    return;//si es menor que 6, no hacemos nada

  // Hora UTC
  double h_val = atof(mi_gps.hora);
  gps_solar.utc_hora = (int)(h_val / 10000);
  gps_solar.utc_min = (int)((h_val - (gps_solar.utc_hora * 10000)) / 100);
  gps_solar.utc_seg = (int)h_val % 100;

  // Fecha UTC
  if (strlen(mi_gps.fecha) == 6) {
    int f_val = atoi(mi_gps.fecha);
    gps_solar.utc_dia = f_val / 10000;
    gps_solar.utc_mes = (f_val % 10000) / 100;
    gps_solar.utc_ano = 2000 + (f_val % 100);
    mi_gps.tiene_hora = 1;
  }
}

void Procesar_Ubicacion_GPS(void) {
  // Latitud
  double raw = atof(mi_gps.latitud);
  int deg = (int)(raw / 100);
  gps_solar.latitud_deg = deg + ((raw - (deg * 100)) / 60.0);
  if (mi_gps.lat_dir == 'S')
    gps_solar.latitud_deg *= -1.0;

  // Longitud
  raw = atof(mi_gps.longitud);
  deg = (int)(raw / 100);
  gps_solar.longitud_deg = deg + ((raw - (deg * 100)) / 60.0);
  if (mi_gps.lon_dir == 'W')
    gps_solar.longitud_deg *= -1.0;
}

void Calcular_Posicion_Solar(void) {
  // Variables auxiliares
	int Y, M, D;
  double d_juliano, n_dia;
  double g_media, l_media, l_ecliptica;
  double oblicuidad, declinacion, ascension_recta;
  double gmst, lmst, angulo_horario;

  if (sys_ctrl.usar_fecha_manual) {
       // Si el usuario fijó fecha manual, usamos esa
       Y = sys_ctrl.anio_manual;
       M = sys_ctrl.mes_manual;
       D = sys_ctrl.dia_manual;}
  else {
       // Si no, usamos la fecha que viene del GPS o de la Simulación
       Y = gps_solar.utc_ano;
       M = gps_solar.utc_mes;
       D = gps_solar.utc_dia;}

  double H_decimal = gps_solar.utc_hora + (gps_solar.utc_min / 60.0) +
                     (gps_solar.utc_seg / 3600.0);

  // 1. Calcular Día Juliano (Algoritmo estándar)
  if (M <= 2) {
    Y -= 1;
    M += 12;
  }
  int A = Y / 100;
  int B = 2 - A + (A / 4);
  d_juliano =
      (int)(365.25 * (Y + 4716)) + (int)(30.6001 * (M + 1)) + D + B - 1524.5;
  d_juliano += H_decimal / 24.0; // Añadir fracción del día

  // 2. Siglos Julianos desde J2000.0
  n_dia = d_juliano - 2451545.0;

  // 3. Coordenadas solares medias (en Grados)
  l_media = fmod(280.460 + 0.9856474 * n_dia, 360.0);
  g_media = fmod(357.528 + 0.9856003 * n_dia, 360.0);
  if (l_media < 0)
    l_media += 360;
  if (g_media < 0)
    g_media += 360;

  // 4. Longitud Eclíptica
  l_ecliptica = l_media + 1.915 * sin(RAD(g_media)) + 0.020 * sin(RAD(2 * g_media));

  // 5. Oblicuidad de la eclíptica (Inclinación de la Tierra)
  oblicuidad = 23.439 - 0.0000004 * n_dia;

  // 6. Ascensión Recta (RA) y Declinación (Dec)
  double sin_l = sin(RAD(l_ecliptica));
  double cos_l = cos(RAD(l_ecliptica));
  double cos_eps = cos(RAD(oblicuidad));
  double sin_eps = sin(RAD(oblicuidad));

  declinacion = DEG(asin(sin_eps * sin_l));
  ascension_recta = DEG(atan2(cos_eps * sin_l, cos_l));

  // 7. Tiempo Sideral de Greenwich (GMST) y Local (LMST)
  gmst = 6.697375 + 0.0657098242 * n_dia + H_decimal;
  gmst = fmod(gmst, 24.0);
  if (gmst < 0)
    gmst += 24.0;

  lmst = gmst + (gps_solar.longitud_deg / 15.0); // Longitud en horas
  lmst = fmod(lmst, 24.0);
  if (lmst < 0)
    lmst += 24.0;

  // 8. Ángulo Horario (Hour Angle - omega) en grados convertir RA a horas (dividiendo por 15) para restar
  angulo_horario = (lmst * 15.0) - ascension_recta;
  while (angulo_horario > 180)
    angulo_horario -= 360;
  while (angulo_horario < -180)
    angulo_horario += 360;

  // 9. COORDENADAS HORIZONTALES FINALES (Azimut y Elevación)
  double lat_rad = RAD(gps_solar.latitud_deg);
  double dec_rad = RAD(declinacion);
  double omega_rad = RAD(angulo_horario);

  // Elevación (Altura solar)
  double sin_el = sin(lat_rad) * sin(dec_rad) +
                  cos(lat_rad) * cos(dec_rad) * cos(omega_rad);
  gps_solar.elevacion = DEG(asin(sin_el));

  // Azimut (Desde el Norte, sentido horario)
  double y = -sin(omega_rad);
  double x = tan(dec_rad) * cos(lat_rad) - sin(lat_rad) * cos(omega_rad);
  gps_solar.azimut = DEG(atan2(y, x));

  if (gps_solar.azimut < 0)
    gps_solar.azimut += 360.0;
}

uint8_t es_bisiesto(int ano) {
  return ((ano % 4 == 0 && ano % 100 != 0) || (ano % 400 == 0));
}

void Calcular_Hora_Local(LocalTime_t *local) {
  if (mi_gps.es_valido) {
    // Si tenemos ubicación, calculamos la zona real
    local->zona_horaria = (int)(round(gps_solar.longitud_deg / 15.0));
  } else {
    // Si NO tenemos ubicación, usamos la por defecto (Colombia)
    local->zona_horaria = DEFAULT_TIMEZONE;
  }

  // 1. Calcular Zona Horaria automática basada en la Longitud
  // Se divide por 15.0 y se redondea.

  // Copiamos datos base
  local->seg = gps_solar.utc_seg;
  local->min = gps_solar.utc_min;
  local->hora = gps_solar.utc_hora + local->zona_horaria; // Aplicamos offset

  local->dia = gps_solar.utc_dia;
  local->mes = gps_solar.utc_mes;
  local->ano = gps_solar.utc_ano;

  // CASO A: Pasamos al día anterior (Ej: 02:00 UTC - 5 = -3)
  if (local->hora < 0) {
    local->hora += 24;
    local->dia--; // Restamos un día

    // Si el día llega a 0, hay que volver al mes anterior
    if (local->dia == 0) {
      local->mes--;
      if (local->mes ==
          0) { // Si bajamos de Enero, vamos a Dic del año anterior
        local->mes = 12;
        local->ano--;
      }
      // Asignar el último día del mes nuevo
      if (local->mes == 2 && es_bisiesto(local->ano)) {
        local->dia = 29;
      } else {
        local->dia = dias_por_mes[local->mes - 1];
      }
    }
  }

  // CASO B: Pasamos al día siguiente (Ej: 23:00 UTC + 2 = 25)
  else if (local->hora >= 24) {
    local->hora -= 24;
    local->dia++; // Sumamos un día

    // Verificar si nos pasamos de los días del mes actual
    int dias_este_mes = dias_por_mes[local->mes - 1];
    if (local->mes == 2 && es_bisiesto(local->ano))
      dias_este_mes = 29;

    if (local->dia > dias_este_mes) {
      local->dia = 1; // Primero del siguiente mes
      local->mes++;
      if (local->mes > 12) { // Si pasamos Diciembre, Feliz Año Nuevo
        local->mes = 1;
        local->ano++;
      }
    }
  }
}

void processReceivedData(void){// funcion que llama a quien procesa el comando recibido
  if (dataBufferAready == 1){// si se levanta la bandera de recepcion en el bufferA
    dataBufferAready = 0; // bajamos la bandera
    parseCommand(dataBufferA, receivedDataSizeA);} //y llamamos a quien procesa y reacciona al comando recibido

  if (dataBufferBready == 1){//si se levanta la bandera de recepcion en el bufferB
    dataBufferBready = 0;//se baja la bandera
    parseCommand(dataBufferB, receivedDataSizeB);} // y llamamos a quien procesa y reacciona al comando recibido
}

// funcion busca el comando recibido y le asocia el numero correspondiente de la enumeracion
static int findCommandIndex(const char *name) {
  for (uint8_t i = 0; i < numberOfCommands; i++) {
    if (strcmp(name, commandTable[i].commandName) == 0) {
      return i;} // retorna el indice de la enumeracion que coincidió
  }
  return -1;
} // Comando no encontrado

void parseCommand(uint8_t *dataBuffer, uint16_t dataSize){// funcion que recibe el buffer
	//y la cantidad de caracteres recibidos sin incluir el \0 al final. La dma no lo agrega
  if (dataBuffer == NULL || dataSize == 0){ // si el buffer está vacio o solo contiene el caracter nulo
    // manejarError("ERROR: Buffer de datos nulo o vacío\r\n");
    return;}

  if (dataSize >= MAXBUFFER){ // si el tamaño del comando supera el tamaño del buffer
    dataBuffer[MAXBUFFER - 1] = '\0';}// reemplaza el ultimo caracter por el nulo
  else { // si no lo supera
    dataBuffer[dataSize] = '\0';}//despues del ultimo caracter escribir el nulo

  char *token;// puntero para almacenar cada parte del comando
  uint8_t paramCount = 0; // inicializamos una variable que va almacenar la cantidad de parametros del comando

  // se usa " \r\n" como delimitador, permitiendo distingir separaciones de enter o espacio
  token = strtok(
      (char *)dataBuffer,
      " \r\n"); // Se separan los parametros por " \r\n" y se alamcenan en token
  if (token == NULL){ // No se encontró ningún token válido.
    return;
  } // salir y no hacer nada mas

  char *commandName = token;
  // bucle para obtener los parametros que vienen en el comando
  // solamente considerando como delimitador el espacio
  while ((token = strtok(NULL, " ")) != NULL && paramCount < 3){
    commandParameters[paramCount++] = token;}//y se alamcenan en commandParameters

  // se busca el comando en la tabla
  int commandIndex = findCommandIndex(commandName);

  if (commandIndex == -1){// Comando no reconocido
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Comando no reconocido\r\n", strlen("Comando no reconocido\r\n"));
    return;}

  char src_time = sys_ctrl.simulacion_activa ? 'S' : 'G';
  char src_lat = sys_ctrl.usar_lat_manual ? 'M' : 'G';
  char src_lon = sys_ctrl.usar_lon_manual ? 'M' : 'G';
  char src_fecha = sys_ctrl.usar_fecha_manual ? 'M' : 'G';
  char src_calc = 'G'; // Asumimos GPS por defecto
  if (src_time == 'S' || src_lat == 'M' || src_lon == 'M' || src_fecha == 'M'){
    src_calc = 'M';}//El resultado es matemático/manual

  if (src_time == 'S' || src_fecha == 'M'){
    src_time = 'M';}//El resultado es matemático/manual
  const CommandEntry *cmdEntry = &commandTable[commandIndex];
  switch (cmdEntry->commandId){
    /*
        COMMANDIDGETLAT,//
        COMMANDIDGETLON,//
        COMMANDIDGETFEC,//
        COMMANDIDGETHOR,//
        COMMANDIDGETAZI,//
        COMMANDIDGETELE,
        COMMANDIDSETLAT,
        COMMANDIDSETLON,
        COMMANDIDSETVEL,
        COMMANDIDMODEGPS,
        COMMANDIDCLEAR,
        COMMANDIDHELP,
     */
  case COMMANDIDGETLAT:
    sprintf(txConsoleBuffer, "Latitud: %.5f %c [%c]\r\n", fabs(gps_solar.latitud_deg),
            (gps_solar.latitud_deg >= 0) ? 'N' : 'S', src_lat);
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)txConsoleBuffer, strlen(txConsoleBuffer));
    break;

  case COMMANDIDGETLON:
    sprintf(txConsoleBuffer, "Longitud: %.5f %c [%c]\r\n",fabs(gps_solar.longitud_deg),
            (gps_solar.longitud_deg >= 0) ? 'E' : 'W', src_lon);
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)txConsoleBuffer, strlen(txConsoleBuffer));
    break;

  case COMMANDIDGETFEC:
    sprintf(txConsoleBuffer, "Fecha: %02d/%02d/%04d [%c]\r\n", tiempo_local.dia,
            tiempo_local.mes, tiempo_local.ano, src_time);
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)txConsoleBuffer, strlen(txConsoleBuffer));
    break;

  case COMMANDIDGETHOR:
    sprintf(txConsoleBuffer, "Hora Local: %02d:%02d:%02d [%c]\r\n",
            tiempo_local.hora, tiempo_local.min, tiempo_local.seg, src_time);
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)txConsoleBuffer, strlen(txConsoleBuffer));
    break;

  case COMMANDIDGETAZI:
    sprintf(txConsoleBuffer, "Azimut: %.2f° [%c]\r\n", gps_solar.azimut, src_calc);
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)txConsoleBuffer, strlen(txConsoleBuffer));
    break;

  case COMMANDIDGETELE:
    sprintf(txConsoleBuffer, "Elevacion: %.2f° [%c]\r\n", gps_solar.elevacion, src_calc);
    HAL_UART_Transmit(&huart2, (uint8_t *)txConsoleBuffer, strlen(txConsoleBuffer), 100);
    break;

  case COMMANDIDSETLAT: // mostrar el valor de la latitud
    // condiciones para que se ejecute correctamnete:
    // valorLatitud entre 0 y 90, cantidad de parametros igual a 2
    // commandParameters[0] > 90.0f, paramCount == 2, commandParameters[0] <
    // -90.0f con base a lo anterior se hace el manejo de las excepciones y se
    // imprime el mensaje. Solo en los casos que el error es critico no se
    // ejecuta la accion
    if (paramCount != 2){
      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Se requieren 2 parametros\r\n",
                            strlen("Se requieren 2 parametros\r\n"));
      break;}

    float valorLatitud = atof(commandParameters[0]); //

    if (valorLatitud < 0.0f || valorLatitud > 90.0f){
      HAL_UART_Transmit_DMA(&huart2,(uint8_t *)"El valor de la latitud esta por fuera del rango (0 a 90)\r\n",
          strlen("El valor de la latitud esta por fuera del rango (0 a 90)\r\n"));
      break;}

    if ((strcmp(commandParameters[1], "S\r\n") != 0) && (strcmp(commandParameters[1], "N\r\n") != 0)){
      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"No se reconoce la direccion (N o S)\r\n",
          strlen("No se reconoce la direccion (N o S)\r\n"));
      break;}

    if (strcmp(commandParameters[1], "N\r\n") == 0){
      valorLatitud = valorLatitud * (1.0f);}

    if (strcmp(commandParameters[1], "S\r\n") == 0){
      valorLatitud = valorLatitud * (-1.0f);}

    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Fijando latitud\r\n", strlen("Fijando latitud\r\n"));
    sys_ctrl.lat_manual = valorLatitud;
    sys_ctrl.usar_lat_manual = 1; // Activamos bandera manual solo para latitud
    break;

  case COMMANDIDSETLON: // mostrar el valor de la latitud
    // condiciones para que se ejecute correctamnete:
    // valorLatitud entre 0 y 90, cantidad de parametros igual a 2
    // commandParameters[0] > 90.0f, paramCount == 2, commandParameters[0] <
    // -90.0f con base a lo anterior se hace el manejo de las excepciones y se
    // imprime el mensaje. Solo en los casos que el error es critico no se
    // ejecuta la accion
    if (paramCount != 2){
      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Se requieren 2 parametros\r\n",
                            strlen("Se requieren 2 parametros\r\n"));
      break;}

    float valorLongitud = atof(commandParameters[0]); //

    if (valorLongitud < 0.0f || valorLongitud > 180.0f){
      HAL_UART_Transmit_DMA(&huart2,(uint8_t *)"El valor de la longitud esta por fuera del rango (0 a 180)\r\n",
          strlen("El valor de la latitud esta por fuera del rango (0 a 180)\r\n"));
      break;}

    if ((strcmp(commandParameters[1], "E\r\n") != 0) && (strcmp(commandParameters[1], "W\r\n") != 0)){
      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"No se reconoce la direccion (E o W)\r\n",
          strlen("No se reconoce la direccion (E o W)\r\n"));
      break;}

    if (strcmp(commandParameters[1], "E\r\n") == 0){
      valorLongitud = valorLongitud * (1.0f);}

    if (strcmp(commandParameters[1], "W\r\n") == 0){
      valorLongitud = valorLongitud * (-1.0f);}

    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Fijando longitud\r\n", strlen("Fijando longitud\r\n"));
    sys_ctrl.lon_manual = valorLongitud;
    sys_ctrl.usar_lon_manual = 1; // Activamos bandera manual solo para longitud
    break;

  case COMMANDIDSETFEC:
	    if (paramCount != 3){
	      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error: Se requieren 3 parametros (DD MM AAAA)\r\n",
	                            strlen("Error: Se requieren 3 parametros (DD MM AAAA)\r\n"));
	      break;}

	    // Convertimos los cadenas a enteros usando atoi
	    // atoi es seguro porque ignora el \r\n que pueda venir en el último parámetro
	    int dia_in = atoi(commandParameters[0]);
	    int mes_in = atoi(commandParameters[1]);
	    int anio_in = atoi(commandParameters[2]);

	    // --- VALIDACIONES DE RANGO ---

	    // 1. Validar Año
	    if (anio_in < 2000 || anio_in > 2100){
	      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error: Anio fuera de rango (2000-2100)\r\n",
	          strlen("Error: Anio fuera de rango (2000-2100)\r\n"));
	      break;}

	    // 2. Validar Mes (1 a 12)
	    if (mes_in < 1 || mes_in > 12){
	      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error: Mes fuera de rango (1-12)\r\n",
	          strlen("Error: Mes fuera de rango (1-12)\r\n"));
	      break;}

	    // 3. Validar Día (Validación básica 1-31)
	    if (dia_in < 1 || dia_in > 31){
	       HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error: Dia fuera de rango (1-31)\r\n",
	          strlen("Error: Dia fuera de rango (1-31)\r\n"));
	       break;}

	    // Validación extra: Meses de 30 días y Febrero
	    if ((mes_in == 4 || mes_in == 6 || mes_in == 9 || mes_in == 11) && dia_in > 30) {
	       HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error: Ese mes solo tiene 30 dias\r\n",
	          strlen("Error: Ese mes solo tiene 30 dias\r\n"));
	       break;}
	    if (mes_in == 2 && dia_in > 29) { // Simplificado (acepta 29 para bisiestos siempre)
	       HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error: Febrero no tiene mas de 29 dias\r\n",
	          strlen("Error: Febrero no tiene mas de 29 dias\r\n"));
	       break;}

	    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Fijando fecha\r\n", strlen("Fijando fecha\r\n"));
	      sys_ctrl.dia_manual = (uint8_t)dia_in;
	      sys_ctrl.mes_manual = (uint8_t)mes_in;
	      sys_ctrl.anio_manual = (uint16_t)anio_in;

	      // Activamos la bandera para que el main sepa que debe ignorar la fecha GPS
	      sys_ctrl.usar_fecha_manual = 1;
	  break;


  case COMMANDIDSETVEL:
    float factorVelocidad = atoi(commandParameters[0]);
    if (factorVelocidad < 1 || factorVelocidad > 1440.0f) {
      HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"El valor del factor esta por fuera del rango (1 a 1440)\r\n",
                            strlen("El valor del factor esta por fuera del rango (1 a 1440)\r\n"));
      break;}

    sys_ctrl.factor_velocidad = factorVelocidad;
    sys_ctrl.simulacion_activa = 1; // Activamos modo simulación
    sys_ctrl.tiempo_interno = tiempo_local;
    sys_ctrl.ultimo_tick = HAL_GetTick();
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Fijando factor de velocidad\r\n", strlen("Fijando factor de velocidad\r\n"));


    break;

  case COMMANDIDMODEGPS:
    sys_ctrl.usar_lat_manual = 0;
    sys_ctrl.usar_lon_manual = 0;
    sys_ctrl.simulacion_activa = 0;
    sys_ctrl.factor_velocidad = 1;
    sys_ctrl.usar_fecha_manual = 0;
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Restaurando al modo GPS\r\n", strlen("Restaurando al modo GPS\r\n"));
    break;

  case COMMANDIDCLEAR:
    HAL_UART_Transmit_DMA(
        &huart2, (uint8_t *)"\x1b[2J",
        strlen("\x1b[2J")); // limpiar la consola por comandos
   break;

  case COMMANDIDHELP:
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)helpText, strlen(helpText)); // imprimir el menu de ayuda
    break;

  default:
    HAL_UART_Transmit_DMA(&huart2, (uint8_t *)"Error1\r\n", strlen("Error1\r\n")); // caso muy improbable
    break;
  }
}

// Llama a esta función justo antes de Calcular_Posicion_Solar()
void Actualizar_Coordenadas_Calculo(void) {
  // 1. Latitud: Si es manual usa la guardada, si no, la del GPS
  if (sys_ctrl.usar_lat_manual) {
    gps_solar.latitud_deg = sys_ctrl.lat_manual;
  } else {
    // Asumimos que Procesar_Ubicacion_GPS() ya llenó gps_solar con datos GPS no hacemos nada, dejamos el valor del GPS
  }

  // 2. Longitud: Independiente de la latitud
  if (sys_ctrl.usar_lon_manual) {
    gps_solar.longitud_deg = sys_ctrl.lon_manual;
  } else {
    // Dejamos el valor del GPS
  }
}

void Gestionar_Tiempo_Sistema(void) {
  uint32_t tick_actual = HAL_GetTick();

  // Si ha pasado al menos 1 segundo real (1000 ms)
  if (tick_actual - sys_ctrl.ultimo_tick >= 1000) {
    sys_ctrl.ultimo_tick = tick_actual;

    // CASO 1: MODO GPS / TIEMPO REAL
    if (!sys_ctrl.simulacion_activa) {
      // Sincronizamos con lo que diga el GPS o el reloj local normal
      sys_ctrl.tiempo_interno = tiempo_local;
    }

    // CASO 2: MODO SIMULACIÓN (Cámara Rápida)
    else {
      // Incrementamos el tiempo interno basado en la velocidad
      Incrementar_Tiempo_Simulado(&sys_ctrl.tiempo_interno, sys_ctrl.factor_velocidad);}
  }

  if (sys_ctrl.simulacion_activa){
    // Actualizamos la variable global 'tiempo_local' para que la pantalla y los cálculos usen esta hora simulada
    tiempo_local = sys_ctrl.tiempo_interno;
    int zona_horaria = (int)(gps_solar.longitud_deg / 15.0);

    // Fórmula: UTC = Local - Zona
    // Ej: 06:00 - (-5) = 11:00
    int calculo_utc_hora = sys_ctrl.tiempo_interno.hora - zona_horaria;

    // C. Normalizamos el desborde (0-23)
    if (calculo_utc_hora >= 24){
      calculo_utc_hora -= 24;}
     else if (calculo_utc_hora < 0){
      calculo_utc_hora += 24;}

    // Actualizamos la estructura solar para el cálculo astronómico
    gps_solar.utc_hora =
    calculo_utc_hora; // ajustar zona horaria si es necesario
    gps_solar.utc_min = tiempo_local.min;
    gps_solar.utc_seg = tiempo_local.seg;
    gps_solar.utc_dia = tiempo_local.dia;
    gps_solar.utc_mes = tiempo_local.mes;
    gps_solar.utc_ano = tiempo_local.ano;
  }
}

void Incrementar_Tiempo_Simulado(LocalTime_t *t, int segundos_a_sumar) {
  // Sumamos los segundos del factor de velocidad
  t->seg += segundos_a_sumar;

  // Ajuste de Segundos -> Minutos
  while (t->seg >= 60){
    t->seg -= 60;
    t->min++;}

  // Ajuste de Minutos -> Horas
  while (t->min >= 60){
    t->min -= 60;
    t->hora++;}

  // Ajuste de Horas -> Días
  while (t->hora >= 24){
    t->hora -= 24;
    t->dia++;

    // Verificamos cuántos días tiene el mes actual
    int dias_max = dias_por_mes[t->mes - 1]; // -1 porque array empieza en 0

    // Ajuste bisiesto para Febrero (Mes 2)
    if (t->mes == 2 && es_bisiesto(t->ano)){
      dias_max = 29;}

    // Ajuste de Días -> Meses
    if (t->dia > dias_max){
      t->dia = 1;
      t->mes++;

      // Ajuste de Meses -> Años
      if (t->mes > 12){
        t->mes = 1;
        t->ano++;
      }
    }
  }
}


// Función para restringir valores (Clamping)
int constrain_pwm(int valor) {
    if (valor < 500) return 500;
    if (valor > 2500) return 2500;
    return valor;}

void Actualizar_Servos(void){
    float az_real = gps_solar.azimut;
    float el_real = gps_solar.elevacion;

    //float az_real = gps_solar.azimut;
    //float el_real = 90;

    float az_objetivo_base = 0.0;
    float el_angulo_fisico = 0.0;

    // Rango Normal: El sol está entre el Este (90) y el Oeste (270), pasando por el Sur (180). En este rango, el servo azimutal puede apuntar directamente.
    if (az_real >= 90.0f && az_real <= 270.0f){
        // MODO NORMAL
        az_objetivo_base = az_real;
        el_angulo_fisico = el_real;}
    else{
        // MODO BACK-FLIP (El sol está al Norte/Noreste/Noroeste). El servo azimutal no llega, así que giramos la base 180 grados e invertimos la elevación.

        if (az_real < 90.0f){
            // Ejemplo: Sol en 45 (NE) -> Base apunta a 225 (SO)
            az_objetivo_base = az_real + 180.0f;}
        else{
            // Ejemplo: Sol en 350 (NO) -> Base apunta a 170 (SE)
            az_objetivo_base = az_real - 180.0f;}

        // Invertimos elevación: Si sol está a 10°, el servo va a 170°
        el_angulo_fisico = 180.0f - el_real;}


    float pwm_az_calc = 1500.0f + ((180.0f - az_objetivo_base) * FACTOR_CONVERSION);
    float pwm_el_calc = 500.0f + (el_angulo_fisico * FACTOR_CONVERSION);

    // Convertimos a entero y aplicamos los límites de seguridad (50-250)
    int pwm_az_final = constrain_pwm((int)pwm_az_calc);
    int pwm_el_final = constrain_pwm((int)pwm_el_calc);

    // Enviar a los motores
    __HAL_TIM_SET_COMPARE(&htim11, TIM_CHANNEL_1,pwm_az_final);
    __HAL_TIM_SET_COMPARE(&htim10, TIM_CHANNEL_1,pwm_el_final);}


void HAL_TIM_PeriodElapsedCallback(TIM_HandleTypeDef *htim){
	if (htim->Instance == TIM1){
		HAL_GPIO_TogglePin(GPIOH, GPIO_PIN_1);
		HAL_GPIO_TogglePin(LD2_GPIO_Port, LD2_Pin);}
}

void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart) {
  if (huart->Instance == USART1) {

    // Si llega un salto de línea, terminamos la frase
    if (rx_byte == '\n') {
      rx_buffer[rx_index] = '\0'; // Terminador de string

      //Copiamos al buffer de procesamiento. Esto libera rx_buffer para seguir recibiendo
      memcpy(gps_processing_buffer, rx_buffer, rx_index + 1);

      flag_gps = 1; // Avisar al main
      rx_index = 0; // Reiniciar para la próxima
    } else {
      // Guardamos dato si cabe (evitamos desbordamiento)
      if (rx_index < sizeof(rx_buffer) - 1) {
        rx_buffer[rx_index++] = rx_byte;
      } else {
        // Si se llena, reiniciamos por seguridad
        rx_index = 0;
      }
    }

    // Volvemos a activar la escucha SOLAMENTE AL FINAL
    HAL_UART_Receive_IT(&huart1, &rx_byte, 1);
  }
}

void HAL_UARTEx_RxEventCallback(UART_HandleTypeDef *huart, uint16_t Size) {
  if (huart->Instance == USART2) {
    if (currentRxBuffer == 0) { // dataBufferA acaba de terminar de recibir
      receivedDataSizeA = Size; // Guardamos tamaño específico de A
      dataBufferAready = 1; // levantamos la bandera de recepcion del buffer A
      currentRxBuffer = 1;  // cambiamos el selector de buffer
      HAL_UARTEx_ReceiveToIdle_DMA(&huart2, dataBufferB, MAXBUFFER);
    }                           // escuchamos, se guarda en el buffer B
    else {                      // dataBufferB acaba de terminar de recibir
      receivedDataSizeB = Size; // Guardamos tamaño específico de B
      dataBufferBready = 1; // levantamos la bandera de recepcion del buffer B
      currentRxBuffer = 0;  // cambiamos el selector de buffer
      HAL_UARTEx_ReceiveToIdle_DMA(&huart2, dataBufferA, MAXBUFFER);
    } // escuchamos, se guarda en el buffer A
  }
}



void HAL_UART_ErrorCallback(UART_HandleTypeDef *huart) {
  // Si ocurre un error de ruido o desbordamiento en el GPS
  if (huart->Instance == USART1) {
    // Limpiamos el error y reiniciamos la recepción forzosamente
    HAL_UART_Receive_IT(&huart1, &rx_byte, 1);
  }
}

/* USER CODE END 4 */

/**
  * @brief  This function is executed in case of error occurrence.
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}

#ifdef  USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */

#ifndef LIQUIDCRYSTAL_I2C_H
#define LIQUIDCRYSTAL_I2C_H

#include "stm32f4xx_hal.h"

// Define commands
#define LCD_CLEARDISPLAY 0x01//Limpia la memoria DDRAM y pone el cursor al inicio.
#define LCD_RETURNHOME 0x02//No borra la RAM, solo mueve el cursor al origen y deshace el scroll.
#define LCD_ENTRYMODESET 0x04//Define hacia dónde se mueve el cursor
// y si la pantalla se desplaza cuando escribe un carácter.
#define LCD_DISPLAYCONTROL 0x08//Enciende/apaga la pantalla, el cursor y el parpadeo del cursor.
#define LCD_CURSORSHIFT 0x10//Mueve el cursor o toda la pantalla a la derecha o izquierda sin cambiar lo que hay en la memoria RAM.
#define LCD_FUNCTIONSET 0x20//Configura la interfaz de datos (4 u 8 bits), número de líneas (1 o 2) y el tamaño de la fuente.
#define LCD_SETDDRAMADDR 0x80//le dice al cursor dónde posicionarse (Fila/Columna).
// El bit más significativo (MSB) en 1 le dice al LCD que esto es una dirección de memoria.

// Flags for display entry mode
#define LCD_ENTRYLEFT 0x02//I/D = 1: Incrementa la dirección (escribe de izq a derecha)
#define LCD_ENTRYSHIFTDECREMENT 0x00//// S = 0: La pantalla no se desplaza, solo se mueve el cursor

// Flags for display on/off control
#define LCD_DISPLAYON 0x04// Bit D = 1: Pantalla encendida (Los cristales líquidos se activan)
#define LCD_CURSOROFF 0x00// Bit C = 0: Cursor invisible
#define LCD_BLINKOFF 0x00// Bit B = 0: Sin parpadeo



// Flags for function set
#define LCD_4BITMODE 0x00// DL = 0: Interfaz de 4 bits
#define LCD_2LINE 0x08// N = 1: Modo de 2 líneas
#define LCD_1LINE 0x00// N = 0: Modo de 1 línea
#define LCD_5x10DOTS 0x04// F = 1: Fuente de 5x10 puntos
#define LCD_5x8DOTS 0x00// F = 0: Fuente de 5x8 puntos

// Flags for backlight control
#define LCD_BACKLIGHT 0x08//controlan la luz de fondo de la pantalla
#define LCD_NOBACKLIGHT 0x00

// PCF8574 pin mapping (verify with your module!)
// Typical: P0=RS, P1=RW, P2=E, P3=BL, P4-P7=D4-D7
#define En 0x04  // Enable bit (P2). La LCD lee los datos en el flanco de bajada de este pulso
#define Rw 0x02  // Read/Write bit (P1). 0 = escribir el la LCD. 1 = leer la LCD
#define Rs 0x01  // Register select bit (P0). Define el destino del byte.
//1 = registro de comandos, 0 = registro de datos

// estructura de control. guarda el estado de la pantalla
typedef struct {
    I2C_HandleTypeDef *hi2c;//contiene la direccion base de los registros del I2C(micro)
    uint8_t _Addr;//direccion del esclavo ya desplazada
    uint8_t _displayfunction;//guarda conficuracion (4bits,2lineas, 5x8dots)
    uint8_t _displaycontrol;//guarda estado (encendido, cursor, Blink)
    uint8_t _displaymode;//guarda modo de entrada (izq -> derecha, shift)
    uint8_t _numlines;//numero de filas de la pantalla)
    uint8_t _cols;//numero de columnas
    uint8_t _rows;//similar a numlines
    uint8_t _backlightval;//estado actual de la luz de fondo
} LiquidCrystal_I2C_t;

// Function prototypes
void LiquidCrystal_I2C_init(LiquidCrystal_I2C_t *lcd, I2C_HandleTypeDef *hi2c,
                           uint8_t lcd_Addr, uint8_t lcd_cols, uint8_t lcd_rows);
void LiquidCrystal_I2C_begin(LiquidCrystal_I2C_t *lcd, uint8_t cols, uint8_t rows, uint8_t charsize);
void LiquidCrystal_I2C_clear(LiquidCrystal_I2C_t *lcd);
void LiquidCrystal_I2C_home(LiquidCrystal_I2C_t *lcd);
void LiquidCrystal_I2C_display(LiquidCrystal_I2C_t *lcd);
void LiquidCrystal_I2C_setCursor(LiquidCrystal_I2C_t *lcd, uint8_t col, uint8_t row);
void LiquidCrystal_I2C_write(LiquidCrystal_I2C_t *lcd, uint8_t value);
void LiquidCrystal_I2C_command(LiquidCrystal_I2C_t *lcd, uint8_t value);
void LiquidCrystal_I2C_print(LiquidCrystal_I2C_t *lcd, const char *str);

#endif

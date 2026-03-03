/* Los archivos .c y .h asociados a la pantalla se encargan de hacer el manejo de la
 * pantalla. Define en macros las mascaras que  odifican puntualmente los registros
 * correspondientes. Y las funciones se encargan de hacer estas modificaciones de la
 * mano de lo pedido. La forma de modificar los datos de pantalla es: 1 D C B.
 * El 1 del MSB significa que es una accion de control sobre la pantalla, D si es
 * 1 enciende la pantalla, si es 0 la apaga. El bit C si es 1 enciende el cursor
 * si es 0, lo apaga. El bit B si es 0 enciende el parpadeo si es 1 lo desactiva.
 * Importante, para todos estas configuraciones: el bit Rs = 0.
 * 0000 1100  (Resultado final: 0x0C)
 *
 * Luego tenemos las modificaciones sobre lo que muestra la pantalla, para los cuales
 * Rs debe ser 1. Y con base en la tabla ASCII se envia byte partido en 2, de a 4
 * bits cada caracter. Se shiftea para las 4 ultimas posiciones (MSB) y en los 4
 * (LSB) se incluye la configuracion. por ejemplo para enviar una "A" 0100 0001:
 * se envia primero 0100 1101 y despues 0001 1101. Con esto la pantalla internamente
 * controla que pixels encender o a apagar para formar dicha letra
 */

#include "LiquidCrystal_I2C.h"
#include <string.h>

// Private function prototypes
static void send(LiquidCrystal_I2C_t *lcd, uint8_t value, uint8_t mode);
static void write4bits(LiquidCrystal_I2C_t *lcd, uint8_t value);
static void expanderWrite(LiquidCrystal_I2C_t *lcd, uint8_t _data);
static void pulseEnable(LiquidCrystal_I2C_t *lcd, uint8_t _data);

// Microsecond delay function
static inline void delayMicroseconds(uint32_t us) {
    uint32_t cycles = (SystemCoreClock / 1000000) * us / 3;
    while (cycles--) {
        __NOP();
    }
}
//inicializa la estructura con el puerto I2C y la direecion fisica
void LiquidCrystal_I2C_init(LiquidCrystal_I2C_t *lcd, I2C_HandleTypeDef *hi2c,
                           uint8_t lcd_Addr, uint8_t lcd_cols, uint8_t lcd_rows) {
    lcd->hi2c = hi2c;
    lcd->_Addr = lcd_Addr << 1;  // Shift for HAL I2C format
    lcd->_cols = lcd_cols;
    lcd->_rows = lcd_rows;
    lcd->_backlightval = LCD_BACKLIGHT;  // Start with backlight ON
    lcd->_displayfunction = LCD_4BITMODE | LCD_1LINE | LCD_5x8DOTS;
    LiquidCrystal_I2C_begin(lcd, lcd->_cols, lcd->_rows, LCD_5x8DOTS);
}


//secuencia de arranque de hardware. Configura el modo 4 bits y limpia la pantalla
void LiquidCrystal_I2C_begin(LiquidCrystal_I2C_t *lcd, uint8_t cols, uint8_t lines, uint8_t dotsize) {
    if (lines > 1) {
        lcd->_displayfunction |= LCD_2LINE;
    }
    lcd->_numlines = lines;

    if ((dotsize != 0) && (lines == 1)) {
        lcd->_displayfunction |= LCD_5x10DOTS;
    }

    // Wait for LCD to power up (>40ms after VCC rises to 4.5V)
    HAL_Delay(50);

    // Put the LCD into 4 bit mode according to HD44780 datasheet
    // Start in 8bit mode, try to set 4 bit mode
    expanderWrite(lcd, lcd->_backlightval);
    HAL_Delay(1);

    // Sequence to put into 4-bit mode
    write4bits(lcd, 0x03 << 4);
    delayMicroseconds(4500);

    write4bits(lcd, 0x03 << 4);
    delayMicroseconds(4500);

    write4bits(lcd, 0x03 << 4);
    delayMicroseconds(150);

    // Finally, set to 4-bit interface
    write4bits(lcd, 0x02 << 4);
    delayMicroseconds(100);

    // Set # lines, font size, etc.
    LiquidCrystal_I2C_command(lcd, LCD_FUNCTIONSET | lcd->_displayfunction);

    // Turn the display on with no cursor or blinking default
    lcd->_displaycontrol = LCD_DISPLAYON | LCD_CURSOROFF | LCD_BLINKOFF;
    LiquidCrystal_I2C_display(lcd);

    // Clear display
    LiquidCrystal_I2C_clear(lcd);

    // Set entry mode
    lcd->_displaymode = LCD_ENTRYLEFT | LCD_ENTRYSHIFTDECREMENT;
    LiquidCrystal_I2C_command(lcd, LCD_ENTRYMODESET | lcd->_displaymode);

    LiquidCrystal_I2C_home(lcd);
}

//envia el comando 0x01 para limpiar todo
void LiquidCrystal_I2C_clear(LiquidCrystal_I2C_t *lcd) {
    LiquidCrystal_I2C_command(lcd, LCD_CLEARDISPLAY);
    HAL_Delay(2);  // Clear takes 1.52ms
}

//envia el comando0x02 para volver al inicio
void LiquidCrystal_I2C_home(LiquidCrystal_I2C_t *lcd) {
    LiquidCrystal_I2C_command(lcd, LCD_RETURNHOME);
    HAL_Delay(2);  // Home takes 1.52ms
}

//calcula la direccion de memoria DDRAM basandose en col/fila y envia el comando
void LiquidCrystal_I2C_setCursor(LiquidCrystal_I2C_t *lcd, uint8_t col, uint8_t row) {
    // Row addresses for 20x4 LCD
    int row_offsets[] = {0x00, 0x40, 0x14, 0x54};

    if (row >= lcd->_numlines) {
        row = lcd->_numlines - 1;
    }
    LiquidCrystal_I2C_command(lcd, LCD_SETDDRAMADDR | (col + row_offsets[row]));
}

//enciende la pantalla (pone el bit D en 1)
void LiquidCrystal_I2C_display(LiquidCrystal_I2C_t *lcd) {
    lcd->_displaycontrol |= LCD_DISPLAYON;
    LiquidCrystal_I2C_command(lcd, LCD_DISPLAYCONTROL | lcd->_displaycontrol);
}

//envia un byte de comando (Rs=0). Ejecuta una instruccion
void LiquidCrystal_I2C_command(LiquidCrystal_I2C_t *lcd, uint8_t value) {
    send(lcd, value, 0);
}

//envia un byte de datos (Rs=1). imprime una letra
void LiquidCrystal_I2C_write(LiquidCrystal_I2C_t *lcd, uint8_t value) {
    send(lcd, value, Rs);
}

//recorre una cadena de texto y llama a write por cada letra
void LiquidCrystal_I2C_print(LiquidCrystal_I2C_t *lcd, const char *str) {
    while (*str) {
        LiquidCrystal_I2C_write(lcd, *str++);
    }
}

// ==================== Private Functions ====================

static void send(LiquidCrystal_I2C_t *lcd, uint8_t value, uint8_t mode) {
    uint8_t highnib = value & 0xF0;
    uint8_t lownib = (value << 4) & 0xF0;
    write4bits(lcd, highnib | mode);
    write4bits(lcd, lownib | mode);
}

static void write4bits(LiquidCrystal_I2C_t *lcd, uint8_t value) {
    expanderWrite(lcd, value);
    pulseEnable(lcd, value);
}

static void expanderWrite(LiquidCrystal_I2C_t *lcd, uint8_t _data) {
    uint8_t data = _data | lcd->_backlightval;
    HAL_I2C_Master_Transmit(lcd->hi2c, lcd->_Addr, &data, 1, 100);
}

static void pulseEnable(LiquidCrystal_I2C_t *lcd, uint8_t _data) {
    expanderWrite(lcd, _data | En);
    delayMicroseconds(1);  // Enable pulse must be >450ns

    expanderWrite(lcd, _data & ~En);
    delayMicroseconds(50);  // Command needs >37us to settle
}

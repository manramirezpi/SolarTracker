package com.curso_simulaciones.seguidorapp.datos;

import java.util.ArrayList;

public class AlmacenDatosRAM {

    public static int ancho, alto, dimensionReferencia, tamanoLetraResolucionIncluida;

    // Estado del broker
    public static int estado_conexion_nube = 1;

    public static String MQTTHOST = "tcp://45.56.74.248:1883";
    public static String USERNAME = "fisica";
    public static String PASSWORD = "iotfisica";
    public static String topicSubFast = "solar/status/fast";
    public static String topicSubSlow = "solar/status/slow";
    public static String topicSubBatch = "solar/data/batch";
    public static String topicDebug = "solar/debug/data";
    public static String topicPub = "solar/cmd";

    public static volatile String conectado_PubSub = "Hacer clic en CONECTAR...";
    public static volatile boolean conectado = false;

    // Datos del Sol
    public static volatile float sol_az = 0;
    public static volatile float sol_el = 0;

    // Datos de los Servos (Ángulos)
    public static volatile float servo_az = 0;
    public static volatile float servo_el = 0;

    // Datos del GPS
    public static volatile float lat = 0;
    public static volatile float lon = 0;
    public static volatile boolean gps_valido = false;

    // --- ESTADOS DE SALUD (HEALTH SYSTEM v2.1) ---
    // 0: Error/Desconectado, 1: Advertencia/Ocupado, 2: Saludable/Fix
    public static volatile int health_mqtt = 0;
    public static volatile int health_gps = 0;
    public static volatile int health_ina = 0;
    public static volatile int health_disk = 0; // % de ocupación o estado datalogger
    public static volatile String pendingAckId = null; // ID pendiente de confirmar (ACK)

    // Fecha y Hora
    public static volatile String fecha = "--/--/----";
    public static volatile String hora = "--:--:--";

    // Modo (AUTO/MAN/SET)
    public static volatile String modo = "AUTO";

    // Datos Potencia (Canal 1: Panel Móvil, Canal 2: Panel Fijo)
    public static volatile float p1_inst = 0, p1_avg = 0, p1_avg_dia = 0;
    public static volatile float p2_inst = 0, p2_avg = 0, p2_avg_dia = 0;

    // Variables para optimización de promedio (Running Sum)
    public static float sumaP1 = 0;
    public static float sumaP2 = 0;

    public static final int MAX_HISTORICO = 100; // Ventana de ~20s para suavizado reactivo

    // Optimización "Ultimate": Búfer Circular con arrays primitivos
    // Evita el boxing de Float y el desplazamiento de memoria O(n)
    public static float[] historico_p1 = new float[MAX_HISTORICO];
    public static float[] historico_p2 = new float[MAX_HISTORICO];
    public static int indexP1 = 0, countP1 = 0;
    public static int indexP2 = 0, countP2 = 0;

    public static void resetStats() {
        indexP1 = 0;
        countP1 = 0;
        sumaP1 = 0;
        indexP2 = 0;
        countP2 = 0;
        sumaP2 = 0;
        for (int i = 0; i < MAX_HISTORICO; i++) {
            historico_p1[i] = 0;
            historico_p2[i] = 0;
        }
    }
}

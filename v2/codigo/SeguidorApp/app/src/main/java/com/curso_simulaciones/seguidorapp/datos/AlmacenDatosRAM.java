package com.curso_simulaciones.seguidorapp.datos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AlmacenDatosRAM {

    public static int ancho, alto, dimensionReferencia, tamanoLetraResolucionIncluida;

    // Estado del broker
    public static int estado_conexion_nube = 1;

    public static String MQTTHOST = "tcp://45.56.74.248:1883";
    public static String USERNAME = "fisica";
    public static String PASSWORD = "iotfisica";
    public static String topicSubFast = "solar/status/fast";
    public static String topicSubSlow = "solar/status/slow";
    public static String topicSubRecord = "solar/data/record";
    public static String topicSubDone = "solar/data/done";
    public static String topicAppStatus = "solar/app/status";
    public static String topicEspStatus = "solar/esp32/status";
    public static String topicPub = "solar/cmd";
    public static String topicPubAck = "solar/data/ack";

    public static volatile String conectado_PubSub = "Hacer clic en CONECTAR...";
    public static volatile boolean conectado = false;

    // Lista en memoria para acumular registros (según req)
    public static ArrayList<String> registrosDatalogger = new ArrayList<>();

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

    // --- ESTADOS DE SALUD (HEALTH SYSTEM v2.5) ---
    public static class HealthStatus {
        public int ina, gps, wifi, mqtt, spiffs, servos, global;
        public long timestampMs;

        public HealthStatus() {
            ina = gps = wifi = mqtt = spiffs = servos = global = -1; // -1: Desconocido
            timestampMs = 0;
        }
    }

    public static volatile HealthStatus currentHealth = new HealthStatus();

    // 0: OK, 1: WARN, 2: FAIL
    public static volatile int health_mqtt = -1;
    public static volatile int health_gps = -1;
    public static volatile int health_ina = -1;
    public static volatile int health_wifi = -1;
    public static volatile int health_servos = -1;
    public static volatile int health_disk = -1; 
    public static volatile int health_global = -1; 

    public static volatile long ts_mqtt = 0, ts_gps = 0, ts_ina = 0, ts_wifi = 0, ts_servos = 0, ts_disk = 0;

    // --- DATALOGGER CLASES Y DATOS ---
    public static class DataRecord {
        public String horaUtc;
        public float p1AvgMw;
        public float p2AvgMw;

        public DataRecord(String h, float p1, float p2) {
            this.horaUtc = h;
            this.p1AvgMw = p1;
            this.p2AvgMw = p2;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DataRecord that = (DataRecord) o;
            return horaUtc.equals(that.horaUtc);
        }

        @Override
        public int hashCode() {
            return horaUtc.hashCode();
        }
    }

    public static List<DataRecord> recordsList = Collections.synchronizedList(new ArrayList<>());
    public static volatile String lastSlowPayloadHeader = ""; // Para metadatos en CSV
    
    public static volatile String pendingAckId = null; 

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

package com.curso_simulaciones.seguidorapp.datos;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.content.Context;

/**
 * Especialista en decodificar secuencias String del protocolo MQTT 
 * y alimentar/actualizar la RAM estática del sistema.
 * 
 * Implementa un mecanismo (Bypass GC) para los datos de alta frecuencia que 
 * recicla memoria nativa evitando la creación excesiva de objetos JSON.
 */
public class ProcesadorTelemetria {

    private boolean primeraVez = true;
    private int contadorMuestreoPotencia = 0;

    public void resetSync() {
        primeraVez = true;
    }

    public boolean procesarDato(String data, Context context) {
        try {
            // DETECCIÓN DE LOTE DE DATOS (Calibración)
            if (data.contains("\"batch\":")) {
                return guardarBatchTxt(data, context); 
            }

            // ESTRATEGIA DE OPTIMIZACIÓN (Garbage Collector Bypass)
            if (data.contains("\"sol\":")) {
                int idxSol = data.indexOf("\"sol\":");
                int idxServos = data.indexOf("\"servos\":");
                int idxP = data.indexOf("\"p\":");

                AlmacenDatosRAM.sol_az = extraerFloat(data, "\"az\":", idxSol);
                AlmacenDatosRAM.sol_el = extraerFloat(data, "\"el\":", idxSol);
                
                AlmacenDatosRAM.servo_az = extraerFloat(data, "\"az\":", idxServos);
                AlmacenDatosRAM.servo_el = extraerFloat(data, "\"el\":", idxServos);
                
                AlmacenDatosRAM.p1_inst = extraerFloat(data, "\"c1\":", idxP);
                AlmacenDatosRAM.p1_avg_dia = extraerFloat(data, "\"a1\":", idxP);
                AlmacenDatosRAM.p2_inst = extraerFloat(data, "\"c2\":", idxP);
                AlmacenDatosRAM.p2_avg_dia = extraerFloat(data, "\"a2\":", idxP);
                
                // Actualización de media móvil local para suavizado de gauges
                contadorMuestreoPotencia++;
                if (contadorMuestreoPotencia % 5 == 0) {
                    actualizarBufferCircular(AlmacenDatosRAM.p1_inst, true);
                    AlmacenDatosRAM.p1_avg = obtenerMediaCircular(true);
                    actualizarBufferCircular(AlmacenDatosRAM.p2_inst, false);
                    AlmacenDatosRAM.p2_avg = obtenerMediaCircular(false);
                }
                return false; // Fast tramas no traen datos de GPS inicial
            }
            
            // --- PROCESAMIENTO CANAL LENTO (1Hz) ---
            JSONObject obj = new JSONObject(data);

            if (obj.has("gps")) {
                JSONObject gps = obj.getJSONObject("gps");
                AlmacenDatosRAM.lat = (float) gps.optDouble("lat", AlmacenDatosRAM.lat);
                AlmacenDatosRAM.lon = (float) gps.optDouble("lon", AlmacenDatosRAM.lon);
                Object validoObj = gps.opt("val");
                if (validoObj instanceof Boolean) {
                    AlmacenDatosRAM.gps_valido = (Boolean) validoObj;
                } else {
                    AlmacenDatosRAM.gps_valido = String.valueOf(validoObj).equalsIgnoreCase("true");
                }
            }
            
            AlmacenDatosRAM.fecha = obj.optString("fecha", AlmacenDatosRAM.fecha);
            AlmacenDatosRAM.hora = obj.optString("hora", AlmacenDatosRAM.hora);
            
            if (obj.has("modo")) {
                String modoPrincipal = obj.getString("modo");
                String parking = obj.optString("parking", "false");
                if (parking.equalsIgnoreCase("true")) {
                    AlmacenDatosRAM.modo = modoPrincipal + " (parking)";
                } else {
                    AlmacenDatosRAM.modo = modoPrincipal;
                }
            }
            
            AlmacenDatosRAM.factor_vel = (float) obj.optDouble("v_sim", AlmacenDatosRAM.factor_vel);

            // Si es el primer dato que llega, avisamos para sincronizar sliders
            if (primeraVez && AlmacenDatosRAM.lat != 0) {
                primeraVez = false;
                return true; 
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private float extraerFloat(String json, String key, int searchFromIndex) {
        int startIdx = json.indexOf(key, searchFromIndex);
        if (startIdx == -1) return 0f;
        startIdx += key.length();
        int endIdxComma = json.indexOf(',', startIdx);
        int endIdxBrace = json.indexOf('}', startIdx);
        
        int endIdx;
        if (endIdxComma == -1) endIdx = endIdxBrace;
        else if (endIdxBrace == -1) endIdx = endIdxComma;
        else endIdx = Math.min(endIdxComma, endIdxBrace);
        
        if (endIdx == -1) return 0f;
        try {
            return Float.parseFloat(json.substring(startIdx, endIdx));
        } catch (Exception e) {
            return 0f;
        }
    }

    private void actualizarBufferCircular(float nuevoValor, boolean esCanal1) {
        float[] buffer = esCanal1 ? AlmacenDatosRAM.historico_p1 : AlmacenDatosRAM.historico_p2;
        int index = esCanal1 ? AlmacenDatosRAM.indexP1 : AlmacenDatosRAM.indexP2;
        int count = esCanal1 ? AlmacenDatosRAM.countP1 : AlmacenDatosRAM.countP2;

        if (count == AlmacenDatosRAM.MAX_HISTORICO) {
            float viejoValor = buffer[index];
            if (esCanal1) AlmacenDatosRAM.sumaP1 -= viejoValor;
            else AlmacenDatosRAM.sumaP2 -= viejoValor;
        }

        buffer[index] = nuevoValor;
        if (esCanal1) AlmacenDatosRAM.sumaP1 += nuevoValor;
        else AlmacenDatosRAM.sumaP2 += nuevoValor;

        index = (index + 1) % AlmacenDatosRAM.MAX_HISTORICO;
        if (count < AlmacenDatosRAM.MAX_HISTORICO) count++;

        if (esCanal1) {
            AlmacenDatosRAM.indexP1 = index;
            AlmacenDatosRAM.countP1 = count;
        } else {
            AlmacenDatosRAM.indexP2 = index;
            AlmacenDatosRAM.countP2 = count;
        }
    }

    private float obtenerMediaCircular(boolean esCanal1) {
        int count = esCanal1 ? AlmacenDatosRAM.countP1 : AlmacenDatosRAM.countP2;
        if (count == 0) return 0;
        float suma = esCanal1 ? AlmacenDatosRAM.sumaP1 : AlmacenDatosRAM.sumaP2;
        return suma / count;
    }

    private boolean guardarBatchTxt(String jsonStr, Context context) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String content = obj.optString("data", "");
            
            // 1. Poblamos la lista en memoria para que el botón "Compartir" sea útil inmediatamente
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (!line.trim().isEmpty() && !line.contains("P1")) { // Evitar cabeceras repetidas
                    AlmacenDatosRAM.registrosDatalogger.add(line);
                }
            }

            // 2. Guardamos en la carpeta de archivos del sistema (.txt persistente)
            File folder = context.getExternalFilesDir(null);
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File file = new File(folder, "batch_potencia_" + timeStamp + ".txt");
            
            FileOutputStream out = new FileOutputStream(file);
            out.write(content.getBytes());
            out.close();
            
            AlmacenDatosRAM.conectado_PubSub = "Lote guardado: " + file.getName();
            android.util.Log.i("TELEMETRIA", "Lote guardado en: " + file.getAbsolutePath());
            return true;
            
        } catch (Exception e) {
            e.printStackTrace();
            AlmacenDatosRAM.conectado_PubSub = "Error al guardar lote .txt";
            return false;
        }
    }
}

package com.curso_simulaciones.seguidorapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.ViewGroup;
import android.widget.SeekBar;

import com.curso_simulaciones.seguidorapp.comunicaciones.ClientePubSubMQTT;
import com.curso_simulaciones.seguidorapp.datos.AlmacenDatosRAM;
import com.curso_simulaciones.seguidorapp.datos.ProcesadorTelemetria;
import com.curso_simulaciones.seguidorapp.utilidades.DialogoSalir;
import com.curso_simulaciones.seguidorapp.utilidades.GeneradorUI;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PorterDuff;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.FileOutputStream;
import java.io.File;




import org.json.JSONException;
import org.json.JSONObject;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import android.app.AlertDialog;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controlador principal de la aplicación SeguidorApp (Capa "Controlador" en el
 * patrón MVC/MVP).
 * 
 * Tras el refactoring arquitectónico de v2.0, esta clase ya no maneja la
 * instanciación
 * visual ni el procesamiento pesado de tramas de telemetría.
 * 
 * Sus tres responsabilidades únicas (Single-Responsibility) son:
 * 1. Gestionar el ciclo de vida de la Actividad en Android (onCreate,
 * onDestroy, onRestart).
 * 2. Manejar los eventos del usuario (OnTouch, OnClick) generados por la capa
 * Vista (GeneradorUI).
 * 3. Ejecutar y administrar el Hilo en segundo plano (run) que extrae los datos
 * de la red
 * (ClientePubSubMQTT), los envía a procesamiento (ProcesadorTelemetria) y
 * notifica a la Vista.
 */
public class ActividadSeguidor extends Activity implements Runnable {

    private GeneradorUI ui;

    private ClientePubSubMQTT cliente;
    private ProcesadorTelemetria procesadorTelemetria = new ProcesadorTelemetria();
    private Thread hilo;
    private final Handler myHandler = new Handler();

    private boolean intervencionGPS = false; // Prioridad manual sobre coordenadas
    private boolean intervencionServo = false; // Prioridad manual sobre ángulos servo
    private long lastManualInteractionTime = 0;
    private long ultimoTiempoPublishGPS = 0; // Throttling MQTT GPS
    private long ultimoTiempoPublishServo = 0; // Throttling MQTT Servos
    private static final long MANUAL_LOCKOUT_MS = 3000;
    private volatile boolean threadRunning = true; // Control del hilo

    // Gestión de Feedback de Comandos
    private HashMap<String, Long> lastCmdTime = new HashMap<>();
    private HashMap<String, Float> lastTargetVal = new HashMap<>();
    private static final long CMD_CONFIRM_TIMEOUT = 3000;
    private static final long CMD_SENDING_UI_MS = 800;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ui = new GeneradorUI(this);
        ui.gestionarResolucion();

        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        this.setContentView(ui.construir(), params);

        eventos();
        cliente = new ClientePubSubMQTT(this);
        hilo = new Thread(this);
        hilo.start();
    }

    private void eventos() {
        ui.botonConectar.setOnClickListener(v -> {
            if (!AlmacenDatosRAM.conectado) {
                AlmacenDatosRAM.resetStats(); 
                cliente.conectar();
                ui.botonConectar.setEnabled(false);
                ui.botonConectar.setText("Conectando...");
            } else {
                cliente.desconectar();
                AlmacenDatosRAM.conectado = false;
                AlmacenDatosRAM.conectado_PubSub = "DESCONECTADO";
                procesadorTelemetria.resetSync();
                actualizarUI();
            }
        });

        ui.healthIndicator.setOnClickListener(v -> mostrarDiagnostico());
        ui.healthStatusText.setOnClickListener(v -> mostrarDiagnostico());

        ui.btnAuto.setOnClickListener(v -> {
            publicarComando("reset", 0, false);
            actualizarEstadoModo(true);
        });

        ui.btnMan.setOnClickListener(v -> {
            publicarComando("set_man", 0, false);
            actualizarEstadoModo(false);
        });

        ui.botonResetGPS.setOnClickListener(v -> {
            sincronizarControles();
            publicarComando("reset", 0, false);
            Snackbar.make(findViewById(android.R.id.content), "GPS sincronizado ✓", Snackbar.LENGTH_SHORT).show();
        });

        ui.sliderLat.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float lat = (progress - 9000) / 100f;
                ui.labelLat.setText(String.format("Lat: %.2f", lat));
                if (fromUser) {
                    intervencionGPS = true; // Usuario toma prioridad en GPS
                    intervencionServo = false; // Al mover coords, liberamos los servos para que sigan al nuevo punto
                    lastManualInteractionTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - ultimoTiempoPublishGPS > 150) {
                        publicarComando("set_lat", lat, true);
                        ultimoTiempoPublishGPS = System.currentTimeMillis();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float lat = (seekBar.getProgress() - 9000) / 100f;
                publicarComando("set_lat", lat, true);
                registrarFeedback("lat", lat, ui.feedLat);
            }
        });

        ui.sliderLon.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float lon = (progress - 18000) / 100f;
                ui.labelLon.setText(String.format("Lon: %.2f", lon));
                if (fromUser) {
                    intervencionGPS = true;
                    intervencionServo = false;
                    lastManualInteractionTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - ultimoTiempoPublishGPS > 150) {
                        publicarComando("set_lon", lon, true);
                        ultimoTiempoPublishGPS = System.currentTimeMillis();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float lon = (seekBar.getProgress() - 18000) / 100f;
                publicarComando("set_lon", lon, true);
                registrarFeedback("lon", lon, ui.feedLon);
            }
        });

        // ui.sliderTiempo ya no existe en v2.1


        // Eventos Manuales
        ui.sliderManualAz.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int valorReal = progress - 90; // Mapeo visual a valor astronómico
                ui.labelManualAz.setText("Manual Az: " + valorReal + "°");
                if (fromUser) {
                    intervencionServo = true;
                    lastManualInteractionTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - ultimoTiempoPublishServo > 150) {
                        publicarComando("set_ser_az", valorReal, true);
                        ultimoTiempoPublishServo = System.currentTimeMillis();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int valorReal = seekBar.getProgress() - 90;
                publicarComando("set_ser_az", valorReal, true);
                registrarFeedback("ser_az", (float)valorReal, ui.feedAz);
            }
        });

        ui.sliderManualEl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ui.labelManualEl.setText("Manual El: " + progress + "°");
                if (fromUser) {
                    intervencionServo = true; // El usuario toma prioridad absoluta
                    lastManualInteractionTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - ultimoTiempoPublishServo > 150) {
                        publicarComando("set_ser_el", progress, true);
                        ultimoTiempoPublishServo = System.currentTimeMillis();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                publicarComando("set_ser_el", seekBar.getProgress(), true);
                registrarFeedback("ser_el", (float)seekBar.getProgress(), ui.feedEl);
            }
        });

        ui.botonTemp.setOnClickListener(v -> {
            if (AlmacenDatosRAM.conectado) {
                AlmacenDatosRAM.registrosDatalogger.clear();
                ui.botonShare.setEnabled(false);
                ui.progressDownload.setVisibility(View.VISIBLE);
                ui.progressDownload.setProgress(0);
                ui.labelDownloadCount.setVisibility(View.VISIBLE);
                ui.labelDownloadCount.setText("Iniciando descarga...");
                publicarComando("get_temp", 0, false);
            }
        });

        ui.botonShare.setOnClickListener(v -> generarYCompartirCSV());
    }

    private void generarYCompartirCSV() {
        if (AlmacenDatosRAM.registrosDatalogger.isEmpty()) {
            AlmacenDatosRAM.conectado_PubSub = "No hay registros acumulados";
            return;
        }

        try {
            // Nombre del archivo basado en la fecha del sistema
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File cacheDir = getExternalCacheDir();
            File file = new File(cacheDir, "SolarTracker_Export_" + timestamp + ".csv");
            
            FileOutputStream out = new FileOutputStream(file);
            
            // Cabecera con lat, lon y fecha (tomados de la RAM actual)
            String header = "# lat=" + AlmacenDatosRAM.lat + ",lon=" + AlmacenDatosRAM.lon + 
                           ",fecha=" + AlmacenDatosRAM.fecha + "\n";
            header += "hora_utc,p1_mw,p2_mw\n";
            out.write(header.getBytes());

            for (String registro : AlmacenDatosRAM.registrosDatalogger) {
                out.write((registro + "\n").getBytes());
            }
            out.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Backup SolarTracker " + AlmacenDatosRAM.fecha);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Compartir CSV..."));

        } catch (Exception e) {
            Log.e("ActividadSeguidor", "Error al generar CSV", e);
            AlmacenDatosRAM.conectado_PubSub = "Error exportación: " + e.getMessage();
        }
    }

    private void finalizarDescarga() {
        if (AlmacenDatosRAM.registrosDatalogger.isEmpty()) return;

        ui.botonShare.setEnabled(true);
        ui.progressDownload.setVisibility(View.GONE);
        ui.labelDownloadCount.setVisibility(View.GONE);
        
        // Calcular resumen
        int total = AlmacenDatosRAM.registrosDatalogger.size();
        String hInicio = AlmacenDatosRAM.registrosDatalogger.get(0).split(",")[0];
        String hFin = AlmacenDatosRAM.registrosDatalogger.get(total - 1).split(",")[0];
        
        float maxP1 = 0, maxP2 = 0;
        for (String r : AlmacenDatosRAM.registrosDatalogger) {
            String[] p = r.split(",");
            maxP1 = Math.max(maxP1, Float.parseFloat(p[1]));
            maxP2 = Math.max(maxP2, Float.parseFloat(p[2]));
        }

        String resumen = String.format(Locale.getDefault(), 
            "Descarga completada: %d registros [%s - %s]. P1 max: %.0fmW, P2 max: %.0fmW",
            total, hInicio, hFin, maxP1, maxP2);

        Snackbar.make(findViewById(android.R.id.content), resumen, Snackbar.LENGTH_LONG).show();
        AlmacenDatosRAM.conectado_PubSub = "Monitoreo activado";
    }
    private void registrarFeedback(String key, float target, TextView label) {
        lastCmdTime.put(key, System.currentTimeMillis());
        lastTargetVal.put(key, target);
        label.setText("Enviando...");
        label.setTextColor(Color.LTGRAY);
        label.setVisibility(View.VISIBLE);
    }

    private void actualizarFeedback(String key, float currentVal, TextView label) {
        if (!lastCmdTime.containsKey(key)) return;
        
        long elapsed = System.currentTimeMillis() - lastCmdTime.get(key);
        float target = lastTargetVal.get(key);
        
        // Si el valor actual coincide con el objetivo (tolerancia 0.15)
        if (Math.abs(currentVal - target) < 0.15f) {
            label.setText("✓");
            label.setTextColor(ui.COLOR_HEALTH_OK);
            // Desaparecer después de 2 segundos
            if (elapsed > 2000) label.setVisibility(View.GONE);
        } else {
            if (elapsed > CMD_CONFIRM_TIMEOUT) {
                label.setText("Sin respuesta");
                label.setTextColor(ui.COLOR_HEALTH_CRIT);
            } else if (elapsed > CMD_SENDING_UI_MS) {
                label.setText("Enviando...");
                label.setTextColor(Color.GRAY);
            }
        }
    }

    private void mostrarDiagnostico() {
        BottomSheetDialog dial = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 60, 60, 60);
        
        TextView title = new TextView(this);
        title.setText("DIAGNÓSTICO DE SUBSISTEMAS");
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 30);
        root.addView(title);

        String[] nombres = {"BROKER MQTT", "SISTEMA GPS", "SENSOR INA", "RED WIFI", "SERVOMOTORES", "SPIFFS / DISK"};
        int[] healths = {AlmacenDatosRAM.health_mqtt, AlmacenDatosRAM.health_gps, AlmacenDatosRAM.health_ina, 
                        AlmacenDatosRAM.health_wifi, AlmacenDatosRAM.health_servos, AlmacenDatosRAM.health_disk};
        long[] tss = {AlmacenDatosRAM.ts_mqtt, AlmacenDatosRAM.ts_gps, AlmacenDatosRAM.ts_ina, 
                     AlmacenDatosRAM.ts_wifi, AlmacenDatosRAM.ts_servos, AlmacenDatosRAM.ts_disk};

        for (int i = 0; i < nombres.length; i++) {
            root.addView(crearFilaEstado(nombres[i], healths[i], tss[i]));
        }

        dial.setContentView(root);
        dial.show();
    }

    private View crearFilaEstado(String nombre, int estado, long ts) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 15, 0, 15);

        long diff = (System.currentTimeMillis() - ts) / 1000;
        boolean obsoleta = (ts == 0 || diff > 5);
        
        View circle = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        
        int color;
        String statusText;
        
        if (obsoleta) {
            color = Color.GRAY;
            statusText = "Desconocido";
        } else {
            if (estado == 0) {
                color = ui.COLOR_HEALTH_OK;
                statusText = "Operando";
            } else if (estado == 1) {
                color = ui.COLOR_HEALTH_WARN;
                statusText = "Degradado";
            } else {
                color = ui.COLOR_HEALTH_CRIT;
                statusText = "Fallo";
            }
        }
        
        gd.setColor(color);
        circle.setBackground(gd);
        row.addView(circle, new LinearLayout.LayoutParams(dpToPx(14), dpToPx(14)));

        TextView tv = new TextView(this);
        String sAge = obsoleta ? "Sin datos" : "Hace " + diff + "s";
        tv.setText(String.format("  %-12s | %-10s | %s", nombre, statusText, sAge));
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(tv);

        return row;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void sincronizarControles() {
        AlmacenDatosRAM.resetStats();
        intervencionGPS = false;
        intervencionServo = false;
        lastManualInteractionTime = 0; // Permitir el seguimiento inmediato tras el reset

        // Sincronizar GPS
        int progLat = (int) (AlmacenDatosRAM.lat * 100 + 9000);
        int progLon = (int) (AlmacenDatosRAM.lon * 100 + 18000);
        ui.sliderLat.setProgress(progLat);
        ui.sliderLon.setProgress(progLon);

        // Sincronizar Manuales con posición real
        ui.sliderManualAz.setProgress((int) AlmacenDatosRAM.servo_az + 90);
        ui.sliderManualEl.setProgress((int) AlmacenDatosRAM.servo_el);

        // Resetear velocidad (Simulación eliminada)
        actualizarEstadoModo(AlmacenDatosRAM.modo.equals("AUTO"));
    }

    private void actualizarEstadoModo(boolean isAuto) {
        ui.btnAuto.setTextColor(isAuto ? Color.WHITE : ui.COLOR_TEXTO_SEC);
        ui.btnAuto.getBackground().setColorFilter(isAuto ? ui.COLOR_CONTROL_ACCENT : Color.LTGRAY, PorterDuff.Mode.MULTIPLY);
        ui.btnMan.setTextColor(!isAuto ? Color.WHITE : ui.COLOR_TEXTO_SEC);
        ui.btnMan.getBackground().setColorFilter(!isAuto ? ui.COLOR_CONTROL_ACCENT : Color.LTGRAY, PorterDuff.Mode.MULTIPLY);

        // Bloquear/Desbloquear sliders manuales según el modo
        ui.sliderManualAz.setEnabled(!isAuto);
        ui.sliderManualEl.setEnabled(!isAuto);
        ui.labelManualAz.setAlpha(isAuto ? 0.3f : 1.0f);
        ui.labelManualEl.setAlpha(isAuto ? 0.3f : 1.0f);
    }

    private void publicarComando(String cmd, float valor, boolean conValor) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("cmd", cmd);
            if (conValor) {
                if (cmd.equals("set_vel")) {
                    // Ignorado en v2.1
                } else {
                    obj.put("valor", valor);
                }
            }
            cliente.publicar(obj.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Hilo en segundo plano de alta reactividad.
     * Lee continuamente del buffer MQTT, decodifica a través de
     * ProcesadorTelemetria
     * y ordena refrescar la Vista solo cuando hay datos nuevos.
     */
    @Override
    public void run() {
        while (threadRunning) {
            try {
                // Aumentamos frecuencia a 20Hz (50ms) para procesar rápido la cola
                Thread.sleep(50);
                boolean uiRequiereUpdate = false;
                String data;
                // Drena la cola completamente antes de actualizar la UI
                // Esto garantiza que el reloj avance a saltos exactos sin retrasos por
                // acumulación
                while ((data = cliente.leerString()) != null) {
                    if (data.startsWith("TOPIC:" + AlmacenDatosRAM.topicSubDone)) {
                         myHandler.post(this::finalizarDescarga);
                         continue;
                    }
                    
                    if (procesadorTelemetria.procesarDato(data, this)) {
                        myHandler.post(this::sincronizarControles); // Handler nativo para tocar la UI
                    }

                    // GESTIÓN DE ACK (Confirmación de Datalogger)
                    if (AlmacenDatosRAM.pendingAckId != null) {
                        cliente.publicar(AlmacenDatosRAM.topicPubAck, AlmacenDatosRAM.pendingAckId);
                        AlmacenDatosRAM.pendingAckId = null; // Limpiar para el siguiente
                    }

                    uiRequiereUpdate = true;
                }

                if (uiRequiereUpdate) {
                    myHandler.post(this::actualizarUI);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Cuando Android resume la app, el sistema puede haber matado el socket en
        // segundo plano.
        // Forzamos un reinicio de la conexión MQTT de forma segura.
        if (cliente != null) {
            AlmacenDatosRAM.conectado_PubSub = "Restaurando conexión MQTT...";
            AlmacenDatosRAM.conectado = false;
            cliente.desconectar();
            myHandler.postDelayed(() -> {
                if (!AlmacenDatosRAM.conectado) {
                    cliente.conectar();
                }
            }, 1000);
        }
    }

    @Override
    protected void onDestroy() {
        threadRunning = false;
        if (hilo != null) {
            hilo.interrupt();
        }
        super.onDestroy();
    }

    private void actualizarUI() {
        // --- 1. TABLA DE TRACKING Y ERROR ---
        ui.solAz.setText(String.format("%.1f°", AlmacenDatosRAM.sol_az));
        ui.solEl.setText(String.format("%.1f°", AlmacenDatosRAM.sol_el));
        ui.servoAz.setText(String.format("%.1f°", AlmacenDatosRAM.servo_az));
        ui.servoEl.setText(String.format("%.1f°", AlmacenDatosRAM.servo_el));

        float eAz = AlmacenDatosRAM.servo_az - AlmacenDatosRAM.sol_az;
        float eEl = AlmacenDatosRAM.servo_el - AlmacenDatosRAM.sol_el;
        if (eAz > 180)
            eAz -= 360;
        if (eAz < -180)
            eAz += 360; // Normalización Azimut

        ui.errAz.setText(String.format("%+.1f°", eAz));
        ui.errEl.setText(String.format("%+.1f°", eEl));
        ui.errAz.setTextColor(Math.abs(eAz) > 1.0 ? ui.COLOR_ERROR : ui.COLOR_ACCENT);
        ui.errEl.setTextColor(Math.abs(eEl) > 1.0 ? ui.COLOR_ERROR : ui.COLOR_ACCENT);

        // --- 2. BALANCE ENERGÉTICO ---
        ui.p1Inst.setText(String.format("%.2f", AlmacenDatosRAM.p1_inst));
        ui.p1Avg.setText(String.format("%.2f", AlmacenDatosRAM.p1_avg));
        ui.p1Daily.setText(String.format("%.1f", AlmacenDatosRAM.p1_avg_dia));
        ui.p2Inst.setText(String.format("%.2f", AlmacenDatosRAM.p2_inst));
        ui.p2Avg.setText(String.format("%.2f", AlmacenDatosRAM.p2_avg));
        ui.p2Daily.setText(String.format("%.1f", AlmacenDatosRAM.p2_avg_dia));

        if (AlmacenDatosRAM.p2_avg_dia > 0.1f) {
            float g = ((AlmacenDatosRAM.p1_avg_dia / AlmacenDatosRAM.p2_avg_dia) - 1) * 100;
            ui.labelGanancia.setText(String.format("%+.1f%%", g));
            ui.actualizarGananciaColor(g);
        } else {
            ui.labelGanancia.setText("--- %");
            ui.actualizarGananciaColor(0);
        }

        // --- 3. HEALTH DASHBOARD ---
        if (!AlmacenDatosRAM.conectado) {
            ui.actualizarEstadoGlobal(-1, "DESCONECTADO");
        } else {
            // Verificar si el dato global está obsoleto (>5s)
            long diffGlobal = (System.currentTimeMillis() - AlmacenDatosRAM.ts_mqtt) / 1000;
            if (diffGlobal > 5) {
                ui.actualizarEstadoGlobal(-1, "SIN DATOS");
            } else {
                int global = AlmacenDatosRAM.health_global;
                String status = (global == 0) ? "OPERANDO" : (global == 1 ? "ADVERTENCIA" : "FALLA");
                ui.actualizarEstadoGlobal(global, status);
            }
        }
        
        // --- 3.1 ALERTA DE DEGRADACIÓN ---
        if (AlmacenDatosRAM.conectado_PubSub.startsWith("DEG:")) {
            String comp = AlmacenDatosRAM.conectado_PubSub.substring(4);
            AlmacenDatosRAM.conectado_PubSub = "Monitoreo activo";
            Snackbar sb = Snackbar.make(findViewById(android.R.id.content), 
                "⚠️ ALERTA: Falla en " + comp, Snackbar.LENGTH_LONG);
            sb.setBackgroundTint(ui.COLOR_HEALTH_CRIT);
            sb.show();
        }


        // --- 4. FEEDBACK DE CONTROLES ---
        long dt = System.currentTimeMillis() - lastManualInteractionTime;
        if (!intervencionGPS && dt > MANUAL_LOCKOUT_MS) {
            ui.sliderLat.setProgress(Math.round(AlmacenDatosRAM.lat * 100 + 9000));
            ui.sliderLon.setProgress(Math.round(AlmacenDatosRAM.lon * 100 + 18000));
        }
        
        // Feedback dinámico de comandos
        actualizarFeedback("lat", AlmacenDatosRAM.lat, ui.feedLat);
        actualizarFeedback("lon", AlmacenDatosRAM.lon, ui.feedLon);
        actualizarFeedback("ser_az", AlmacenDatosRAM.servo_az, ui.feedAz);
        actualizarFeedback("ser_el", AlmacenDatosRAM.servo_el, ui.feedEl);

        if (dt > MANUAL_LOCKOUT_MS) {
            intervencionServo = false;
            ui.sliderManualAz.setProgress(Math.round(AlmacenDatosRAM.servo_az + 90));
            ui.sliderManualEl.setProgress(Math.round(AlmacenDatosRAM.servo_el));
            actualizarEstadoModo(AlmacenDatosRAM.modo.equals("AUTO"));
        }

        // --- 5. ESTADO DE DESCARGA ---
        if (ui.progressDownload.getVisibility() == View.VISIBLE) {
            int count = AlmacenDatosRAM.registrosDatalogger.size();
            ui.labelDownloadCount.setText(count + " registros recibidos");
            // Indeterminado hasta que llegue solar/data/done
        }

        ui.textviewFechaHora.setText(AlmacenDatosRAM.fecha + " " + AlmacenDatosRAM.hora);
        ui.textviewAviso.setText(AlmacenDatosRAM.conectado_PubSub);
        
        if (!AlmacenDatosRAM.conectado) {
            if (ui.botonConectar.getText().equals("Conectando...")) {
                 // Sigue intentando
            } else {
                ui.botonConectar.setText("CONECTAR");
                ui.botonConectar.setEnabled(true);
            }
        } else {
            ui.botonConectar.setText("DESCONECTAR");
            ui.botonConectar.setEnabled(true);
        }
    }

    @Override
    public void onBackPressed() {
        new DialogoSalir(this).mostrarPopMenuCoeficientes();
    }
}

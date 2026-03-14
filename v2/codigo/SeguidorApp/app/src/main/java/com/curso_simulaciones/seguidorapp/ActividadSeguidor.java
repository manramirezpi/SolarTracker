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
                AlmacenDatosRAM.resetStats(); // Limpiar promedios previos para empezar fresco
                cliente.conectar();
            } else {
                cliente.desconectar();
                AlmacenDatosRAM.conectado = false;
                AlmacenDatosRAM.conectado_PubSub = "Desconectado";
                procesadorTelemetria.resetSync(); // Reset sync flag for next connection
            }
        });

        ui.btnAuto.setOnClickListener(v -> {
            publicarComando("reset", 0, false);
            actualizarEstadoModo(true);
        });

        ui.btnMan.setOnClickListener(v -> {
            actualizarEstadoModo(false);
        });

        ui.botonResetGPS.setOnClickListener(v -> {
            sincronizarControles();
            publicarComando("reset", 0, false);
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
            }
        });

        ui.sliderTiempo.setOnValueChangeListener(value -> {
            AlmacenDatosRAM.factor_vel = value;
            // El tiempo suele ir ligado al GPS, pero lo tratamos como intervención GPS
            intervencionGPS = true;
            publicarComando("set_vel", value, true);
        });

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
            }
        });

        ui.botonTemp.setOnClickListener(v -> {
            if (AlmacenDatosRAM.conectado) {
                publicarComando("get_temp", 0, false);
                AlmacenDatosRAM.conectado_PubSub = "Solicitando Snapshot...";
            }
        });

        ui.botonShare.setOnClickListener(v -> compartirArchivos());
    }

    private void compartirArchivos() {
        File folder = getExternalFilesDir(null);
        if (folder == null)
            return;

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".txt") && !name.startsWith("RECEP_"));
        if (files == null || files.length == 0) {
            AlmacenDatosRAM.conectado_PubSub = "No hay archivos para compartir";
            return;
        }

        // Ordenar por fecha (más reciente primero)
        List<File> fileList = new ArrayList<>();
        Collections.addAll(fileList, files);
        Collections.sort(fileList, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        String[] names = new String[fileList.size()];
        for (int i = 0; i < fileList.size(); i++) {
            names[i] = fileList.get(i).getName();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Seleccione archivo para enviar");
        builder.setItems(names, (dialog, which) -> {
            File fileToShare = fileList.get(which);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", fileToShare);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Datos SolarTracker: " + fileToShare.getName());
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(intent, "Enviar datos vía..."));
        });
        builder.show();
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

        // Resetear velocidad
        ui.sliderTiempo.setValue(1.0f);
        actualizarEstadoModo(AlmacenDatosRAM.modo.equals("AUTO"));
    }

    private void actualizarEstadoModo(boolean isAuto) {
        ui.btnAuto.setTextColor(isAuto ? Color.WHITE : ui.COLOR_TEXTO_SEC);
        ui.btnAuto.getBackground().setColorFilter(isAuto ? ui.COLOR_ACCENT : Color.LTGRAY, PorterDuff.Mode.MULTIPLY);
        ui.btnMan.setTextColor(!isAuto ? Color.WHITE : ui.COLOR_TEXTO_SEC);
        ui.btnMan.getBackground().setColorFilter(!isAuto ? ui.COLOR_ACCENT : Color.LTGRAY, PorterDuff.Mode.MULTIPLY);

        // Bloquear/Desbloquear sliders manuales según el modo
        ui.sliderManualAz.setEnabled(!isAuto);
        ui.sliderManualEl.setEnabled(!isAuto);
        ui.labelManualAz.setAlpha(isAuto ? 0.5f : 1.0f);
        ui.labelManualEl.setAlpha(isAuto ? 0.5f : 1.0f);
    }

    private void publicarComando(String cmd, float valor, boolean conValor) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("cmd", cmd);
            if (conValor) {
                if (cmd.equals("set_vel")) {
                    obj.put("factor", (int) valor);
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
                    if (procesadorTelemetria.procesarDato(data, this)) {
                        myHandler.post(this::sincronizarControles); // Handler nativo para tocar la UI
                    }

                    // GESTIÓN DE ACK (Confirmación de Datalogger)
                    if (AlmacenDatosRAM.pendingAckId != null) {
                        cliente.publicar("solar/data/ack", AlmacenDatosRAM.pendingAckId);
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
        } else {
            ui.labelGanancia.setText("--- %");
        }

        // --- 3. HEALTH DASHBOARD ---
        if (!AlmacenDatosRAM.conectado)
            AlmacenDatosRAM.health_mqtt = 0;
        else if (AlmacenDatosRAM.health_mqtt == 0)
            AlmacenDatosRAM.health_mqtt = 2; // Green if connected unless ESP32 says otherwise

        ui.actualizarEstadoIcono(ui.iconHealthMqtt, AlmacenDatosRAM.health_mqtt);
        ui.actualizarEstadoIcono(ui.iconHealthGps, AlmacenDatosRAM.health_gps);
        ui.actualizarEstadoIcono(ui.iconHealthIna, AlmacenDatosRAM.health_ina);
        ui.actualizarEstadoIcono(ui.iconHealthLog, AlmacenDatosRAM.health_disk > 90 ? 1 : 2); // Warn if log > 90%

        // --- 4. FEEDBACK DE CONTROLES ---
        long dt = System.currentTimeMillis() - lastManualInteractionTime;
        if (!intervencionGPS && dt > MANUAL_LOCKOUT_MS) {
            ui.sliderLat.setProgress(Math.round(AlmacenDatosRAM.lat * 100 + 9000));
            ui.sliderLon.setProgress(Math.round(AlmacenDatosRAM.lon * 100 + 18000));
            ui.sliderTiempo.setValue(AlmacenDatosRAM.factor_vel);
        }

        if (dt > MANUAL_LOCKOUT_MS) {
            intervencionServo = false;
            ui.sliderManualAz.setProgress(Math.round(AlmacenDatosRAM.servo_az + 90));
            ui.sliderManualEl.setProgress(Math.round(AlmacenDatosRAM.servo_el));
            actualizarEstadoModo(AlmacenDatosRAM.modo.equals("AUTO"));
        }

        ui.textviewFechaHora.setText(AlmacenDatosRAM.fecha + " " + AlmacenDatosRAM.hora);
        ui.textviewAviso.setText(AlmacenDatosRAM.conectado_PubSub);
        ui.botonConectar.setText(AlmacenDatosRAM.conectado ? "DESCONECTAR" : "CONECTAR");
    }

    @Override
    public void onBackPressed() {
        new DialogoSalir(this).mostrarPopMenuCoeficientes();
    }
}

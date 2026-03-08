package com.curso_simulaciones.seguidorapp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.curso_simulaciones.seguidorapp.comunicaciones.ClientePubSubMQTT;
import com.curso_simulaciones.seguidorapp.datos.AlmacenDatosRAM;
import com.curso_simulaciones.seguidorapp.utilidades.CircularSlider;
import com.curso_simulaciones.seguidorapp.utilidades.DialogoSalir;
import com.curso_simulaciones.seguidorapp.utilidades.GaugeSimple;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class ActividadSeguidor extends Activity implements Runnable {

    private int tamanoLetraResolucionIncluida;
    private Button botonConectar, botonResetGPS;
    private TextView textviewAviso, textviewFechaHora;

    private GaugeSimple gaugeSolAz, gaugeSolEl;
    private GaugeSimple gaugeServoAz, gaugeServoEl;
    private SeekBar sliderLat, sliderLon;
    private SeekBar sliderManualAz, sliderManualEl;
    private CircularSlider sliderTiempo;
    private TextView labelLat, labelLon, labelManualAz, labelManualEl;
    private TextView p1Title, p1Inst, p1Avg, p1Daily, p2Title, p2Inst, p2Avg, p2Daily; 
    private TextView labelEficiencia, labelEstadoGPS; 

    private ClientePubSubMQTT cliente;
    private Thread hilo;
    private final Handler myHandler = new Handler();

    // Colores premium
    private final int COLOR_FONDO = Color.rgb(18, 18, 18);
    private final int COLOR_CARD = Color.rgb(30, 33, 40);
    private final int COLOR_ACCENT = Color.rgb(0, 230, 118); // Verde neón
    private final int COLOR_TEXTO_PRI = Color.WHITE;
    private final int COLOR_TEXTO_SEC = Color.rgb(180, 180, 180);

    private boolean primeraVez = true;
    private boolean intervencionGPS = false;   // Prioridad manual sobre coordenadas
    private boolean intervencionServo = false; // Prioridad manual sobre ángulos servo
    private long lastManualInteractionTime = 0; 
    private long ultimoTiempoPublishGPS = 0;   // Throttling MQTT GPS
    private long ultimoTiempoPublishServo = 0; // Throttling MQTT Servos
    private static final long MANUAL_LOCKOUT_MS = 3000; 
    private int contadorMuestreoPotencia = 0; // Contador para diezmado (1 de cada 10)
    private volatile boolean threadRunning = true; // Control del hilo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        gestionarResolucion();
        crearElementosGUI();

        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        this.setContentView(crearGUI(), params);

        eventos();
        cliente = new ClientePubSubMQTT(this);
        hilo = new Thread(this);
        hilo.start();
    }

    private void gestionarResolucion() {
        AlmacenDatosRAM.tamanoLetraResolucionIncluida = 18; 
        tamanoLetraResolucionIncluida = (int) (0.8 * AlmacenDatosRAM.tamanoLetraResolucionIncluida);
    }

    private void crearElementosGUI() {
        // Gauges Sol
        gaugeSolAz = new GaugeSimple(this);
        gaugeSolAz.setRango(0, 360);
        gaugeSolAz.setUnidades("Sol Az (°)");

        gaugeSolEl = new GaugeSimple(this);
        gaugeSolEl.setRango(-90, 90);
        gaugeSolEl.setDivisiones(6);
        gaugeSolEl.setUnidades("Sol El (°)");

        // Gauges Servos
        gaugeServoAz = new GaugeSimple(this);
        gaugeServoAz.setRango(-90, 90); // Rango visual -90 a 90
        gaugeServoAz.setDivisiones(6);
        gaugeServoAz.setUnidades("Servo Az (°)");

        gaugeServoEl = new GaugeSimple(this);
        gaugeServoEl.setRango(0, 180); // Revertido a 0-180 fiel al hardware
        gaugeServoEl.setDivisiones(9);
        gaugeServoEl.setUnidades("Servo El (°)");

        // Sliders GPS/Config
        sliderLat = configSeekBar(0, 18000, 9000);
        sliderLon = configSeekBar(0, 36000, 18000);
        
        labelLat = configLabel("Lat: 0.00");
        labelLon = configLabel("Lon: 0.00");

        // Sliders Manuales (Mapeo 0-180 para evitar artefactos de color, igual que los GPS)
        sliderManualAz = configSeekBar(0, 180, 90); 
        sliderManualEl = configSeekBar(0, 180, 90);
        labelManualAz = configLabel("Manual Az: 0°"); 
        labelManualEl = configLabel("Manual El: 90°");

        // Circular Slider
        sliderTiempo = new CircularSlider(this);
        sliderTiempo.setRange(1.0f, 1440.0f);
        sliderTiempo.setValue(1.0f);
        sliderTiempo.setLabel("Factor Vel");

        // Buttons
        botonConectar = new Button(this);
        botonConectar.setText("CONECTAR");
        botonConectar.setTextColor(Color.BLACK);
        botonConectar.setTextSize(12);
        botonConectar.getBackground().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.MULTIPLY);

        botonResetGPS = new Button(this);
        botonResetGPS.setText("VOLVER A GPS");
        botonResetGPS.setTextColor(Color.WHITE);
        botonResetGPS.getBackground().setColorFilter(Color.rgb(200, 50, 50), PorterDuff.Mode.MULTIPLY);

        textviewAviso = new TextView(this);
        textviewAviso.setGravity(Gravity.CENTER);
        textviewAviso.setTextColor(COLOR_ACCENT);
        textviewAviso.setTextSize(14);
        textviewAviso.setText(AlmacenDatosRAM.conectado_PubSub);

        textviewFechaHora = new TextView(this);
        textviewFechaHora.setGravity(Gravity.CENTER);
        textviewFechaHora.setTextColor(COLOR_TEXTO_SEC);
        textviewFechaHora.setTextSize(12);
        textviewFechaHora.setText("Fecha/Hora: --");

        // Labels Potencia Canal 1
        p1Title = configLabel("PANEL MÓVIL");
        p1Title.setTextColor(COLOR_ACCENT);
        p1Title.setTextSize(12);
        p1Title.setTypeface(null, android.graphics.Typeface.BOLD);
        p1Inst = configLabel("Inst: -- mW");
        p1Avg = configLabel("Med: -- mW");
        p1Daily = configLabel("Dia: -- mW");

        // Labels Potencia Canal 2
        p2Title = configLabel("PANEL FIJO");
        p2Title.setTextColor(COLOR_ACCENT);
        p2Title.setTextSize(12);
        p2Title.setTypeface(null, android.graphics.Typeface.BOLD);
        p2Inst = configLabel("Inst: -- mW");
        p2Avg = configLabel("Med: -- mW");
        p2Daily = configLabel("Dia: -- mW");

        labelEficiencia = configLabel("Ganancia Móvil: -- %");
        labelEficiencia.setTextSize(14);
        labelEficiencia.setTextColor(COLOR_ACCENT); // Removed BOLD

        labelEstadoGPS = configLabel("GPS: Buscando...");
    }

    private SeekBar configSeekBar(int min, int max, int progress) {
        SeekBar sb = new SeekBar(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            sb.setMin(min);
            sb.setMax(max);
        } else {
            // Fallback para APIs antiguas: el rango se desplaza
            sb.setMax(max - min);
        }
        sb.setProgress(progress);
        
        sb.getProgressDrawable().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        return sb;
    }

    private TextView configLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXTO_SEC);
        tv.setTextSize(13);
        tv.setPadding(0, 5, 0, 0);
        return tv;
    }

    private LinearLayout crearGUI() {
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(COLOR_FONDO);
        main.setPadding(20, 20, 20, 20);
        main.setWeightSum(10.0f);

        // Header (Weight 0.6)
        LinearLayout headerContenedor = new LinearLayout(this);
        headerContenedor.setGravity(Gravity.CENTER);
        headerContenedor.setBackground(crearFondoHeader());
        
        TextView header = new TextView(this);
        header.setText("SOLAR TRACKER PRO");
        header.setTextSize(20); 
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(COLOR_ACCENT);
        header.setGravity(Gravity.CENTER);
        headerContenedor.addView(header);
        
        main.addView(headerContenedor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f));

        // Paneles Superiores: GAUGES HORIZONTALES (Weight 3.8)
        LinearLayout rowGauges = new LinearLayout(this);
        rowGauges.setOrientation(LinearLayout.HORIZONTAL);
        rowGauges.setWeightSum(2.0f);
        
        LinearLayout.LayoutParams paramsG1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        paramsG1.setMargins(0, 0, 0, 0);
        rowGauges.addView(crearContenedorInstrumentos("SOL REAL", gaugeSolAz, gaugeSolEl), paramsG1);
        
        // Espacio entre bloques de gauges (Pasillo Horizontal)
        View gapGauges = new View(this);
        rowGauges.addView(gapGauges, new LinearLayout.LayoutParams(15, ViewGroup.LayoutParams.MATCH_PARENT));
        
        LinearLayout.LayoutParams paramsG2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        paramsG2.setMargins(0, 0, 0, 0);
        rowGauges.addView(crearContenedorInstrumentos("SERVOS", gaugeServoAz, gaugeServoEl), paramsG2);
        
        main.addView(rowGauges, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.8f));

        // PASILLO VERTICAL (Separación entre Gauges y Controles)
        View verticalGap = new View(this);
        main.addView(verticalGap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 15));

        // Controles GPS y Manuales (Weight 4.7)
        LinearLayout rowControles = new LinearLayout(this);
        rowControles.setOrientation(LinearLayout.HORIZONTAL);
        rowControles.setWeightSum(10.0f);
        
        // Columna 1: GPS, Velocidad y Rendimiento (Weight 5)
        LinearLayout col1 = new LinearLayout(this);
        col1.setOrientation(LinearLayout.VERTICAL);
        col1.setPadding(0, 0, 0, 0); 
        
        // Tarjeta GPS
        LinearLayout cardGPS = new LinearLayout(this);
        cardGPS.setOrientation(LinearLayout.VERTICAL);
        cardGPS.setBackground(crearFondoCard());
        cardGPS.setPadding(20, 15, 20, 15);
        
        LinearLayout.LayoutParams pCardGPS = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 6.5f);
        pCardGPS.setMargins(0, 0, 0, 0); 
        cardGPS.setLayoutParams(pCardGPS);
        
        TextView titleGPS = new TextView(this);
        titleGPS.setText("UBICACIÓN Y TIEMPO");
        titleGPS.setTextColor(COLOR_ACCENT);
        titleGPS.setTextSize(12);
        titleGPS.setTypeface(null, android.graphics.Typeface.BOLD); // Set BOLD
        titleGPS.setGravity(Gravity.CENTER);
        
        cardGPS.addView(titleGPS);
        cardGPS.addView(labelLat);
        cardGPS.addView(sliderLat);
        cardGPS.addView(labelLon);
        cardGPS.addView(sliderLon);
        
        LinearLayout.LayoutParams pSpeed = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.5f);
        pSpeed.setMargins(0, 10, 0, 0);
        cardGPS.addView(sliderTiempo, pSpeed);
        
        col1.addView(cardGPS);

        // Pasillo Vertical interno col 1
        View gapV1 = new View(this);
        col1.addView(gapV1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 15));
        
        // Tarjeta Rendimiento
        LinearLayout cardPerf = new LinearLayout(this);
        cardPerf.setOrientation(LinearLayout.VERTICAL);
        cardPerf.setBackground(crearFondoCard());
        cardPerf.setPadding(20, 15, 20, 15);
        cardPerf.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams pCardPerf = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.5f);
        pCardPerf.setMargins(0, 0, 0, 0); 
        cardPerf.setLayoutParams(pCardPerf);

        TextView titlePerf = new TextView(this);
        titlePerf.setText("RENDIMIENTO Y GPS");
        titlePerf.setTextColor(COLOR_ACCENT);
        titlePerf.setTextSize(12);
        titlePerf.setTypeface(null, android.graphics.Typeface.BOLD); // Set BOLD
        titlePerf.setGravity(Gravity.CENTER);
        
        cardPerf.addView(titlePerf);
        cardPerf.addView(labelEficiencia);
        cardPerf.addView(labelEstadoGPS); // Original line
        
        col1.addView(cardPerf);
        
        rowControles.addView(col1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5.0f));

        // PASILLO CENTRAL (Coincide con el de arriba: 15px)
        View gapCentral = new View(this);
        rowControles.addView(gapCentral, new LinearLayout.LayoutParams(15, ViewGroup.LayoutParams.MATCH_PARENT));

        // Columna 2: Control Independiente y Potencia (Weight 5)
        LinearLayout col2 = new LinearLayout(this);
        col2.setOrientation(LinearLayout.VERTICAL);
        col2.setPadding(0, 0, 0, 0); 
        col2.setWeightSum(10.0f); // Explicit weight sum

        // Sub-Tarjeta 1: Control Manual
        LinearLayout cardManual = new LinearLayout(this);
        cardManual.setOrientation(LinearLayout.VERTICAL);
        cardManual.setBackground(crearFondoCard());
        cardManual.setPadding(20, 15, 20, 5); // Bottom padding reduced to 5
        
        LinearLayout.LayoutParams pCardManual = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4.2f);
        pCardManual.setMargins(0, 0, 0, 0); 
        cardManual.setLayoutParams(pCardManual);
        
        TextView titleMan = new TextView(this);
        titleMan.setText("MODO INDEPENDIENTE");
        titleMan.setTextColor(COLOR_ACCENT);
        titleMan.setTextSize(12);
        titleMan.setTypeface(null, android.graphics.Typeface.BOLD); // Set BOLD
        titleMan.setGravity(Gravity.CENTER);
        
        cardManual.addView(titleMan);
        cardManual.addView(labelManualAz);
        cardManual.addView(sliderManualAz);
        cardManual.addView(labelManualEl);
        cardManual.addView(sliderManualEl);
        
        LinearLayout.LayoutParams pBtnReset = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pBtnReset.setMargins(0, 0, 0, 0); // Margin removed to stick to slider above
        cardManual.addView(botonResetGPS, pBtnReset);

        col2.addView(cardManual, pCardManual);

        // Pasillo Vertical interno col 2 (Corregido a 15px para simetría)
        View gap2 = new View(this);
        col2.addView(gap2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 15));

        LinearLayout panelPot = new LinearLayout(this);
        panelPot.setOrientation(LinearLayout.VERTICAL);
        panelPot.setPadding(20, 15, 20, 15); // Padding igualado a las otras tarjetas
        panelPot.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams pPanelPot = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 5.8f);
        pPanelPot.setMargins(0, 0, 0, 0); 
        panelPot.setLayoutParams(pPanelPot);
        
        android.graphics.drawable.GradientDrawable bgPot = crearFondoCard();
        bgPot.setColor(Color.rgb(35, 40, 50)); 
        panelPot.setBackground(bgPot);
        
        panelPot.addView(p1Title);
        panelPot.addView(p1Inst);
        panelPot.addView(p1Avg);
        panelPot.addView(p1Daily);
        
        View space = new View(this);
        panelPot.addView(space, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10));
        
        panelPot.addView(p2Title);
        panelPot.addView(p2Inst);
        panelPot.addView(p2Avg);
        panelPot.addView(p2Daily);

        col2.addView(panelPot, pPanelPot);
        
        rowControles.addView(col2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5.0f));
        
        main.addView(rowControles, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4.7f));

        // Footer (Weight 1.0)
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(10, 5, 10, 0);
        footer.setBackgroundColor(Color.rgb(25, 25, 25));
        
        LinearLayout statusCol = new LinearLayout(this);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusCol.addView(textviewFechaHora);
        statusCol.addView(textviewAviso);
        
        footer.addView(statusCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        footer.addView(botonConectar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f));
        
        main.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        return main;
    }

    private LinearLayout crearItemInstrumento(String label, View gauge) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackground(crearFondoCard());
        l.setPadding(5, 5, 5, 5);
        
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(COLOR_TEXTO_SEC);
        tv.setTextSize(10);
        l.addView(tv);
        l.addView(gauge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(0, 5, 10, 5);
        l.setLayoutParams(params);
        return l;
    }


    private LinearLayout crearContenedorInstrumentos(String titulo, View g1, View g2) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(10, 10, 10, 10);
        l.setGravity(Gravity.CENTER);
        l.setBackground(crearFondoCard());
        
        TextView tv = new TextView(this);
        tv.setText(titulo);
        tv.setTextColor(COLOR_TEXTO_SEC);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD); // Set BOLD
        tv.setGravity(Gravity.CENTER);
        l.addView(tv);
        
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(g1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        col.addView(g2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        l.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        
        return l;
    }

    private android.graphics.drawable.GradientDrawable crearFondoHeader() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.rgb(30, 35, 45)); // Oscuro elegante
        gd.setCornerRadius(0);
        gd.setStroke(2, COLOR_ACCENT); // Borde neón sutil
        return gd;
    }

    private android.graphics.drawable.GradientDrawable crearFondoCard() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(20);
        gd.setStroke(2, Color.rgb(50, 55, 65));
        return gd;
    }

    private void eventos() {
        botonConectar.setOnClickListener(v -> {
            if (!AlmacenDatosRAM.conectado) {
                cliente.conectar();
            } else {
                cliente.desconectar();
                AlmacenDatosRAM.conectado = false;
                AlmacenDatosRAM.conectado_PubSub = "Desconectado";
                primeraVez = true; // Reset sync flag for next connection
            }
        });

        botonResetGPS.setOnClickListener(v -> {
            sincronizarControles();
            publicarComando("reset", 0, false);
        });

        sliderLat.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float lat = (progress - 9000) / 100f;
                labelLat.setText(String.format("Lat: %.2f", lat));
                if (fromUser) {
                    intervencionGPS = true;   // Usuario toma prioridad en GPS
                    intervencionServo = false; // Al mover coords, liberamos los servos para que sigan al nuevo punto
                    lastManualInteractionTime = System.currentTimeMillis(); 
                    if (System.currentTimeMillis() - ultimoTiempoPublishGPS > 150) {
                        publicarComando("set_lat", lat, true);
                        ultimoTiempoPublishGPS = System.currentTimeMillis();
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float lat = (seekBar.getProgress() - 9000) / 100f;
                publicarComando("set_lat", lat, true);
            }
        });

        sliderLon.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float lon = (progress - 18000) / 100f;
                labelLon.setText(String.format("Lon: %.2f", lon));
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
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float lon = (seekBar.getProgress() - 18000) / 100f;
                publicarComando("set_lon", lon, true);
            }
        });

        sliderTiempo.setOnValueChangeListener(value -> {
            AlmacenDatosRAM.factor_vel = value;
            // El tiempo suele ir ligado al GPS, pero lo tratamos como intervención GPS
            intervencionGPS = true; 
            publicarComando("set_vel", value, true);
        });

        // Eventos Manuales
        sliderManualAz.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int valorReal = progress - 90; // Mapeo visual a valor astronómico
                labelManualAz.setText("Manual Az: " + valorReal + "°");
                if (fromUser) {
                    intervencionServo = true;
                    lastManualInteractionTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - ultimoTiempoPublishServo > 150) {
                        publicarComando("set_ser_az", valorReal, true);
                        ultimoTiempoPublishServo = System.currentTimeMillis();
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int valorReal = seekBar.getProgress() - 90;
                publicarComando("set_ser_az", valorReal, true);
            }
        });

        sliderManualEl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelManualEl.setText("Manual El: " + progress + "°");
                if (fromUser) {
                    intervencionServo = true; // El usuario toma prioridad absoluta
                    lastManualInteractionTime = System.currentTimeMillis(); 
                    if (System.currentTimeMillis() - ultimoTiempoPublishServo > 150) {
                        publicarComando("set_ser_el", progress, true);
                        ultimoTiempoPublishServo = System.currentTimeMillis();
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                publicarComando("set_ser_el", seekBar.getProgress(), true);
            }
        });
    }

    private void sincronizarControles() {
        intervencionGPS = false;
        intervencionServo = false; 
        lastManualInteractionTime = 0; // Permitir el seguimiento inmediato tras el reset
        // Sincronizar GPS
        int progLat = (int) (AlmacenDatosRAM.lat * 100 + 9000);
        int progLon = (int) (AlmacenDatosRAM.lon * 100 + 18000);
        sliderLat.setProgress(progLat);
        sliderLon.setProgress(progLon);
        
        // Sincronizar Manuales con posición real
        sliderManualAz.setProgress((int) AlmacenDatosRAM.servo_az + 90);
        sliderManualEl.setProgress((int) AlmacenDatosRAM.servo_el);
        
        // Resetear velocidad
        sliderTiempo.setValue(1.0f);
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


    @Override
    public void run() {
        while (threadRunning) {
            try {
                // Aumentamos frecuencia a 20Hz (50ms) para procesar rápido la cola
                Thread.sleep(50);
                boolean uiRequiereUpdate = false;
                String data;
                // Drena la cola completamente antes de actualizar la UI
                // Esto garantiza que el reloj avance a saltos exactos sin retrasos por acumulación
                while ((data = cliente.leerString()) != null) {
                    procesarDato(data);
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
        // Cuando Android resume la app, el sistema puede haber matado el socket en segundo plano.
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

    private void procesarDato(String data) {
        try {
            // ESTRATEGIA DE OPTIMIZACIÓN (Garbage Collector Bypass):
            // Las tramas "FAST" (a 4Hz) causan saturación de memoria en Android si se usa JSONObject repetidamente.
            // Para la telemetría viva extraemos los valores de forma plana usando punteros en el String base, 
            // evitando instanciar miles de objetos JSON y Arrays en la RAM.
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
                return; // Sale temprano, las tramas rápidas no traen datos de GPS o Fecha
            }
            
            // --- PROCESAMIENTO CANAL LENTO (1Hz) ---
            // Como las tramas lentas (GPS, hora, modo) solo ocurren 1 vez por segundo, 
            // usar JSONObject aquí NO impacta la batería ni la fluidez del dispositivo.
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

            // Si es el primer dato que llega, sincronizamos sliders
            if (primeraVez && AlmacenDatosRAM.lat != 0) {
                myHandler.post(() -> {
                    sincronizarControles();
                    primeraVez = false;
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Extractor nativo ultraligero que evita instanciar un motor JSON y reduce la basura (Garbage)
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

    private void actualizarUI() {
        gaugeSolAz.setMedida(AlmacenDatosRAM.sol_az);
        gaugeSolEl.setMedida(AlmacenDatosRAM.sol_el);
        gaugeServoAz.setMedida(AlmacenDatosRAM.servo_az); // Valor fiel
        gaugeServoEl.setMedida(AlmacenDatosRAM.servo_el); // Valor fiel

        // --- SINCRONIZACIÓN AUTOMÁTICA DE CONTROLES (FEEDBACK) ---
        long dt = System.currentTimeMillis() - lastManualInteractionTime;
        
        // 1. Sincronizar Sliders de Ubicación (GPS)
        // Solo si NO hay intervención manual en GPS y ha pasado el tiempo de gracia
        if (!intervencionGPS && dt > MANUAL_LOCKOUT_MS) {
            int progLat = Math.round(AlmacenDatosRAM.lat * 100 + 9000);
            int progLon = Math.round(AlmacenDatosRAM.lon * 100 + 18000);
            if (sliderLat.getProgress() != progLat) sliderLat.setProgress(progLat);
            if (sliderLon.getProgress() != progLon) sliderLon.setProgress(progLon);

            if (Math.abs(sliderTiempo.getValue() - AlmacenDatosRAM.factor_vel) > 0.01f) {
                sliderTiempo.setValue(AlmacenDatosRAM.factor_vel);
            }
        }

        // 2. Sincronizar Sliders Manuales (Servo Feedback)
        // Se sincronizan si ha pasado el tiempo de gracia (el usuario ya soltó el control)
        if (dt > MANUAL_LOCKOUT_MS) {
            intervencionServo = false; // Liberamos el flag para que reanuden la telemetría real
            int targetAz = Math.round(AlmacenDatosRAM.servo_az + 90); // Mapeo visual
            int targetEl = Math.round(AlmacenDatosRAM.servo_el);
            
            if (sliderManualAz.getProgress() != targetAz)
                sliderManualAz.setProgress(targetAz);
            
            if (sliderManualEl.getProgress() != targetEl)
                sliderManualEl.setProgress(targetEl);
        }

        textviewFechaHora.setText(AlmacenDatosRAM.fecha + " " + AlmacenDatosRAM.hora + " | Modo: " + AlmacenDatosRAM.modo);
        textviewAviso.setText(AlmacenDatosRAM.conectado_PubSub);

        // Actualizar Potencia Canal 1 (Ya en mW)
        p1Inst.setText(String.format("Inst: %.4f mW", AlmacenDatosRAM.p1_inst));
        p1Avg.setText(String.format("Med: %.4f mW", AlmacenDatosRAM.p1_avg));
        p1Daily.setText(String.format("Dia: %.4f mW", AlmacenDatosRAM.p1_avg_dia));

        // Actualizar Potencia Canal 2 (Ya en mW)
        p2Inst.setText(String.format("Inst: %.4f mW", AlmacenDatosRAM.p2_inst));
        p2Avg.setText(String.format("Med: %.4f mW", AlmacenDatosRAM.p2_avg));
        p2Daily.setText(String.format("Dia: %.4f mW", AlmacenDatosRAM.p2_avg_dia));

        // Cálculo Eficiencia
        if (AlmacenDatosRAM.p2_avg > 0.001f) {
            float ganancia = ((AlmacenDatosRAM.p1_avg_dia / AlmacenDatosRAM.p2_avg_dia) - 1) * 100;
            labelEficiencia.setText(String.format("Ganancia Móvil: %+.1f%%", ganancia));
        } else {
            labelEficiencia.setText("Ganancia Móvil: --- %");
        }

        // Estado GPS
        labelEstadoGPS.setText(AlmacenDatosRAM.gps_valido ? "GPS: SEÑAL ESTABLE" : "GPS: BUSCANDO...");
        labelEstadoGPS.setTextColor(AlmacenDatosRAM.gps_valido ? Color.GREEN : Color.YELLOW);

        if (AlmacenDatosRAM.conectado) {
            botonConectar.setText("DESCONECTAR");
        } else {
            botonConectar.setText("CONECTAR");
        }
    }

    @Override
    public void onBackPressed() {
        new DialogoSalir(this).mostrarPopMenuCoeficientes();
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
}

package com.solartracker.utilidades;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.solartracker.datos.AlmacenDatosRAM;

/**
 * Clase encargada de empaquetar, instancias y manipular la interfaz gráfica (UI).
 * Representa la Capa "Vista" en el patrón MVC/MVP.
 *
 * Expone variables públicas de sus componentes (TextView, SeekBar, Gauge) para 
 * que el controlador pueda suscribirse a los eventos y enviar actualizaciones de datos.
 */
public class GeneradorUI {

    private Activity actividad;

    public Button botonConectar, botonResetGPS, botonBatch, botonCompartir;
    public TextView textviewAviso, textviewFechaHora, textviewUptime;

    public GaugeSimple gaugeSolAz, gaugeSolEl;
    public GaugeSimple gaugeServoAz, gaugeServoEl;
    public SeekBar sliderLat, sliderLon;
    public SeekBar sliderManualAz, sliderManualEl;
    public CircularSlider sliderTiempo;
    public TextView labelLat, labelLon, labelManualAz, labelManualEl;
    public TextView p1Title, p1Inst, p1Avg, p1Daily, p2Title, p2Inst, p2Avg, p2Daily; 
    public TextView labelEficiencia, labelEstadoGPS; 

    public final int COLOR_FONDO = Color.rgb(18, 18, 18);
    public final int COLOR_CARD = Color.rgb(30, 33, 40);
    public final int COLOR_ACCENT = Color.rgb(0, 230, 118);
    public final int COLOR_TEXTO_PRI = Color.WHITE;
    public final int COLOR_TEXTO_SEC = Color.rgb(180, 180, 180);

    public GeneradorUI(Activity actividad) {
        this.actividad = actividad;
    }

    public void gestionarResolucion() {
        AlmacenDatosRAM.tamanoLetraResolucionIncluida = 18; 
    }

    public LinearLayout construir() {
        crearElementosGUI();
        return crearGUI();
    }

    private void crearElementosGUI() {
        gaugeSolAz = new GaugeSimple(actividad);
        gaugeSolAz.setRango(0, 360);
        gaugeSolAz.setUnidades("Sol Az (°)");

        gaugeSolEl = new GaugeSimple(actividad);
        gaugeSolEl.setRango(-90, 90);
        gaugeSolEl.setDivisiones(6);
        gaugeSolEl.setUnidades("Sol El (°)");

        gaugeServoAz = new GaugeSimple(actividad);
        gaugeServoAz.setRango(-90, 90);
        gaugeServoAz.setDivisiones(6);
        gaugeServoAz.setUnidades("Servo Az (°)");

        gaugeServoEl = new GaugeSimple(actividad);
        gaugeServoEl.setRango(0, 180);
        gaugeServoEl.setDivisiones(9);
        gaugeServoEl.setUnidades("Servo El (°)");

        sliderLat = configSeekBar(0, 18000, 9000);
        sliderLon = configSeekBar(0, 36000, 18000);
        
        labelLat = configLabel("Lat: 0.00");
        labelLon = configLabel("Lon: 0.00");

        sliderManualAz = configSeekBar(0, 180, 90); 
        sliderManualEl = configSeekBar(0, 180, 90);
        labelManualAz = configLabel("Manual Az: 0°"); 
        labelManualEl = configLabel("Manual El: 90°");

        sliderTiempo = new CircularSlider(actividad);
        sliderTiempo.setRange(1.0f, 1440.0f);
        sliderTiempo.setValue(1.0f);
        sliderTiempo.setLabel("Factor Vel");

        botonConectar = new Button(actividad);
        botonConectar.setText("CONECTAR");
        botonConectar.setTextColor(Color.BLACK);
        botonConectar.setTextSize(12);
        botonConectar.getBackground().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.MULTIPLY);

        botonResetGPS = new Button(actividad);
        botonResetGPS.setText("VOLVER A GPS");
        botonResetGPS.setTextColor(Color.WHITE);
        botonResetGPS.getBackground().setColorFilter(Color.rgb(200, 50, 50), PorterDuff.Mode.MULTIPLY);

        botonBatch = new Button(actividad);
        botonBatch.setText("DESCARGAR");
        botonBatch.setTextColor(Color.WHITE);
        botonBatch.setTextSize(10);
        botonBatch.getBackground().setColorFilter(Color.rgb(100, 100, 100), PorterDuff.Mode.MULTIPLY);

        botonCompartir = new Button(actividad);
        botonCompartir.setText("COMPARTIR");
        botonCompartir.setTextColor(Color.WHITE);
        botonCompartir.setTextSize(10);
        botonCompartir.getBackground().setColorFilter(Color.rgb(50, 50, 150), PorterDuff.Mode.MULTIPLY);
        botonCompartir.setEnabled(false);

        textviewAviso = new TextView(actividad);
        textviewAviso.setGravity(Gravity.CENTER);
        textviewAviso.setTextColor(COLOR_ACCENT);
        textviewAviso.setTextSize(14);
        textviewAviso.setText(AlmacenDatosRAM.conectado_PubSub);

        textviewFechaHora = new TextView(actividad);
        textviewFechaHora.setGravity(Gravity.CENTER);
        textviewFechaHora.setTextColor(COLOR_TEXTO_SEC);
        textviewFechaHora.setTextSize(12);
        textviewFechaHora.setText("Fecha/Hora: --");

        p1Title = configLabel("PANEL MÓVIL");
        p1Title.setTextColor(COLOR_ACCENT);
        p1Title.setTextSize(12);
        p1Title.setTypeface(null, android.graphics.Typeface.BOLD);
        p1Inst = configLabel("Inst: -- mW");
        p1Avg = configLabel("Med: -- mW");
        p1Daily = configLabel("E: -- mWh");

        p2Title = configLabel("PANEL FIJO");
        p2Title.setTextColor(COLOR_ACCENT);
        p2Title.setTextSize(12);
        p2Title.setTypeface(null, android.graphics.Typeface.BOLD);
        p2Inst = configLabel("Inst: -- mW");
        p2Avg = configLabel("Med: -- mW");
        p2Daily = configLabel("E: -- mWh");

        labelEficiencia = configLabel("Ganancia Móvil: -- %");
        labelEficiencia.setTextSize(14);
        labelEficiencia.setTextColor(COLOR_ACCENT);

        labelEstadoGPS = configLabel("GPS: Buscando...");
        
        textviewUptime = new TextView(actividad);
        textviewUptime.setGravity(Gravity.CENTER);
        textviewUptime.setTextColor(COLOR_TEXTO_SEC);
        textviewUptime.setTextSize(10); // Un poco más pequeño
        textviewUptime.setText("Uptime: -- | Inicio: --");
    }

    private SeekBar configSeekBar(int min, int max, int progress) {
        SeekBar sb = new SeekBar(actividad);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            sb.setMin(min);
            sb.setMax(max);
        } else {
            sb.setMax(max - min);
        }
        sb.setProgress(progress);
        
        sb.getProgressDrawable().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        return sb;
    }

    private TextView configLabel(String text) {
        TextView tv = new TextView(actividad);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXTO_SEC);
        tv.setTextSize(13);
        tv.setPadding(0, 5, 0, 0);
        return tv;
    }

    private LinearLayout crearGUI() {
        LinearLayout main = new LinearLayout(actividad);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(COLOR_FONDO);
        main.setPadding(20, 20, 20, 20);
        main.setWeightSum(10.0f);

        LinearLayout headerContenedor = new LinearLayout(actividad);
        headerContenedor.setGravity(Gravity.CENTER);
        headerContenedor.setBackground(crearFondoHeader());
        
        TextView header = new TextView(actividad);
        header.setText("SOLAR TRACKER V2.0");
        header.setTextSize(20); 
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(COLOR_ACCENT);
        header.setGravity(Gravity.CENTER);
        headerContenedor.addView(header);
        
        main.addView(headerContenedor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f));

        LinearLayout rowGauges = new LinearLayout(actividad);
        rowGauges.setOrientation(LinearLayout.HORIZONTAL);
        rowGauges.setWeightSum(2.0f);
        
        LinearLayout.LayoutParams paramsG1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        paramsG1.setMargins(0, 0, 0, 0);
        rowGauges.addView(crearContenedorInstrumentos("SOL REAL", gaugeSolAz, gaugeSolEl), paramsG1);
        
        View gapGauges = new View(actividad);
        rowGauges.addView(gapGauges, new LinearLayout.LayoutParams(15, ViewGroup.LayoutParams.MATCH_PARENT));
        
        LinearLayout.LayoutParams paramsG2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        paramsG2.setMargins(0, 0, 0, 0);
        rowGauges.addView(crearContenedorInstrumentos("SERVOS", gaugeServoAz, gaugeServoEl), paramsG2);
        
        main.addView(rowGauges, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.8f));

        View verticalGap = new View(actividad);
        main.addView(verticalGap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 15));

        LinearLayout rowControles = new LinearLayout(actividad);
        rowControles.setOrientation(LinearLayout.HORIZONTAL);
        rowControles.setWeightSum(10.0f);
        
        LinearLayout col1 = new LinearLayout(actividad);
        col1.setOrientation(LinearLayout.VERTICAL);
        col1.setPadding(0, 0, 0, 0); 
        
        LinearLayout cardGPS = new LinearLayout(actividad);
        cardGPS.setOrientation(LinearLayout.VERTICAL);
        cardGPS.setBackground(crearFondoCard());
        cardGPS.setPadding(20, 15, 20, 15);
        
        LinearLayout.LayoutParams pCardGPS = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 6.5f);
        pCardGPS.setMargins(0, 0, 0, 0); 
        cardGPS.setLayoutParams(pCardGPS);
        
        TextView titleGPS = new TextView(actividad);
        titleGPS.setText("UBICACIÓN Y TIEMPO");
        titleGPS.setTextColor(COLOR_ACCENT);
        titleGPS.setTextSize(12);
        titleGPS.setTypeface(null, android.graphics.Typeface.BOLD); 
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

        View gapV1 = new View(actividad);
        col1.addView(gapV1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 15));
        
        LinearLayout cardPerf = new LinearLayout(actividad);
        cardPerf.setOrientation(LinearLayout.VERTICAL);
        cardPerf.setBackground(crearFondoCard());
        cardPerf.setPadding(20, 15, 20, 15);
        cardPerf.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams pCardPerf = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.5f);
        pCardPerf.setMargins(0, 0, 0, 0); 
        cardPerf.setLayoutParams(pCardPerf);

        TextView titlePerf = new TextView(actividad);
        titlePerf.setText("RENDIMIENTO Y GPS");
        titlePerf.setTextColor(COLOR_ACCENT);
        titlePerf.setTextSize(12);
        titlePerf.setTypeface(null, android.graphics.Typeface.BOLD); 
        titlePerf.setGravity(Gravity.CENTER);
        
        cardPerf.addView(titlePerf);
        cardPerf.addView(labelEficiencia);
        cardPerf.addView(labelEstadoGPS); 
        
        col1.addView(cardPerf);
        
        rowControles.addView(col1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5.0f));

        View gapCentral = new View(actividad);
        rowControles.addView(gapCentral, new LinearLayout.LayoutParams(15, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col2 = new LinearLayout(actividad);
        col2.setOrientation(LinearLayout.VERTICAL);
        col2.setPadding(0, 0, 0, 0); 
        col2.setWeightSum(10.0f); 

        LinearLayout cardManual = new LinearLayout(actividad);
        cardManual.setOrientation(LinearLayout.VERTICAL);
        cardManual.setBackground(crearFondoCard());
        cardManual.setPadding(20, 15, 20, 5); 
        
        LinearLayout.LayoutParams pCardManual = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4.2f);
        pCardManual.setMargins(0, 0, 0, 0); 
        cardManual.setLayoutParams(pCardManual);
        
        TextView titleMan = new TextView(actividad);
        titleMan.setText("MODO INDEPENDIENTE");
        titleMan.setTextColor(COLOR_ACCENT);
        titleMan.setTextSize(12);
        titleMan.setTypeface(null, android.graphics.Typeface.BOLD); 
        titleMan.setGravity(Gravity.CENTER);
        
        cardManual.addView(titleMan);
        cardManual.addView(labelManualAz);
        cardManual.addView(sliderManualAz);
        cardManual.addView(labelManualEl);
        cardManual.addView(sliderManualEl);
        
        LinearLayout.LayoutParams pBtnReset = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pBtnReset.setMargins(0, 0, 0, 0); 
        cardManual.addView(botonResetGPS, pBtnReset);

        col2.addView(cardManual, pCardManual);

        View gap2 = new View(actividad);
        col2.addView(gap2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 15));

        LinearLayout panelPot = new LinearLayout(actividad);
        panelPot.setOrientation(LinearLayout.VERTICAL);
        panelPot.setPadding(20, 15, 20, 15);
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
        
        View space = new View(actividad);
        panelPot.addView(space, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10));
        
        panelPot.addView(p2Title);
        panelPot.addView(p2Inst);
        panelPot.addView(p2Avg);
        panelPot.addView(p2Daily);

        col2.addView(panelPot, pPanelPot);
        
        rowControles.addView(col2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5.0f));
        
        main.addView(rowControles, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4.7f));

        LinearLayout footer = new LinearLayout(actividad);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(10, 5, 10, 0);
        footer.setBackgroundColor(Color.rgb(25, 25, 25));
        
        LinearLayout statusCol = new LinearLayout(actividad);
        statusCol.setOrientation(LinearLayout.VERTICAL);
        statusCol.addView(textviewFechaHora);
        statusCol.addView(textviewUptime); // Nueva fila de estabilidad
        statusCol.addView(textviewAviso);
        
        footer.addView(statusCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        footer.addView(botonBatch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f));
        footer.addView(botonCompartir, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f));
        footer.addView(botonConectar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f));
        
        main.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        return main;
    }

    private LinearLayout crearItemInstrumento(String label, View gauge) {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackground(crearFondoCard());
        l.setPadding(5, 5, 5, 5);
        
        TextView tv = new TextView(actividad);
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
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(10, 10, 10, 10);
        l.setGravity(Gravity.CENTER);
        l.setBackground(crearFondoCard());
        
        TextView tv = new TextView(actividad);
        tv.setText(titulo);
        tv.setTextColor(COLOR_TEXTO_SEC);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        l.addView(tv);
        
        LinearLayout col = new LinearLayout(actividad);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(g1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        col.addView(g2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        l.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        
        return l;
    }

    private android.graphics.drawable.GradientDrawable crearFondoHeader() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.rgb(30, 35, 45));
        gd.setCornerRadius(0);
        gd.setStroke(2, COLOR_ACCENT);
        return gd;
    }

    private android.graphics.drawable.GradientDrawable crearFondoCard() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(20);
        gd.setStroke(2, Color.rgb(50, 55, 65));
        return gd;
    }
}

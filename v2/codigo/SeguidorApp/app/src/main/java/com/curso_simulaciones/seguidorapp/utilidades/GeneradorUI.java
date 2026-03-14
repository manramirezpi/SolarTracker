package com.curso_simulaciones.seguidorapp.utilidades;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.curso_simulaciones.seguidorapp.datos.AlmacenDatosRAM;

/**
 * Clase encargada de empaquetar, instancias y manipular la interfaz gráfica (UI).
 * Representa la Capa "Vista" en el patrón MVC/MVP.
 *
 * v2.1: Diseño Industrial, Tema Claro, Tablas Consolidadas y Dashboard de Salud.
 */
public class GeneradorUI {

    private Activity actividad;

    public Button botonConectar, botonResetGPS, botonTemp, botonShare;
    public TextView textviewAviso, textviewFechaHora;

    public TextView labelLat, labelLon, labelManualAz, labelManualEl;
    public TextView solAz, solEl, servoAz, servoEl, errAz, errEl;
    public TextView p1Inst, p1Avg, p1Daily, p2Inst, p2Avg, p2Daily, labelGanancia;
    public Button btnAuto, btnMan;
    public TextView iconHealthMqtt, iconHealthGps, iconHealthIna, iconHealthLog;

    // --- PALETA DE COLORES v2.1 (TEMA CLARO INDUSTRIAL) ---
    public final int COLOR_FONDO = Color.parseColor("#F2F2F7"); // Gris claro iOS style
    public final int COLOR_CARD = Color.WHITE;
    public final int COLOR_ACCENT = Color.parseColor("#007AFF"); // Azul sistema
    public final int COLOR_TEXTO_PRI = Color.parseColor("#1C1C1E"); // Casi negro
    public final int COLOR_TEXTO_SEC = Color.parseColor("#8E8E93"); // Gris medio
    public final int COLOR_ERROR = Color.parseColor("#FF3B30");
    public final int COLOR_OK = Color.parseColor("#34C759");
    public final int COLOR_WARN = Color.parseColor("#FFCC00");
    public final int COLOR_BORDE = Color.parseColor("#E5E5EA");

    public SeekBar sliderLat, sliderLon, sliderManualAz, sliderManualEl;

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
        // --- Datos de Tracking ---
        solAz = configDato("--"); solEl = configDato("--");
        servoAz = configDato("--"); servoEl = configDato("--");
        errAz = configDato("--"); errEl = configDato("--");

        // --- Datos de Potencia ---
        p1Inst = configDato("--"); p1Avg = configDato("--"); p1Daily = configDato("--");
        p2Inst = configDato("--"); p2Avg = configDato("--"); p2Daily = configDato("--");
        labelGanancia = configDato("--");
        labelGanancia.setTextColor(COLOR_ACCENT);

        // --- Controles de Ubicación ---
        sliderLat = configSeekBar(0, 18000, 9000);
        sliderLon = configSeekBar(0, 36000, 18000);
        labelLat = configLabel("Latitud: 0.00°");
        labelLon = configLabel("Longitud: 0.00°");

        // --- Controles Manuales ---
        sliderManualAz = configSeekBar(0, 180, 90); 
        sliderManualEl = configSeekBar(0, 180, 90);
        labelManualAz = configLabel("Azimut: 0°"); 
        labelManualEl = configLabel("Elevación: 90°");

        // --- Selectores de Modo ---
        btnAuto = configBotonTab("AUTO", true);
        btnMan = configBotonTab("MANUAL", false);

        // --- Botones de Acción ---
        botonConectar = new Button(actividad);
        botonConectar.setText("CONECTAR");
        botonConectar.setTextColor(Color.WHITE);
        botonConectar.setAllCaps(false);
        botonConectar.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        botonConectar.getBackground().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.MULTIPLY);

        botonResetGPS = new Button(actividad);
        botonResetGPS.setText("Sincronizar GPS");
        botonResetGPS.setAllCaps(false);
        botonResetGPS.setTextSize(12);
        botonResetGPS.setTextColor(COLOR_ACCENT);

        botonTemp = new Button(actividad);
        botonTemp.setText("Descargar Datos");
        botonTemp.setAllCaps(false);
        botonTemp.setTextSize(12);

        botonShare = new Button(actividad);
        botonShare.setText("Compartir");
        botonShare.setAllCaps(false);
        botonShare.setTextSize(12);

        textviewAviso = new TextView(actividad);
        textviewAviso.setTextColor(COLOR_TEXTO_SEC);
        textviewAviso.setTextSize(11);

        textviewFechaHora = new TextView(actividad);
        textviewFechaHora.setTextColor(COLOR_TEXTO_SEC);
        textviewFechaHora.setTextSize(11);

        // Iconos de Salud
        iconHealthMqtt = configHealthIcon("MQTT");
        iconHealthGps = configHealthIcon("GPS");
        iconHealthIna = configHealthIcon("INA");
        iconHealthLog = configHealthIcon("DISK");
    }

    private TextView configHealthIcon(String name) {
        TextView tv = new TextView(actividad);
        tv.setText(name);
        tv.setTextSize(9);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        tv.setGravity(Gravity.CENTER);
        actualizarEstadoIcono(tv, 0); 
        return tv;
    }

    public void actualizarEstadoIcono(TextView tv, int state) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(6));
        int color = (state == 2) ? COLOR_OK : (state == 1 ? COLOR_WARN : COLOR_ERROR);
        gd.setColor(color);
        tv.setBackground(gd);
    }

    private TextView configDato(String t) {
        TextView tv = new TextView(actividad);
        tv.setText(t);
        tv.setTextColor(COLOR_TEXTO_PRI);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button configBotonTab(String text, boolean active) {
        Button b = new Button(actividad);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        b.setTextColor(active ? Color.WHITE : COLOR_TEXTO_SEC);
        b.getBackground().setColorFilter(active ? COLOR_ACCENT : Color.LTGRAY, PorterDuff.Mode.MULTIPLY);
        return b;
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
        tv.setPadding(0, dp(5), 0, 0);
        return tv;
    }

    private LinearLayout crearGUI() {
        LinearLayout main = new LinearLayout(actividad);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(COLOR_FONDO);
        main.setPadding(dp(20), dp(15), dp(20), dp(15));

        // --- HEADER CON HEALTH MONITOR ---
        LinearLayout header = new LinearLayout(actividad);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView title = new TextView(actividad);
        title.setText("SolarTracker v2.1");
        title.setTextSize(20);
        title.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        title.setTextColor(COLOR_TEXTO_PRI);
        
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        
        LinearLayout healthBox = new LinearLayout(actividad);
        healthBox.addView(iconHealthMqtt);
        healthBox.addView(gapH(6));
        healthBox.addView(iconHealthGps);
        healthBox.addView(gapH(6));
        healthBox.addView(iconHealthIna);
        healthBox.addView(gapH(6));
        healthBox.addView(iconHealthLog);
        header.addView(healthBox);

        main.addView(header);
        main.addView(gapV(20));

        // --- SECCIÓN 1: POSICIÓN (TRACKING) ---
        main.addView(crearCard(crearTablaTracking()));
        main.addView(gapV(15));

        // --- SECCIÓN 2: BALANCE ENERGÉTICO ---
        main.addView(crearCard(crearTablaPotencia()));
        main.addView(gapV(15));

        // --- SECCIÓN 3: PANELES DE CONTROL (UBICACIÓN Y MANUAL) ---
        LinearLayout rowCtrl = new LinearLayout(actividad);
        rowCtrl.addView(crearCard(crearPanelUbicacion()), new LinearLayout.LayoutParams(0, -2, 1));
        rowCtrl.addView(gapH(12));
        rowCtrl.addView(crearCard(crearPanelManual()), new LinearLayout.LayoutParams(0, -2, 1));
        
        main.addView(rowCtrl);
        main.addView(gapV(20));

        // --- FOOTER: FECHA, ESTADO Y ACCIONES ---
        LinearLayout footer = new LinearLayout(actividad);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout footerInfo = new LinearLayout(actividad);
        footerInfo.setOrientation(LinearLayout.VERTICAL);
        footerInfo.addView(textviewFechaHora);
        footerInfo.addView(textviewAviso);
        
        footer.addView(footerInfo, new LinearLayout.LayoutParams(0, -2, 1));
        
        footer.addView(botonShare);
        footer.addView(botonTemp);
        footer.addView(botonConectar);
        
        main.addView(footer);

        return main;
    }

    private LinearLayout crearCard(View view) {
        LinearLayout card = new LinearLayout(actividad);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(crearFondoCard());
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(view);
        return card;
    }

    private View gapV(int d) { 
        View v = new View(actividad); 
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(d)));
        return v; 
    }
    
    private View gapH(int d) { 
        View v = new View(actividad); 
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(d), -1));
        return v; 
    }

    private int dp(int px) {
        float density = actividad.getResources().getDisplayMetrics().density;
        return (int) (px * density);
    }

    private LinearLayout crearTablaTracking() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(crearFila("POSICIÓN", "Solar", "Servo", "Error", true));
        l.addView(gapV(8));
        l.addView(crearFila("Azimut", solAz, servoAz, errAz));
        l.addView(crearFila("Elevación", solEl, servoEl, errEl));
        return l;
    }

    private LinearLayout crearTablaPotencia() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(crearFila("POTENCIA", "Tracker", "Estático", "", true));
        l.addView(gapV(8));
        l.addView(crearFila("Instantánea", p1Inst, p2Inst, null));
        l.addView(crearFila("Promedio", p1Avg, p2Avg, null));
        l.addView(crearFila("Diaria (mWh)", p1Daily, p2Daily, null));
        
        LinearLayout gainBox = new LinearLayout(actividad);
        gainBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView t = new TextView(actividad); 
        t.setText("Ganancia Neta: "); 
        t.setTextColor(COLOR_TEXTO_SEC);
        t.setTextSize(14);
        gainBox.addView(t);
        labelGanancia.setTextSize(16);
        gainBox.addView(labelGanancia);
        l.addView(gapV(8));
        l.addView(gainBox);
        return l;
    }

    private LinearLayout crearFila(String label, Object c1, Object c2, Object c3, boolean title) {
        LinearLayout r = new LinearLayout(actividad);
        r.setWeightSum(10);
        
        TextView tLabel = new TextView(actividad);
        tLabel.setText(label);
        tLabel.setTextColor(title ? COLOR_ACCENT : COLOR_TEXTO_SEC);
        tLabel.setTextSize(title ? 12 : 14);
        if (title) tLabel.setTypeface(null, Typeface.BOLD);
        r.addView(tLabel, new LinearLayout.LayoutParams(0, -2, 4));

        Object[] cols = {c1, c2, c3};
        for (Object col : cols) {
            if (col == null) {
                View space = new View(actividad);
                r.addView(space, new LinearLayout.LayoutParams(0, 1, 2));
                continue;
            }
            if (col instanceof String) {
                TextView tv = new TextView(actividad);
                tv.setText((String)col);
                tv.setTextColor(COLOR_TEXTO_SEC);
                tv.setTextSize(11);
                tv.setGravity(Gravity.CENTER);
                r.addView(tv, new LinearLayout.LayoutParams(0, -2, 2));
            } else {
                r.addView((View)col, new LinearLayout.LayoutParams(0, -2, 2));
            }
        }
        return r;
    }

    private LinearLayout crearPanelUbicacion() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(actividad);
        t.setText("UBICACIÓN");
        t.setTextColor(COLOR_ACCENT);
        t.setTextSize(11);
        t.setTypeface(null, Typeface.BOLD);
        l.addView(t);
        l.addView(gapV(8));
        l.addView(labelLat);
        l.addView(sliderLat);
        l.addView(labelLon);
        l.addView(sliderLon);
        return l;
    }

    private LinearLayout crearPanelManual() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        
        LinearLayout toggle = new LinearLayout(actividad);
        toggle.addView(btnAuto, new LinearLayout.LayoutParams(0, dp(34), 1));
        toggle.addView(btnMan, new LinearLayout.LayoutParams(0, dp(34), 1));
        l.addView(toggle);
        
        l.addView(gapV(10));
        l.addView(labelManualAz);
        l.addView(sliderManualAz);
        l.addView(labelManualEl);
        l.addView(sliderManualEl);
        l.addView(botonResetGPS);
        return l;
    }

    private GradientDrawable crearFondoCard() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), COLOR_BORDE);
        return gd;
    }
}

package com.curso_simulaciones.seguidorapp.utilidades;

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

import com.curso_simulaciones.seguidorapp.datos.AlmacenDatosRAM;

/**
 * Clase encargada de empaquetar, instancias y manipular la interfaz gráfica (UI).
 * Representa la Capa "Vista" en el patrón MVC/MVP.
 *
 * Expone variables públicas de sus componentes (TextView, SeekBar, Gauge) para 
 * que el controlador pueda suscribirse a los eventos y enviar actualizaciones de datos.
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

    public final int COLOR_FONDO = Color.parseColor("#F2F2F7");
    public final int COLOR_CARD = Color.WHITE;
    public final int COLOR_ACCENT = Color.parseColor("#007AFF");
    public final int COLOR_TEXTO_PRI = Color.parseColor("#1C1C1E");
    public final int COLOR_TEXTO_SEC = Color.parseColor("#8E8E93");
    public final int COLOR_ERROR = Color.parseColor("#FF3B30");
    public final int COLOR_OK = Color.parseColor("#34C759");
    public final int COLOR_WARN = Color.parseColor("#FFCC00");

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
        // --- Tabla de Tracking ---
        solAz = configDato("--"); solEl = configDato("--");
        servoAz = configDato("--"); servoEl = configDato("--");
        errAz = configDato("--"); errEl = configDato("--");

        // --- Tabla de Potencia ---
        p1Inst = configDato("--"); p1Avg = configDato("--"); p1Daily = configDato("--");
        p2Inst = configDato("--"); p2Avg = configDato("--"); p2Daily = configDato("--");
        labelGanancia = configDato("--");
        labelGanancia.setTextColor(COLOR_ACCENT);

        sliderLat = configSeekBar(0, 18000, 9000);
        sliderLon = configSeekBar(0, 36000, 18000);
        labelLat = configLabel("Latitud: 0.00°");
        labelLon = configLabel("Longitud: 0.00°");

        sliderManualAz = configSeekBar(0, 180, 90); 
        sliderManualEl = configSeekBar(0, 180, 90);
        labelManualAz = configLabel("Azimut: 0°"); 
        labelManualEl = configLabel("Elevación: 90°");

        sliderTiempo = new CircularSlider(actividad);
        sliderTiempo.setRange(1.0f, 1440.0f);
        sliderTiempo.setValue(1.0f);
        sliderTiempo.setLabel("Simulación");

        btnAuto = configBotonTab("AUTO", true);
        btnMan = configBotonTab("MANUAL", false);

        botonConectar = new Button(actividad);
        botonConectar.setText("CONECTAR");
        botonConectar.setTextColor(Color.WHITE);
        botonConectar.getBackground().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.MULTIPLY);

        botonResetGPS = new Button(actividad);
        botonResetGPS.setText("SYNC");
        botonResetGPS.setTextColor(COLOR_ERROR);

        botonTemp = new Button(actividad);
        botonTemp.setText("DATA LOG");

        botonShare = new Button(actividad);
        botonShare.setText("EMAIL");

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
        iconHealthLog = configHealthIcon("LOG");
    }

    private TextView configHealthIcon(String name) {
        TextView tv = new TextView(actividad);
        tv.setText(name);
        tv.setTextSize(8);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        tv.setGravity(Gravity.CENTER);
        actualizarEstadoIcono(tv, 0); // Por defecto: Desconectado
        return tv;
    }

    public void actualizarEstadoIcono(TextView tv, int state) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(4));
        int color = (state == 2) ? COLOR_OK : (state == 1 ? COLOR_WARN : COLOR_ERROR);
        gd.setColor(color);
        tv.setBackground(gd);
    }

    private TextView configDato(String t) {
        TextView tv = new TextView(actividad);
        tv.setText(t);
        tv.setTextColor(COLOR_TEXTO_PRI);
        tv.setTextSize(15);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button configBotonTab(String text, boolean active) {
        Button b = new Button(actividad);
        b.setText(text);
        b.setTextSize(11);
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
        tv.setPadding(0, 5, 0, 0);
        return tv;    private LinearLayout crearGUI() {
        LinearLayout main = new LinearLayout(actividad);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(COLOR_FONDO);
        main.setPadding(dp(15), dp(10), dp(15), dp(10));

        // --- HEADER CON HEALTH MONITOR ---
        LinearLayout header = new LinearLayout(actividad);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView title = new TextView(actividad);
        title.setText("SolarTracker v2.1");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD);
        title.setTextColor(COLOR_TEXTO_PRI);
        
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        
        LinearLayout healthBox = new LinearLayout(actividad);
        healthBox.addView(iconHealthMqtt);
        healthBox.addView(gapH(4));
        healthBox.addView(iconHealthGps);
        healthBox.addView(gapH(4));
        healthBox.addView(iconHealthIna);
        healthBox.addView(gapH(4));
        healthBox.addView(iconHealthLog);
        header.addView(healthBox);

        main.addView(header);
        main.addView(gapV(15));

        // --- SECCIÓN 1: TRACKING ---
        main.addView(crearCard(crearTablaTracking()));
        main.addView(gapV(12));

        // --- SECCIÓN 2: ENERGÍA ---
        main.addView(crearCard(crearTablaPotencia()));
        main.addView(gapV(12));

        // --- SECCIÓN 3: CONTROLES ---
        LinearLayout rowCtrl = new LinearLayout(actividad);
        rowCtrl.addView(crearCard(crearPanelUbicacion()), new LinearLayout.LayoutParams(0, -1, 1));
        rowCtrl.addView(gapH(10));
        rowCtrl.addView(crearCard(crearPanelManual()), new LinearLayout.LayoutParams(0, -1, 1));
        
        main.addView(rowCtrl, new LinearLayout.LayoutParams(-1, 0, 1));
        main.addView(gapV(12));

        // --- FOOTER ---
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
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
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
        l.addView(crearFila("SEGUIMIENTO", "Sol", "Servo", "Error", true));
        l.addView(gapV(5));
        l.addView(crearFila("Azimut", solAz, servoAz, errAz));
        l.addView(crearFila("Elevación", solEl, servoEl, errEl));
        return l;
    }

    private LinearLayout crearTablaPotencia() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(crearFila("POTENCIA", "Seguidor", "Estático", "", true));
        l.addView(gapV(5));
        l.addView(crearFila("Inst.(mW)", p1Inst, p2Inst, null));
        l.addView(crearFila("Med.(mW)", p1Avg, p2Avg, null));
        l.addView(crearFila("E.(mWh)", p1Daily, p2Daily, null));
        
        LinearLayout gainBox = new LinearLayout(actividad);
        gainBox.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView t = new TextView(actividad); t.setText("Ganancia: "); t.setTextColor(COLOR_TEXTO_SEC);
        gainBox.addView(t);
        gainBox.addView(labelGanancia);
        l.addView(gainBox);
        return l;
    }

    private LinearLayout crearFila(String label, Object c1, Object c2, Object c3, boolean title) {
        LinearLayout r = new LinearLayout(actividad);
        r.setWeightSum(10);
        
        TextView tLabel = new TextView(actividad);
        tLabel.setText(label);
        tLabel.setTextColor(title ? COLOR_ACCENT : COLOR_TEXTO_SEC);
        tLabel.setTextSize(title ? 11 : 13);
        if (title) tLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        r.addView(tLabel, new LinearLayout.LayoutParams(0, -2, 4));

        Object[] cols = {c1, c2, c3};
        for (Object col : cols) {
            if (col == null) {
                r.addView(new View(actividad), new LinearLayout.LayoutParams(0, 1, 2));
                continue;
            }
            if (col instanceof String) {
                TextView tv = new TextView(actividad);
                tv.setText((String)col);
                tv.setTextColor(COLOR_TEXTO_SEC);
                tv.setTextSize(10);
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
        l.addView(labelLat);
        l.addView(sliderLat);
        l.addView(labelLon);
        l.addView(sliderLon);
        l.addView(gapV(10));
        l.addView(sliderTiempo);
        return l;
    }

    private LinearLayout crearPanelManual() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        
        LinearLayout toggle = new LinearLayout(actividad);
        toggle.addView(btnAuto, new LinearLayout.LayoutParams(0, dp(32), 1));
        toggle.addView(btnMan, new LinearLayout.LayoutParams(0, dp(32), 1));
        l.addView(toggle);
        
        l.addView(gapV(8));
        l.addView(labelManualAz);
        l.addView(sliderManualAz);
        l.addView(labelManualEl);
        l.addView(sliderManualEl);
        l.addView(botonResetGPS);
        return l;
    }

    private android.graphics.drawable.GradientDrawable crearFondoCard() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(dp(12));
        gd.setStroke(1, Color.parseColor("#E5E5EA"));
        return gd;
    }
}
in;
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

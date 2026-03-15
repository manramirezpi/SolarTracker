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
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.FrameLayout;

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
    public TextView p1Inst, p1Avg, p1Daily, p2Inst, p2Avg, p2Daily, p1Gain, p2Gain;
    public Button btnAuto, btnMan;
    public View healthIndicator;
    public TextView healthStatusText;
    public ProgressBar progressDownload, progressConectando;
    public TextView labelDownloadCount;
    
    // Labels de feedback para comandos
    public TextView feedLat, feedLon, feedAz, feedEl;

    // --- PALETA DE COLORES v2.5 (INDUSTRIAL MONOTONE) ---
    public final int COLOR_FONDO = Color.WHITE;
    public final int COLOR_CONTROL_BG = Color.parseColor("#F5F5F5");
    public final int COLOR_CONTROL_ACCENT = Color.parseColor("#1565C0"); // Azul industrial
    public final int COLOR_TEXTO_PRI = Color.parseColor("#212121");
    public final int COLOR_TEXTO_SEC = Color.parseColor("#757575");
    
    public final int COLOR_HEALTH_OK = Color.parseColor("#2E7D32");    // Verde
    public final int COLOR_HEALTH_WARN = Color.parseColor("#F9A825");  // Amarillo
    public final int COLOR_HEALTH_CRIT = Color.parseColor("#C62828");  // Rojo
    public final int COLOR_HEALTH_DISC = Color.parseColor("#757575");  // Gris
    
    public final int COLOR_BORDE = Color.parseColor("#E0E0E0");
    
    public final int COLOR_GAIN_POS_TXT = Color.parseColor("#2E7D32");
    public final int COLOR_GAIN_POS_BG = Color.parseColor("#E8F5E9");
    public final int COLOR_GAIN_NEG_TXT = Color.parseColor("#C62828");
    public final int COLOR_GAIN_NEG_BG = Color.parseColor("#FFEBEE");

    public final int COLOR_TOPBAR_BG = Color.parseColor("#EEEEEE");
    public final int COLOR_TOPBAR_TXT = Color.parseColor("#1565C0");

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
        p1Gain = configDato("--"); p2Gain = configDato("--");

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

        // Icono de Salud Global
        healthIndicator = new View(actividad);
        healthIndicator.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        
        healthStatusText = new TextView(actividad);
        healthStatusText.setText("SIN SEÑAL");
        healthStatusText.setTextSize(14);
        healthStatusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        healthStatusText.setPadding(dp(8), 0, dp(16), 0);

        // Barra de descarga (ahora circular indeterminada)
        progressDownload = new ProgressBar(actividad, null, android.R.attr.progressBarStyleSmall);
        progressDownload.setIndeterminate(true);
        progressDownload.setVisibility(View.GONE);
        labelDownloadCount = new TextView(actividad);
        labelDownloadCount.setTextSize(12);
        labelDownloadCount.setTextColor(COLOR_TEXTO_SEC);
        labelDownloadCount.setVisibility(View.GONE);
        
        progressConectando = new ProgressBar(actividad, null, android.R.attr.progressBarStyleSmall);
        progressConectando.setVisibility(View.GONE);
        
        // Feedback de comandos
        feedLat = configFeedback(); feedLon = configFeedback();
        feedAz = configFeedback(); feedEl = configFeedback();
    }

    private TextView configFeedback() {
        TextView tv = new TextView(actividad);
        tv.setTextSize(10);
        tv.setTypeface(null, Typeface.ITALIC);
        tv.setPadding(dp(10), 0, 0, 0);
        return tv;
    }

    public void actualizarEstadoGlobal(int state, String text) {
        int color = COLOR_HEALTH_DISC; // Default Gris
        if (state == 0) color = COLOR_HEALTH_OK;
        else if (state == 1) color = COLOR_HEALTH_WARN;
        else if (state == 2) color = COLOR_HEALTH_CRIT;

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        healthIndicator.setBackground(gd);
        healthStatusText.setText(text);
        healthStatusText.setTextColor(color);
        
        // Actualizar barra superior si está conectado
        if (AlmacenDatosRAM.conectado) {
            View parent = (View) healthIndicator.getParent().getParent();
            if (parent != null) {
                parent.setBackgroundColor(Color.parseColor("#F5F5F5")); // Fondo base de topBar
                // Opcionalmente, el requerimiento dice: "barra superior con color según estado de salud global"
                // Pero esto podría ser muy molesto si toda la barra se vuelve roja. 
                // Revisando: "CONECTADO: botón cambia a 'Desconectar', barra superior con color según estado de salud global"
                // Probablemente se refiere a un color teñido sutilmente o solo el indicador.
                // Usaré un tinte suave en el fondo de la barra si es FAIL.
                if (state == 2) parent.setBackgroundColor(Color.parseColor("#FFEBEE"));
                else if (state == 1) parent.setBackgroundColor(Color.parseColor("#FFF9C4"));
                else parent.setBackgroundColor(COLOR_TOPBAR_BG);
            }
        } else {
             View parent = (View) healthIndicator.getParent().getParent();
             if (parent != null) parent.setBackgroundColor(COLOR_TOPBAR_BG);
        }
    }


    private TextView configDato(String t) {
        TextView tv = new TextView(actividad);
        tv.setText(t);
        tv.setTextColor(COLOR_TEXTO_PRI);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button configBotonTab(String text, boolean active) {
        Button b = new Button(actividad);
        b.setText(text);
        b.setAllCaps(true);
        b.setTextSize(11);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(active ? Color.WHITE : COLOR_TEXTO_SEC);
        b.getBackground().setColorFilter(active ? COLOR_CONTROL_ACCENT : Color.LTGRAY, PorterDuff.Mode.MULTIPLY);
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
        sb.setPadding(0, dp(10), 0, dp(10));
        sb.getProgressDrawable().setColorFilter(COLOR_CONTROL_ACCENT, PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(COLOR_CONTROL_ACCENT, PorterDuff.Mode.SRC_IN);
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

        // --- TOP BAR (HEADER) ---
        LinearLayout topBar = new LinearLayout(actividad);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(COLOR_TOPBAR_BG);
        topBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        
        TextView title = new TextView(actividad);
        title.setText("SOLAR TRACKER");
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(COLOR_TOPBAR_TXT);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        
        LinearLayout healthBox = new LinearLayout(actividad);
        healthBox.setGravity(Gravity.CENTER_VERTICAL);
        healthBox.addView(healthIndicator);
        healthBox.addView(healthStatusText);
        topBar.addView(healthBox);
        
        // ProgressBar de descarga (indeterminado)
        topBar.addView(progressDownload, new LinearLayout.LayoutParams(dp(40), dp(24)));
        topBar.addView(labelDownloadCount);
        
        main.addView(topBar);
        
        // --- CONTENT AREA ---
        LinearLayout content = new LinearLayout(actividad);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        // --- ZONA DE MONITOREO (FONDO BLANCO) ---
        content.addView(crearTablaTracking());
        content.addView(gapV(24));
        content.addView(crearTablaPotencia());
        content.addView(gapV(24));

        // --- ZONA DE CONTROL (FONDO GRIS + BORDE AZUL) ---
        content.addView(crearZonaControl());
        
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- FOOTER ---
        LinearLayout footer = new LinearLayout(actividad);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        
        LinearLayout footerInfo = new LinearLayout(actividad);
        footerInfo.setOrientation(LinearLayout.VERTICAL);
        footerInfo.addView(textviewFechaHora);
        footerInfo.addView(textviewAviso);
        footer.addView(footerInfo, new LinearLayout.LayoutParams(0, -2, 1));
        
        footer.addView(botonShare);
        footer.addView(gapH(8));
        footer.addView(botonTemp);
        footer.addView(gapH(8));
        
        FrameLayout btnLayout = new FrameLayout(actividad);
        btnLayout.addView(botonConectar);
        FrameLayout.LayoutParams lpProg = new FrameLayout.LayoutParams(dp(24), dp(24));
        lpProg.gravity = Gravity.CENTER;
        btnLayout.addView(progressConectando, lpProg);
        
        footer.addView(btnLayout);
        
        main.addView(footer);
        return main;
    }

    private LinearLayout crearZonaControl() {
        LinearLayout container = new LinearLayout(actividad);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(COLOR_CONTROL_BG);
        
        // Borde izquierdo azul
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CONTROL_BG);
        container.setBackground(gd);
        
        // En Android para un borde lateral se suele usar un View o un layer-list. 
        // Aquí lo haremos con un FrameLayout envolvente.
        FrameLayout edge = new FrameLayout(actividad);
        View line = new View(actividad);
        line.setBackgroundColor(COLOR_CONTROL_ACCENT);
        
        LinearLayout content = new LinearLayout(actividad);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        content.addView(crearPanelUbicacion());
        content.addView(gapV(16));
        content.addView(crearPanelManual());
        
        edge.addView(content);
        edge.addView(line, new FrameLayout.LayoutParams(dp(3), FrameLayout.LayoutParams.MATCH_PARENT));
        
        return edge;
    }

    // crearCard eliminado para monitoreo (fondo blanco plano)


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
        l.addView(crearFila("SISTEMA DE SEGUIMIENTO", "Solar", "Servo", "Error", true));
        l.addView(gapV(8));
        l.addView(crearFila("Azimut", solAz, servoAz, errAz));
        l.addView(crearFila("Elevación", solEl, servoEl, errEl));
        return l;
    }

    private LinearLayout crearTablaPotencia() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(crearFila("RENDIMIENTO METROLÓGICO", "Inst", "Prom", "Acum", "Gan", true));
        l.addView(gapV(8));
        l.addView(crearFila("Panel Seguidor", p1Inst, p1Avg, p1Daily, p1Gain));
        l.addView(crearFila("Panel Estático", p2Inst, p2Avg, p2Daily, p2Gain));
        return l;
    }

    private LinearLayout crearFila(String label, Object c1, Object c2, Object c3, Object c4) {
        LinearLayout r = new LinearLayout(actividad);
        r.setWeightSum(12);
        
        TextView tLabel = new TextView(actividad);
        tLabel.setText(label);
        tLabel.setTextColor(COLOR_TEXTO_SEC);
        tLabel.setTextSize(13);
        r.addView(tLabel, new LinearLayout.LayoutParams(0, -2, 4));

        Object[] cols = {c1, c2, c3, c4};
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
    
    // Método auxiliar para el título de la tabla
    private LinearLayout crearFila(String label, String c1, String c2, String c3, String c4, boolean title) {
        LinearLayout r = new LinearLayout(actividad);
        r.setWeightSum(12);
        
        TextView tLabel = new TextView(actividad);
        tLabel.setText(label);
        tLabel.setTextColor(COLOR_CONTROL_ACCENT);
        tLabel.setTextSize(11);
        tLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        r.addView(tLabel, new LinearLayout.LayoutParams(0, -2, 4));

        String[] cols = {c1, c2, c3, c4};
        for (String col : cols) {
            TextView tv = new TextView(actividad);
            tv.setText(col);
            tv.setTextColor(COLOR_TEXTO_SEC);
            tv.setTextSize(10);
            tv.setGravity(Gravity.CENTER);
            r.addView(tv, new LinearLayout.LayoutParams(0, -2, 2));
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
        l.addView(crearLabelConFeedback(labelLat, feedLat));
        l.addView(sliderLat);
        l.addView(crearLabelConFeedback(labelLon, feedLon));
        l.addView(sliderLon);
        return l;
    }
    
    private LinearLayout crearLabelConFeedback(TextView label, TextView feed) {
        LinearLayout r = new LinearLayout(actividad);
        r.addView(label);
        r.addView(feed);
        return r;
    }

    private LinearLayout crearPanelManual() {
        LinearLayout l = new LinearLayout(actividad);
        l.setOrientation(LinearLayout.VERTICAL);
        
        LinearLayout toggle = new LinearLayout(actividad);
        toggle.addView(btnAuto, new LinearLayout.LayoutParams(0, dp(34), 1));
        toggle.addView(btnMan, new LinearLayout.LayoutParams(0, dp(34), 1));
        l.addView(toggle);
        
        l.addView(gapV(10));
        l.addView(crearLabelConFeedback(labelManualAz, feedAz));
        l.addView(sliderManualAz);
        l.addView(crearLabelConFeedback(labelManualEl, feedEl));
        l.addView(sliderManualEl);
        
        LinearLayout actionRow = new LinearLayout(actividad);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.addView(botonResetGPS);
        l.addView(actionRow);
        
        return l;
    }
    
    public void actualizarGananciaColor(float ganancia) {
        if (ganancia > 0) {
            p1Gain.setText(String.format("+%.1f%%", ganancia));
            p1Gain.setTextColor(COLOR_GAIN_POS_TXT);
            p1Gain.setBackgroundColor(COLOR_GAIN_POS_BG);
            p2Gain.setText("---");
            p2Gain.setTextColor(COLOR_TEXTO_SEC);
            p2Gain.setBackgroundColor(Color.TRANSPARENT);
        } else if (ganancia < 0) {
            p1Gain.setText(String.format("%.1f%%", ganancia));
            p1Gain.setTextColor(COLOR_GAIN_NEG_TXT);
            p1Gain.setBackgroundColor(COLOR_GAIN_NEG_BG);
            p2Gain.setText("---");
            p2Gain.setTextColor(COLOR_TEXTO_SEC);
            p2Gain.setBackgroundColor(Color.TRANSPARENT);
        } else {
            p1Gain.setText("---");
            p1Gain.setTextColor(COLOR_TEXTO_SEC);
            p1Gain.setBackgroundColor(Color.TRANSPARENT);
            p2Gain.setText("---");
            p2Gain.setTextColor(COLOR_TEXTO_SEC);
            p2Gain.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private GradientDrawable crearFondoCard() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COLOR_CARD);
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), COLOR_BORDE);
        return gd;
    }
}

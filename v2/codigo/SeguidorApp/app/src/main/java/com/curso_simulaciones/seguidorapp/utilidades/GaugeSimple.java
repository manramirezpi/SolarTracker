package com.curso_simulaciones.seguidorapp.utilidades;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public class GaugeSimple extends View {

    private float largo;
    private float minimo = 0;
    private float maximo = 100f;
    private float medida = 0.0f;
    private String unidades = "UNIDADES";

    private int colorFondoTacometro = Color.rgb(40, 45, 55);
    private int colorBordeTacometro = Color.rgb(100, 105, 115);
    private int colorLineas = Color.rgb(200, 200, 200);
    private int colorNumeros = Color.WHITE;
    private int colorNumerosDespliegue = Color.rgb(0, 230, 118); // Accent color for the digital value

    private int numeroDivisiones = 10;

    public GaugeSimple(Context context) {
        super(context);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            this.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
    }

    public void setRango(float minimo, float maximo) {
        this.minimo = minimo;
        this.maximo = maximo;
    }

    public void setDivisiones(int divisiones) {
        this.numeroDivisiones = divisiones;
        invalidate();
    }

    public void setMedida(float medida) {
        this.medida = medida;
        invalidate();
    }

    public void setUnidades(String unidades) {
        this.unidades = unidades;
        invalidate();
    }

    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();

        float ancho = this.getWidth();
        float alto = this.getHeight();
        largo = 0.8f * Math.min(ancho, alto);

        canvas.translate(0.5f * ancho, 0.5f * alto);

        Paint pincel = new Paint();
        pincel.setAntiAlias(true);
        pincel.setTextSize(0.05f * largo);
        pincel.setLinearText(true);
        pincel.setFilterBitmap(true);
        pincel.setDither(true);

        // Fondo
        pincel.setStyle(Paint.Style.STROKE);
        pincel.setStrokeWidth(0.02f * largo);
        pincel.setColor(colorBordeTacometro);
        canvas.drawCircle(0, 0, 0.5f * largo, pincel);
        
        pincel.setStyle(Paint.Style.FILL);
        pincel.setColor(colorFondoTacometro);
        canvas.drawCircle(0, 0, 0.48f * largo, pincel);

        // Escala
        float indent = (float) (0.05 * largo);
        float posicionY = (float) (0.5 * largo);

        for (int i = 0; i <= numeroDivisiones; i++) {
            float anguloRotacion = 240 + (240f / numeroDivisiones) * i;
            canvas.save();
            canvas.rotate(anguloRotacion, 0, 0);
            pincel.setColor(colorLineas);
            pincel.setStrokeWidth(0.01f * largo);
            canvas.drawLine(0, -posicionY, 0, -posicionY + indent, pincel);

            float valorMarca = minimo + ((maximo - minimo) / numeroDivisiones) * i;
            String numero;
            // Si el rango es grande, mostrar sin decimales. Si es pequeño, con 1.
            if (Math.abs(maximo - minimo) > 20) {
                numero = String.format("%.0f", valorMarca);
            } else {
                numero = String.format("%.1f", valorMarca);
            }
            
            float anchoCadenaNumero = pincel.measureText(numero);
            canvas.rotate(-anguloRotacion, 0, -posicionY + 2.5f * indent);
            pincel.setColor(colorNumeros);
            canvas.drawText(numero, -0.5f * anchoCadenaNumero, -posicionY + 2.5f * indent, pincel);
            canvas.restore();
        }

        // Aguja
        float medidaReferencia = Math.max(minimo, Math.min(maximo, medida));
        float angulo_rotacion_medida = 240 + (240f / (maximo - minimo)) * (medidaReferencia - minimo);
        canvas.save();
        canvas.rotate(angulo_rotacion_medida, 0, 0);
        pincel.setStrokeWidth(0.01f * largo);
        pincel.setColor(Color.RED);
        canvas.drawLine(0, -posicionY + indent, 0, 1.5f * indent, pincel);
        canvas.restore();

        pincel.setStyle(Paint.Style.FILL);
        pincel.setColor(Color.RED);
        canvas.drawCircle(0, 0, 0.05f * largo, pincel);

        // Unidades y Medida
        pincel.setColor(colorLineas);
        pincel.setTextSize(0.08f * largo);
        float anchoUnidades = pincel.measureText(unidades);
        canvas.drawText(unidades, -0.5f * anchoUnidades, -0.15f * largo, pincel);

        pincel.setColor(colorNumerosDespliegue);
        pincel.setTextSize(0.12f * largo);
        String valorMedida;
        if (Math.abs(maximo - minimo) > 20) {
            valorMedida = String.format("%.0f", medidaReferencia);
        } else {
            valorMedida = String.format("%.1f", medidaReferencia);
        }
        float anchoMedida = pincel.measureText(valorMedida);
        canvas.drawText(valorMedida, -0.5f * anchoMedida, 0.2f * largo, pincel);

        canvas.restore();
    }
}

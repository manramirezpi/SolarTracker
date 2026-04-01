package com.solartracker.utilidades;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public class CircularSlider extends View {

    private float value = 1.0f;
    private float min = 0.1f;
    private float max = 10.0f;
    private String label = "Factor";

    private float centerX, centerY, radius;
    private Paint paintCircle, paintHandle, paintText;

    public interface OnValueChangeListener {
        void onValueChange(float value);
    }

    private OnValueChangeListener listener;

    public CircularSlider(Context context) {
        super(context);
        init();
    }

    private void init() {
        paintCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintCircle.setColor(Color.rgb(0, 230, 118)); // Neon Green
        paintCircle.setStyle(Paint.Style.STROKE);
        paintCircle.setStrokeWidth(12f);
        paintCircle.setAlpha(60); // Faint background track

        paintHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintHandle.setColor(Color.rgb(0, 230, 118)); // Neon Green
        paintHandle.setStyle(Paint.Style.FILL);
        paintHandle.setShadowLayer(8, 0, 0, Color.rgb(0, 230, 118));

        paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintText.setColor(Color.WHITE);
        paintText.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnValueChangeListener(OnValueChangeListener listener) {
        this.listener = listener;
    }

    public void setRange(float min, float max) {
        this.min = min;
        this.max = max;
        invalidate();
    }

    public void setValue(float value) {
        this.value = Math.max(min, Math.min(max, value));
        invalidate();
    }

    public float getValue() {
        return value;
    }

    public void setLabel(String label) {
        this.label = label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        centerX = getWidth() / 2f;
        centerY = getHeight() / 2f + (Math.min(centerX, getHeight() / 2f) * 0.03f); 
        radius = Math.min(centerX, getHeight() / 2f) * 0.82f;

        // Background track (faint)
        paintCircle.setAlpha(60);
        canvas.drawCircle(centerX, centerY, radius, paintCircle);

        // Calculate angle based on value
        float sweepAngle = ((value - min) / (max - min)) * 360f;
        float handleAngle = sweepAngle - 90f;

        // High intensity trail (Arc)
        paintCircle.setAlpha(255);
        canvas.drawArc(centerX - radius, centerY - radius, centerX + radius, centerY + radius, 
                -90f, sweepAngle, false, paintCircle);

        float hx = centerX + radius * (float) Math.cos(Math.toRadians(handleAngle));
        float hy = centerY + radius * (float) Math.sin(Math.toRadians(handleAngle));

        // Handle (smaller)
        canvas.drawCircle(hx, hy, 15f, paintHandle);

        // Text
        paintText.setTextSize(radius * 0.45f); // Increased from 0.3f
        String format = (max - min > 20) ? "%.0f" : "%.1f";
        canvas.drawText(String.format(format, value), centerX, centerY, paintText);
        paintText.setTextSize(radius * 0.22f); // Increased from 0.15f
        canvas.drawText(label, centerX, centerY + radius * 0.45f, paintText);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float tx = event.getX() - centerX;
        float ty = event.getY() - centerY;
        float angle = (float) Math.toDegrees(Math.atan2(ty, tx)) + 90f;
        if (angle < 0) angle += 360f;

        // Current status normalized [0, 1]
        float currentNorm = (value - min) / (max - min);
        float newNorm = angle / 360f;

        // Blocking: Prevent wrap-around jump from Max to Min (0.9 to 0.1)
        if (Math.abs(newNorm - currentNorm) > 0.5f) {
            return true; // Ignore jump, must rotate back
        }

        value = min + (newNorm * (max - min));
        
        if (listener != null) {
            listener.onValueChange(value);
        }
        
        invalidate();
        return true;
    }
}

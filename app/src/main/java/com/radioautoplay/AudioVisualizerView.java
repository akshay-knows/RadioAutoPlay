package com.radioautoplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class AudioVisualizerView extends View {

    private static final int BAR_COUNT = 28;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private boolean active;

    public AudioVisualizerView(Context context) {
        super(context);
        init();
    }

    public AudioVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AudioVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        barPaint.setColor(getResources().getColor(R.color.playing_green));
        basePaint.setColor(getResources().getColor(R.color.visualizer_low));
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float gap = dp(3);
        float barWidth = Math.max(dp(3), (width - gap * (BAR_COUNT - 1)) / BAR_COUNT);
        float centerY = height / 2f;
        float minHeight = dp(6);
        float maxHeight = Math.max(minHeight, height - dp(12));
        long now = SystemClock.uptimeMillis();

        for (int i = 0; i < BAR_COUNT; i++) {
            float progress = active ? liveLevel(i, now) : idleLevel(i);
            float barHeight = minHeight + (maxHeight - minHeight) * progress;
            float left = i * (barWidth + gap);
            float top = centerY - barHeight / 2f;
            float right = left + barWidth;
            float bottom = centerY + barHeight / 2f;

            barPaint.setColor(active ? getResources().getColor(R.color.playing_green)
                    : getResources().getColor(R.color.visualizer_low));
            barRect.set(left, top, right, bottom);
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint);
        }

        if (active) {
            postInvalidateOnAnimation();
        }
    }

    private float liveLevel(int index, long now) {
        double t = now / 230.0;
        double primary = Math.sin(t + index * 0.72);
        double secondary = Math.sin(t * 0.47 + index * 1.31);
        double blended = (primary + secondary) * 0.5;
        return 0.22f + (float) ((blended + 1.0) * 0.39);
    }

    private float idleLevel(int index) {
        return 0.12f + (index % 5) * 0.018f;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}

package app.ktmnav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import app.ktmnav.backend.ride.RideFormat;
import app.ktmnav.backend.ride.RideStats;
import app.ktmnav.backend.ride.TrackPoint;
import app.ktmnav.backend.ride.TrackProjection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RideTrackView extends View {
    private static final float MIN_STEP_PX = 1.5f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] bandPaths = new Path[TrackProjection.BAND_LIMITS_KMH.length + 1];
    private int[] bandColors = {0xFF4CAF50, 0xFFFFC107, 0xFFFF6D00, 0xFFE53935};
    private int startColor = 0xFF43A047;
    private int endColor = 0xFFE53935;
    private int backgroundColor = Color.TRANSPARENT;
    private List<TrackPoint> points = Collections.emptyList();
    private float startX = Float.NaN;
    private float startY;
    private float endX;
    private float endY;

    public RideTrackView(Context context) {
        this(context, null);
    }

    public RideTrackView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RideTrackView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dot.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bandPaths.length; i++) bandPaths[i] = new Path();
        setContentDescription(RideFormat.describe(null));
    }

    public void setRide(List<TrackPoint> track, RideStats stats) {
        points = track != null ? new ArrayList<>(track) : Collections.<TrackPoint>emptyList();
        setContentDescription(RideFormat.describe(stats != null ? stats : RideStats.of(points)));
        rebuild();
        invalidate();
    }

    public void setColors(int background, int[] speedBandColors, int start, int end) {
        backgroundColor = background;
        if (speedBandColors != null && speedBandColors.length >= bandPaths.length) {
            bandColors = speedBandColors.clone();
        }
        startColor = start;
        endColor = end;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void rebuild() {
        for (Path p : bandPaths) p.reset();
        startX = Float.NaN;
        int n = points.size();
        if (n == 0 || getWidth() == 0 || getHeight() == 0) return;
        float[] xy = TrackProjection.project(points, getWidth(), getHeight(), dp(16));
        float[] speeds = TrackProjection.speeds(points);
        startX = xy[0];
        startY = xy[1];
        endX = xy[(n - 1) * 2];
        endY = xy[(n - 1) * 2 + 1];
        float lastX = startX;
        float lastY = startY;
        for (int i = 1; i < n; i++) {
            float x = xy[i * 2];
            float y = xy[i * 2 + 1];
            if (i < n - 1 && Math.abs(x - lastX) < MIN_STEP_PX && Math.abs(y - lastY) < MIN_STEP_PX) continue;
            Path p = bandPaths[TrackProjection.band(speeds[i])];
            p.moveTo(lastX, lastY);
            p.lineTo(x, y);
            lastX = x;
            lastY = y;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (backgroundColor != Color.TRANSPARENT) canvas.drawColor(backgroundColor);
        if (Float.isNaN(startX)) return;
        line.setStrokeWidth(dp(3));
        for (int i = 0; i < bandPaths.length; i++) {
            line.setColor(bandColors[i]);
            canvas.drawPath(bandPaths[i], line);
        }
        float r = dp(5);
        dot.setColor(endColor);
        canvas.drawCircle(endX, endY, r, dot);
        dot.setColor(startColor);
        canvas.drawCircle(startX, startY, r, dot);
    }
}

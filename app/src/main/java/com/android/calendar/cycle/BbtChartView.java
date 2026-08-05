package com.android.calendar.cycle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import kotlin.Pair;

/**
 * Custom View that draws a basal body temperature (BBT) line chart with
 * period-range highlighting, ovulation-threshold guide, and gradient fill.
 * <p>
 * Data: Pair<epochDay, temperatureInCelsius>
 * Period ranges: Pair<startEpochDay, endEpochDay> — drawn as light-pink bands.
 */
public class BbtChartView extends View {

    private static final int LINE_COLOR = 0xFF3D8BFF;
    private static final int FILL_TOP_COLOR = 0x603D8BFF;
    private static final int FILL_BOTTOM_COLOR = 0x053D8BFF;
    private static final int PERIOD_BAND_COLOR = 0x30FFB6C1;

    private final float density;
    private final Paint gridPaint;
    private final Paint dashPaint;
    private final Paint linePaint;
    private final Paint dotPaint;
    private final Paint dotStrokePaint;
    private final Paint labelPaint;
    private final Paint xLabelPaint;
    private final Paint periodPaint;
    private final Paint fillPaint;

    private List<Pair<Long, Double>> data = Collections.emptyList();
    private List<Pair<Long, Long>> periodRanges = Collections.emptyList();

    private float chartLeft;
    private float chartTop;
    private float chartRight;
    private float chartBottom;

    private double yMin = 35.5;
    private double yMax = 38.5;

    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM/dd");

    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    // ------------------------------------------------------------------ constructors

    public BbtChartView(Context context) {
        this(context, null);
    }

    public BbtChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BbtChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0xFFE0E0E0);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setStyle(Paint.Style.STROKE);

        dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dashPaint.setColor(0xFFFF5722);
        dashPaint.setStrokeWidth(1.5f * density);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setPathEffect(new DashPathEffect(
                new float[]{8f * density, 6f * density}, 0f));

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(LINE_COLOR);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(LINE_COLOR);
        dotPaint.setStyle(Paint.Style.FILL);

        dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotStrokePaint.setColor(Color.WHITE);
        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(2f * density);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(0xFF888888);
        labelPaint.setTextSize(10f * density);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        labelPaint.setTypeface(Typeface.DEFAULT);

        xLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        xLabelPaint.setColor(0xFF888888);
        xLabelPaint.setTextSize(10f * density);
        xLabelPaint.setTextAlign(Paint.Align.CENTER);
        xLabelPaint.setTypeface(Typeface.DEFAULT);

        periodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        periodPaint.setColor(PERIOD_BAND_COLOR);
        periodPaint.setStyle(Paint.Style.FILL);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ API

    public void setData(List<Pair<Long, Double>> newData) {
        data = new ArrayList<>(newData);
        Collections.sort(data, Comparator.comparing(Pair<Long, Double>::getFirst));
        if (!data.isEmpty()) {
            double minTemp = data.get(0).getSecond();
            double maxTemp = data.get(0).getSecond();
            for (Pair<Long, Double> p : data) {
                double t = p.getSecond();
                if (t < minTemp) minTemp = t;
                if (t > maxTemp) maxTemp = t;
            }
            yMin = Math.max(minTemp - 0.2, 35.0);
            yMax = Math.min(maxTemp + 0.2, 39.0);
            if (yMax - yMin < 1.0) {
                double mid = (yMin + yMax) / 2.0;
                yMin = Math.max(mid - 0.5, 35.0);
                yMax = Math.min(mid + 0.5, 39.0);
            }
        }
        invalidate();
    }

    public void setPeriodRanges(List<Pair<Long, Long>> ranges) {
        periodRanges = ranges != null ? ranges : Collections.emptyList();
        invalidate();
    }

    // ------------------------------------------------------------------ measure

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredH = Math.round(200f * density);
        int h = resolveSize(desiredH, heightMeasureSpec);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (data.isEmpty()) return;

        computeChartBounds();
        drawPeriodBands(canvas);
        drawGrid(canvas);
        drawYAxisLabels(canvas);
        drawOvulationLine(canvas);
        drawGradientFill(canvas);
        drawLine(canvas);
        drawDataPoints(canvas);
        drawXAxisLabels(canvas);
    }

    // ------------------------------------------------------------ helpers

    private void computeChartBounds() {
        chartLeft = getPaddingLeft() + 44f * density;
        chartTop = getPaddingTop() + 8f * density;
        chartRight = (getWidth() - getPaddingRight()) - 8f * density;
        chartBottom = (getHeight() - getPaddingBottom()) - 24f * density;
    }

    private void drawPeriodBands(Canvas canvas) {
        if (periodRanges.isEmpty() || data.isEmpty()) return;
        long dataMinDay = data.get(0).getFirst();
        long dataMaxDay = data.get(data.size() - 1).getFirst();
        float daySpan = dataMaxDay - dataMinDay;
        if (daySpan <= 0f) return;

        for (Pair<Long, Long> range : periodRanges) {
            long startDay = range.getFirst();
            long endDay = range.getSecond();
            if (startDay > dataMaxDay || endDay < dataMinDay) continue;
            long clampedStart = Math.max(startDay, dataMinDay);
            long clampedEnd = Math.min(endDay, dataMaxDay);
            float x0 = chartLeft + ((clampedStart - dataMinDay) / daySpan) * (chartRight - chartLeft);
            float x1 = chartLeft + ((clampedEnd - dataMinDay) / daySpan) * (chartRight - chartLeft);
            canvas.drawRect(x0, chartTop, x1, chartBottom, periodPaint);
        }
    }

    private void drawGrid(Canvas canvas) {
        if (chartRight <= chartLeft || chartBottom <= chartTop) return;

        double yRange = yMax - yMin;
        double step = yRange / 5.0;
        gridPaint.setColor(0xFFE8E8E8);
        for (int i = 0; i <= 5; i++) {
            double yVal = yMin + step * i;
            float y = (float) (chartBottom - ((yVal - yMin) / yRange) * (chartBottom - chartTop));
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
        }
    }

    private void drawYAxisLabels(Canvas canvas) {
        if (chartRight <= chartLeft || chartBottom <= chartTop) return;

        double yRange = yMax - yMin;
        double step = yRange / 5.0;
        for (int i = 0; i <= 5; i++) {
            double yVal = yMin + step * i;
            float y = (float) (chartBottom - ((yVal - yMin) / yRange) * (chartBottom - chartTop));
            String text = String.format("%.1f", yVal);
            float textX = chartLeft - 6f * density;
            float textY = y + 4f * density;
            canvas.drawText(text, textX, textY, labelPaint);
        }
    }

    private void drawOvulationLine(Canvas canvas) {
        if (chartBottom <= chartTop) return;
        double threshold = 36.7;
        if (threshold < yMin || threshold > yMax) return;
        float y = (float) (chartBottom - ((threshold - yMin) / (yMax - yMin)) * (chartBottom - chartTop));
        canvas.drawLine(chartLeft, y, chartRight, y, dashPaint);

        Paint.Align prevAlign = labelPaint.getTextAlign();
        labelPaint.setTextAlign(Paint.Align.LEFT);
        labelPaint.setColor(0xFFFF5722);
        labelPaint.setTextSize(9f * density);
        canvas.drawText("36.7°", chartRight + 2f * density, y + 3f * density, labelPaint);
        labelPaint.setTextAlign(prevAlign);
        labelPaint.setColor(0xFF888888);
        labelPaint.setTextSize(10f * density);
    }

    private void drawGradientFill(Canvas canvas) {
        if (data.size() < 2 || chartBottom <= chartTop) return;

        long dataMinDay = data.get(0).getFirst();
        long dataMaxDay = data.get(data.size() - 1).getFirst();
        float daySpan = dataMaxDay - dataMinDay;
        if (daySpan <= 0f) return;

        fillPath.reset();
        float x0 = chartLeft + ((data.get(0).getFirst() - dataMinDay) / daySpan) * (chartRight - chartLeft);
        float y0 = (float) (chartBottom - ((data.get(0).getSecond() - yMin) / (yMax - yMin)) * (chartBottom - chartTop));
        fillPath.moveTo(x0, chartBottom);
        fillPath.lineTo(x0, y0);

        buildBezierPath(fillPath, dataMinDay, daySpan);

        Pair<Long, Double> last = data.get(data.size() - 1);
        float xLast = chartLeft + ((last.getFirst() - dataMinDay) / daySpan) * (chartRight - chartLeft);
        float yLast = (float) (chartBottom - ((last.getSecond() - yMin) / (yMax - yMin)) * (chartBottom - chartTop));
        fillPath.lineTo(xLast, yLast);
        fillPath.lineTo(xLast, chartBottom);
        fillPath.close();

        LinearGradient gradient = new LinearGradient(
                chartLeft, chartTop,
                chartLeft, chartBottom,
                new int[]{FILL_TOP_COLOR, FILL_BOTTOM_COLOR},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP
        );
        fillPaint.setShader(gradient);
        canvas.drawPath(fillPath, fillPaint);
    }

    private void drawLine(Canvas canvas) {
        if (data.size() < 2 || chartBottom <= chartTop) return;

        long dataMinDay = data.get(0).getFirst();
        long dataMaxDay = data.get(data.size() - 1).getFirst();
        float daySpan = dataMaxDay - dataMinDay;
        if (daySpan <= 0f) return;

        linePath.reset();
        float x0 = chartLeft + ((data.get(0).getFirst() - dataMinDay) / daySpan) * (chartRight - chartLeft);
        float y0 = (float) (chartBottom - ((data.get(0).getSecond() - yMin) / (yMax - yMin)) * (chartBottom - chartTop));
        linePath.moveTo(x0, y0);

        buildBezierPath(linePath, dataMinDay, daySpan);

        canvas.drawPath(linePath, linePaint);
    }

    private void buildBezierPath(Path path, long dataMinDay, float daySpan) {
        int n = data.size();
        for (int i = 0; i < n - 1; i++) {
            Pair<Long, Double> p0 = data.get(i);
            Pair<Long, Double> p1 = data.get(i + 1);
            float x0 = chartLeft + ((p0.getFirst() - dataMinDay) / daySpan) * (chartRight - chartLeft);
            float y0 = (float) (chartBottom - ((p0.getSecond() - yMin) / (yMax - yMin)) * (chartBottom - chartTop));
            float x1 = chartLeft + ((p1.getFirst() - dataMinDay) / daySpan) * (chartRight - chartLeft);
            float y1 = (float) (chartBottom - ((p1.getSecond() - yMin) / (yMax - yMin)) * (chartBottom - chartTop));

            float dx = (x1 - x0) * 0.4f;
            float cp1x = x0 + dx;
            float cp1y = y0;
            float cp2x = x1 - dx;
            float cp2y = y1;

            path.cubicTo(cp1x, cp1y, cp2x, cp2y, x1, y1);
        }
    }

    private void drawDataPoints(Canvas canvas) {
        if (data.isEmpty() || chartBottom <= chartTop) return;

        long dataMinDay = data.get(0).getFirst();
        long dataMaxDay = data.get(data.size() - 1).getFirst();
        float daySpan = dataMaxDay - dataMinDay;
        if (daySpan <= 0f) return;

        float radius = 4f * density;
        for (Pair<Long, Double> point : data) {
            long day = point.getFirst();
            double temp = point.getSecond();
            float x = chartLeft + ((day - dataMinDay) / daySpan) * (chartRight - chartLeft);
            float y = (float) (chartBottom - ((temp - yMin) / (yMax - yMin)) * (chartBottom - chartTop));

            canvas.drawCircle(x, y, radius + 1f * density, dotStrokePaint);
            canvas.drawCircle(x, y, radius, dotPaint);
        }
    }

    private void drawXAxisLabels(Canvas canvas) {
        if (data.isEmpty()) return;

        long dataMinDay = data.get(0).getFirst();
        long dataMaxDay = data.get(data.size() - 1).getFirst();
        float daySpan = dataMaxDay - dataMinDay;
        if (daySpan <= 0f) return;

        int maxLabels = Math.max((int) ((chartRight - chartLeft) / (56f * density)), 2);
        long totalDays = dataMaxDay - dataMinDay;
        if (totalDays <= 0) return;

        float stepDays = Math.max(totalDays / (float) (maxLabels - 1), 1f);
        List<Long> labelDays = new ArrayList<>();
        long d = dataMinDay;
        while (d <= dataMaxDay) {
            labelDays.add(d);
            d += Math.max(Math.round(stepDays), 1);
        }
        // Ensure last label is shown
        if (!labelDays.isEmpty() && !labelDays.get(labelDays.size() - 1).equals(dataMaxDay)) {
            labelDays.set(labelDays.size() - 1, dataMaxDay);
        }

        for (long ld : labelDays) {
            float x = chartLeft + ((ld - dataMinDay) / daySpan) * (chartRight - chartLeft);
            String label = LocalDate.ofEpochDay(ld).format(dateFormatter);
            float y = chartBottom + 14f * density;
            canvas.drawText(label, x, y, xLabelPaint);
        }
    }
}

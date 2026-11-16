package com.android.systemui.navigationbar.spectrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

public class SpectrumView extends View {
	private int steps = 1024; // Must be a power of 2 as we do an FFT of steps*2 size.
	private int stepsDraw = 929; // How many of the  steps do we draw.
	private static final float decay = 0.95f;
	private static final float range = 19;

	private float[] buffer;
	private float[] spectrum;
	private float[] peaks;
	private float[] window;
	private int frames = 0;
	private boolean flip = false;

	// At 44.1kHz, the bins are for n * 44100 / size.
	private FFT fft;

	//private Paint BGPaint;
	private Paint peakPaint;
	private Paint FGPaint;
	private float[] FGColor = new float[3]; // HSV foreground color.
	private final Path peakPath = new Path(), fgPath = new Path();


	public SpectrumView(Context context) {
		super(context);
		init();
	}

	public SpectrumView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public SpectrumView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init();
	}

	private void init() {
		//BGPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		FGPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		//BGPaint.setColor(Color.MAGENTA);
		//BGPaint.setStyle(Paint.Style.FILL);
		//BGPaint.setAlpha(128);
		peakPaint.setColor(Color.GRAY);
		peakPaint.setStyle(Paint.Style.FILL);
		peakPaint.setAlpha(64);
		Color.colorToHSV(Color.RED, FGColor);
		FGPaint.setStyle(Paint.Style.FILL);
		FGPaint.setAlpha(64);
		setSteps(steps);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		float w = getWidth();
		float h = getHeight();
		canvas.save();
		if (w < h) {
			if (flip) {
				canvas.translate(0, h);
				canvas.rotate(-90);
			} else {
				canvas.translate(w, 0);
				canvas.rotate(90);
			}
			float t = w;
			w = h;
			h = t;
		}
		float sw = w / (stepsDraw - 1);
		int cframes = 0;

		synchronized (this) {
			if (frames > 0) {
				fft.calculate(buffer, window);
				cframes = frames; // We copy it for thread-safety.
				frames = 0;
			}
		}
		if (cframes > 0) {
			fft.getMagnitudes(spectrum);

			// The magnitude of FFT values is proportional to the FFT size, this is normal.
			for (int i = 0; i < spectrum.length; ++i)
				spectrum[i] = (float) Math.max(0, (range + Math.log(spectrum[i] / (spectrum.length / 2.0f)) / Math.log(2)) / range);

			while (cframes-- > 0) { // Decay the peaks by the right amount even if we missed a frame.
				for (int i = 0; i < peaks.length; ++i)
					peaks[i] = Math.max(peaks[i] * decay, spectrum[i]);
				FGColor[0] = (FGColor[0] + 1f) % 360f; // Rotate the foreground color.
			}
		}

		peakPath.reset();
		peakPath.moveTo(0, h);
		fgPath.reset();
		fgPath.moveTo(0, h);
		for (int i = 0; i < stepsDraw; ++i) {
			float x = sw * i;
			float top = h - spectrum[i] * h;
			float peak = h - peaks[i] * h;
			peakPath.lineTo(x, peak);
			fgPath.lineTo(x, top);
		}
		peakPath.lineTo(w, h);
		peakPath.close();
		fgPath.lineTo(w, h);
		fgPath.close();
		//canvas.drawRect(0, 0, w, h, BGPaint); // TODO remove
		canvas.drawPath(peakPath, peakPaint);
		FGPaint.setColor(Color.HSVToColor(64, FGColor));
		canvas.drawPath(fgPath, FGPaint);
		canvas.restore();
	}

	public void setSteps(int n) {
		synchronized (this) {
			steps = n;
			fft = new FFT(steps * 2); // Must be a power of 2, by extension bars also.
			spectrum = new float[steps];
			Arrays.fill(spectrum, 0);
			peaks = new float[steps];
			Arrays.fill(peaks, 0);
			buffer = new float[steps * 2];
			Arrays.fill(buffer, 0);
			// Hamming window.
			window = new float[steps * 2];
			int N = window.length;
			for (int i = 0; i < N; ++i)
				window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
		}
		invalidate();
	}

	public void update(float[] data, int n) { // Can be called from non-UI thread, should finish fast.
		synchronized (this) {
			int off = Math.max(buffer.length - n, 0);
			for (int i = 0; i < off; ++i)
				buffer[i] = buffer[i + n];
			System.arraycopy(data, 0, buffer, off, Math.min(n, buffer.length));
			++frames;
		}
		postInvalidate(); // Note that invalidate() is not thread-safe, and the resulting bug is very confusing.
	}

	public void reset() {
		setSteps(steps);
	}

	public void setFlip(boolean f) {
		flip = f;
	}
}

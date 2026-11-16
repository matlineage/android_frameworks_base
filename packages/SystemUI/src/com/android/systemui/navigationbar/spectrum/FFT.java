package com.android.systemui.navigationbar.spectrum;

public class FFT {
	private final float[] tf_re;
	private final float[] tf_im;
	private final float[] out_re;
	private final float[] out_im;
	private final int n;

	public FFT(int size) { // Size must be a power of 2!
		n = size;
		tf_re = new float[n];
		tf_im = new float[n];
		out_re = new float[n];
		out_im = new float[n];
		int i, k;
		for (i = 1; i < n; i <<= 1) for (k = 0; k < i; ++k) {
			tf_re[i - 1 + k] = (float) Math.cos(-2 * Math.PI * k / (i << 1));
			tf_im[i - 1 + k] = (float) Math.sin(-2 * Math.PI * k / (i << 1));
		}
	}

	public void calculate(float[] input, float[] window) {
		int i, j, k, l = Math.min(n, input.length);
		for (i = 0, k = 0; i < l; ++i) {
			out_re[k] = (window == null) ? input[i] : (input[i] * window[i]);
			out_im[k] = 0;
			for (j = n >> 1; (k & j) != 0; j >>= 1)
				k ^= j;
			k ^= j;
		}
		for (i = 1; i < n; i <<= 1) for (j = 0; j < n; j += (i << 1)) for (k = 0; k < i; ++k) {
			int tf = i - 1 + k, out = j + k + i, x = j + k;
			float tmp_re = tf_re[tf] * out_re[out] - tf_im[tf] * out_im[out];
			float tmp_im = tf_re[tf] * out_im[out] + tf_im[tf] * out_re[out];
			out_re[out] = out_re[x] - tmp_re;
			out_im[out] = out_im[x] - tmp_im;
			out_re[x] += tmp_re;
			out_im[x] += tmp_im;
		}
	}

	public void getMagnitudes(float[] output) {
		int l = Math.min(n, output.length);
		for (int i = 0; i < l; ++i)
			output[i] = (float) Math.sqrt(out_re[i] * out_re[i] + out_im[i] * out_im[i]);
	}
}

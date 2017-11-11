package com.enrique.stackblur;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.concurrent.Callable;

/**
 * Blur using Java code.
 *
 * This is a compromise between Gaussian Blur and Box blur
 * It creates much better looking blurs than Box Blur, but is
 * 7x faster than my Gaussian Blur implementation.

 * I called it Stack Blur because this describes best how this
 * filter works internally: it creates a kind of moving stack
 * of colors whilst scanning through the image. Thereby it
 * just has to add one new block of color to the right side
 * of the stack and remove the leftmost color. The remaining
 * colors on the topmost layer of the stack are either added on
 * or reduced by one, depending on if they are on the right or
 * on the left side of the stack.
 *
 * @author Enrique López Mañas <eenriquelopez@gmail.com>
 * http://www.neo-tech.es
 *
 * Author of the original algorithm: Mario Klingemann <mario.quasimondo.com>
 *
 * Based heavily on http://vitiy.info/Code/stackblur.cpp
 * See http://vitiy.info/stackblur-algorithm-multi-threaded-blur-for-cpp/
 *
 * @copyright: Enrique López Mañas
 * @license: Apache License 2.0
 */
class JavaBlurProcess implements BlurProcess {
	@Override
	public void blur(Bitmap src, Bitmap dst, float radius) {
		if (!dst.isMutable()) {
			throw new IllegalArgumentException("dst must be mutable");
		}
		if (radius < 0) {
			throw new IllegalArgumentException("radius must be >= 0");
		}
		if (radius == 0) {
			if (src != dst) {
				Canvas canvas = new Canvas(dst);
				Rect rect = new Rect(0, 0, dst.getWidth(), dst.getHeight());
				canvas.drawBitmap(src, null, rect, null);
			}
			return;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		int[] currentPixels = new int[w * h];
		src.getPixels(currentPixels, 0, w, 0, 0, w, h);
		int cores = StackBlurManager.EXECUTOR_THREADS;

		ArrayList<BlurTask> jobs = new ArrayList<BlurTask>(cores);
		for (int i = 0; i < cores; i++) {
			jobs.add(new BlurTask(currentPixels, w, h, Math.round(radius), cores, i, false));
		}

		try {
			StackBlurManager.EXECUTOR.invokeAll(jobs);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		for (int i = 0, jobsSize = jobs.size(); i < jobsSize; i++) {
			BlurTask job = jobs.get(i);
			job.horizontal = true;
		}

		try {
			StackBlurManager.EXECUTOR.invokeAll(jobs);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		Canvas canvas = new Canvas(dst);
		canvas.drawBitmap(currentPixels, 0, w, 0, 0, dst.getWidth(), dst.getHeight(), src.hasAlpha(), null);
	}

	private static class LineBlur {
		private final int[] src;
		private final int w;
		private final int h;
		private final int radius;
		private final int div;
		private final byte[] stackR;
		private final byte[] stackG;
		private final byte[] stackB;
		private final byte[] stackA;
		private final float divSum;


		private LineBlur(int[] src, int w, int h, int radius, boolean blurAlpha) {
			this.src = src;
			this.w = w;
			this.h = h;
			this.radius = radius;

			this.div = (radius * 2) + 1;
			this.divSum = 1.0f / ((radius + 1) * (radius + 1));
			this.stackR = new byte[div];
			this.stackG = new byte[div];
			this.stackB = new byte[div];
			if (blurAlpha) {
				this.stackA = new byte[div];
			} else {
				this.stackA = null;
			}
		}

		private void blurLine(int lineIdx, boolean horizontal) {
			int stride;
			int stack_i = 0;
			int stack_drop = 0;
			int inputValue;

			int src_i;
			int dst_i;

			int r, g, b, a;
			int sumR, sumG, sumB, sumA;
			int sumInR, sumInG, sumInB, sumInA;
			int sumOutR, sumOutG, sumOutB, sumOutA;

			int max_i;
			if (horizontal) {
				stride = 1;
				src_i = w * lineIdx;
				max_i = src_i + w;
			} else {
				stride = w;
				src_i = lineIdx;
				max_i = src_i + w * h;
			}

			dst_i = src_i;
			sumR = sumG = sumB = sumA = 0;
			sumInR = sumInG = sumInB = sumInA = 0;
			sumOutR = sumOutG = sumOutB = sumOutA = 0;

			for (int i = 0; i <= radius; i++) {
				stack_i = i;
				inputValue = src[src_i];
				if (this.stackA != null) {
					a = inputValue >>> 24 & 0xFF;
					stackA[stack_i] = (byte) a;
					sumA += a * (i + 1);
					sumOutA += a;
				}
				r = (inputValue >>> 16) & 0xFF;
				g = (inputValue >>> 8) & 0xFF;
				b = inputValue & 0xFF;
				stackR[stack_i] = (byte) r;
				stackG[stack_i] = (byte) g;
				stackB[stack_i] = (byte) b;
				sumR += r * (i + 1);
				sumG += g * (i + 1);
				sumB += b * (i + 1);
				sumOutR += r;
				sumOutG += g;
				sumOutB += b;
			}

			for (int i = 1; i <= radius; i++) {
				if (src_i + stride < max_i) {
					src_i += stride;
				}
				stack_i = i + radius;
				inputValue = src[src_i];
				if (stackA != null) {
					a = (inputValue >>> 24) & 0xFF;
					stackA[stack_i] = (byte) a;
					sumA += a * (radius + 1 - i);
					sumInA += a;
				}
				r = (inputValue >>> 16) & 0xFF;
				g = (inputValue >>> 8) & 0xFF;
				b = inputValue & 0xFF;
				stackR[stack_i] = (byte) r;
				stackG[stack_i] = (byte) g;
				stackB[stack_i] = (byte) b;
				sumR += r * (radius + 1 - i);
				sumG += g * (radius + 1 - i);
				sumB += b * (radius + 1 - i);
				sumInR += r;
				sumInG += g;
				sumInB += b;
			}


			stack_i = radius;
			while (dst_i < max_i) {
				if (src_i + stride < max_i) {
					src_i += stride;
				}

				a = (stackA == null) ? (src[dst_i] >>> 24) : (int) (sumA * divSum);
				r = (int) (sumR * divSum);
				g = (int) (sumG * divSum);
				b = (int) (sumB * divSum);
				src[dst_i] = (a << 24) | (r << 16) | (g << 8) | b;

				dst_i += stride;

				sumR -= sumOutR;
				sumG -= sumOutG;
				sumB -= sumOutB;
				sumA -= sumOutA;

				stack_drop = (stack_i + radius + 1) % div;
				sumOutR -= (stackR[stack_drop] & 0xFF);
				sumOutG -= (stackG[stack_drop] & 0xFF);
				sumOutB -= (stackB[stack_drop] & 0xFF);
				if (stackA != null) {
					sumOutA -= (stackA[stack_drop] & 0xFF);
				}

				inputValue = src[src_i];
				r = (inputValue >>> 16) & 0xFF;
				stackR[stack_drop] = (byte) r;
				sumInR += r;
				sumR += sumInR;

				g = (inputValue >>> 8) & 0xFF;
				stackG[stack_drop] = (byte) g;
				sumInG += g;
				sumG += sumInG;

				b = inputValue & 0xFF;
				stackB[stack_drop] = (byte) b;
				sumInB += b;
				sumB += sumInB;

				if (stackA != null) {
					a = (inputValue >>> 24) & 0xFF;
					stackA[stack_drop] = (byte) a;
					sumInA += a;
					sumA += sumInA;
				}

				stack_i = (stack_i + 1) % div;

				sumOutR += (stackR[stack_i] & 0xFF);
				sumInR -= (stackR[stack_i] & 0xFF);

				sumOutG += (stackG[stack_i] & 0xFF);
				sumInG -= (stackG[stack_i] & 0xFF);

				sumOutB += (stackB[stack_i] & 0xFF);
				sumInB -= (stackB[stack_i] & 0xFF);

				if (stackA != null) {
					sumOutA += (stackA[stack_i] & 0xFF);
					sumInA -= (stackA[stack_i] & 0xFF);
				}
			}
		}
	}

	private static class BlurTask implements Callable<Void> {
		private final LineBlur _blur;
		private final int _w;
		private final int _h;
		private final int _totalCores;
		private final int _coreIndex;
		boolean horizontal = false;

		BlurTask(int[] src, int w, int h, int radius, int totalCores, int coreIndex, boolean blurAlpha) {
			_w = w;
			_h = h;
			_totalCores = totalCores;
			_coreIndex = coreIndex;
			_blur = new LineBlur(src, w, h, radius, blurAlpha);
		}

		@Override public Void call() throws Exception {
			int _minLine;
			int _maxLine;
			if (horizontal) {
				_minLine = _h * _coreIndex / _totalCores;
				_maxLine = _h * (_coreIndex + 1) / _totalCores;
			} else {
				_minLine = _w * _coreIndex / _totalCores;
				_maxLine = _w * (_coreIndex + 1) / _totalCores;
			}

			for (int i = _minLine; i < _maxLine; i++) {
				_blur.blurLine(i, horizontal);
			}
			return null;
		}

	}
}

package com.enrique.stackblur;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.Callable;

/**
 * @see JavaBlurProcess
 * Blur using the NDK and native code.
 */
class NativeBlurProcess implements BlurProcess {
	private static native void functionToBlur(Bitmap bitmapOut, int radius, int threadCount, int threadIndex, boolean horizontal);

	static {
		System.loadLibrary("blur");
	}

	@Override
	public void blur(Bitmap src, Bitmap dst, float radius) {
		if (!dst.isMutable()) {
			throw new IllegalArgumentException("dst must be mutable");
		}
		if (radius < 0) {
			throw new IllegalArgumentException("radius must be >= 0");
		}
		if (dst != src) {
			Canvas canvas = new Canvas(dst);
			Rect rect = new Rect(0, 0, dst.getWidth(), dst.getHeight());
			canvas.drawBitmap(src, null, rect, null);
		}
		float scale = Math.min((float) dst.getWidth() / src.getWidth(), (float) dst.getHeight() / src.getHeight());
		radius *= scale;

		int roundRadius = Math.round(radius);
		if (roundRadius == 0) {
			return;
		}

		int cores = StackBlurManager.EXECUTOR_THREADS;

		ArrayList<NativeTask> jobs = new ArrayList<NativeTask>(cores);
		for (int i = 0; i < cores; i++) {
			jobs.add(new NativeTask(dst, roundRadius, cores, i));
		}

		try {
			StackBlurManager.EXECUTOR.invokeAll(jobs);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		for (int i = 0, jobsSize = jobs.size(); i < jobsSize; i++) {
			NativeTask job = jobs.get(i);
			job.horizontal = true;
		}

		try {
			StackBlurManager.EXECUTOR.invokeAll(jobs);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static class NativeTask implements Callable<Void> {
		private final Bitmap _bitmapOut;
		private final int _radius;
		private final int _totalCores;
		private final int _coreIndex;
		boolean horizontal;

		NativeTask(Bitmap bitmapOut, int radius, int totalCores, int coreIndex) {
			_bitmapOut = bitmapOut;
			_radius = radius;
			_totalCores = totalCores;
			_coreIndex = coreIndex;
			horizontal = false;
		}

		@Override public Void call() throws Exception {
			functionToBlur(_bitmapOut, _radius, _totalCores, _coreIndex, horizontal);
			return null;
		}

	}
}

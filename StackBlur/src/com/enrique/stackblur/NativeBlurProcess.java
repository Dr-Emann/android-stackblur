package com.enrique.stackblur;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.concurrent.Callable;

/**
 * @see JavaBlurProcess
 * Blur using the NDK and native code.
 */
class NativeBlurProcess implements BlurProcess {
	private static native void functionToBlur(Bitmap bitmapOut, int radius, int threadCount, int threadIndex, int round);

	static {
		System.loadLibrary("blur");
	}

	@Override
	public void blur(Bitmap src, Bitmap dst, float radius) {
		Canvas canvas = new Canvas(dst);
		Rect rect = new Rect(0, 0, dst.getWidth(), dst.getHeight());
		canvas.drawBitmap(src, null, rect, null);

		int cores = StackBlurManager.EXECUTOR_THREADS;

		ArrayList<NativeTask> horizontal = new ArrayList<NativeTask>(cores);
		ArrayList<NativeTask> vertical = new ArrayList<NativeTask>(cores);
		for (int i = 0; i < cores; i++) {
			horizontal.add(new NativeTask(dst, (int) radius, cores, i, 1));
			vertical.add(new NativeTask(dst, (int) radius, cores, i, 2));
		}

		try {
			StackBlurManager.EXECUTOR.invokeAll(horizontal);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		try {
			StackBlurManager.EXECUTOR.invokeAll(vertical);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private static class NativeTask implements Callable<Void> {
		private final Bitmap _bitmapOut;
		private final int _radius;
		private final int _totalCores;
		private final int _coreIndex;
		private final int _round;

		NativeTask(Bitmap bitmapOut, int radius, int totalCores, int coreIndex, int round) {
			_bitmapOut = bitmapOut;
			_radius = radius;
			_totalCores = totalCores;
			_coreIndex = coreIndex;
			_round = round;
		}

		@Override public Void call() throws Exception {
			functionToBlur(_bitmapOut, _radius, _totalCores, _coreIndex, _round);
			return null;
		}

	}
}

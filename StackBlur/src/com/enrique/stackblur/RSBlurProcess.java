package com.enrique.stackblur;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;

/**
 * @see JavaBlurProcess
 * Blur using renderscript.
 */
class RSBlurProcess implements BlurProcess {
	private static volatile RenderScript RS;
	private static final ThreadLocal<ScriptIntrinsicBlur> _blur = new ThreadLocal<>();
	private final Context context;

	public RSBlurProcess(Context context) {
		this.context = context.getApplicationContext();
		if (RS == null) {
			RS = RenderScript.create(this.context);
		}
	}

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
		ScriptIntrinsicBlur blur = _blur.get();
		if (blur == null) {
			blur = ScriptIntrinsicBlur.create(RS, Element.U8_4(RS));
			_blur.set(blur);
		}
		Bitmap scaleFrom = src;
		if (dst.getWidth() < src.getWidth() || dst.getHeight() < src.getHeight()) {
			scaleFrom = dst;
			radius *= Math.min((float) dst.getWidth() / src.getWidth(), (float) dst.getHeight() / src.getHeight());
		}

		float scale = 1f;
		while (radius > 25) {
			radius /= 2;
			scale /= 2;
		}
		int w = (int) (scaleFrom.getWidth() * scale);
		int h = (int) (scaleFrom.getHeight() * scale);
		Bitmap intermediateIn = Bitmap.createScaledBitmap(scaleFrom, w, h, true);
		Bitmap intermediateOut;
		if (intermediateIn == dst || dst.getWidth() != w || dst.getHeight() != h) {
			intermediateOut = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
		} else {
			intermediateOut = dst;
		}
		Bitmap alphaCopy;
		if (!intermediateIn.hasAlpha()) {
			alphaCopy = null;
		} else if (intermediateIn != intermediateOut) {
			alphaCopy = intermediateIn;
		} else {
			alphaCopy = intermediateIn.extractAlpha();
		}

		Allocation inAllocation = Allocation.createFromBitmap(RS, intermediateIn);
		Allocation outAllocation = Allocation.createFromBitmap(RS, intermediateOut);

		blur.setInput(inAllocation);
		blur.setRadius(radius);

		blur.forEach(outAllocation);
		outAllocation.copyTo(intermediateOut);

		inAllocation.destroy();
		outAllocation.destroy();

		if (alphaCopy != null) {
			int[] alphaLine = new int[w];
			int[] colorLine = new int[w];
			for(int y = 0; y < h; y++) {
				alphaCopy.getPixels(alphaLine, 0, w, 0, y, w, 1);
				intermediateOut.getPixels(colorLine, 0, w, 0, y, w, 1);
				for(int x = 0; x < w; x++) {
					colorLine[x] = (colorLine[x] & 0xFFFFFF) | (alphaLine[x] & 0xFF000000);
				}
				intermediateOut.setPixels(colorLine, 0, w, 0, y, w, 1);
			}
		}
		if (intermediateOut != dst) {
			Canvas canvas = new Canvas(dst);
			Rect rect = new Rect(0, 0, dst.getWidth(), dst.getHeight());
			canvas.drawBitmap(intermediateOut, null, rect, null);
			if (intermediateOut != src) {
				intermediateOut.recycle();
			}
		}
		if (intermediateIn != src && intermediateIn != dst) {
			intermediateIn.recycle();
		}
		if (alphaCopy != null && alphaCopy != intermediateIn) {
			alphaCopy.recycle();
		}
	}
}

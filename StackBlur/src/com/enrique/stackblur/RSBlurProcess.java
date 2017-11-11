package com.enrique.stackblur;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;

/**
 * @see JavaBlurProcess
 * Blur using renderscript.
 */
class RSBlurProcess implements BlurProcess {
	private final Context context;
	private final RenderScript _rs;

	public RSBlurProcess(Context context) {
		this.context = context.getApplicationContext();
		_rs = RenderScript.create(this.context);
	}

	@Override
	public void blur(Bitmap src, Bitmap dst, float radius) {
		int width = dst.getWidth();
		int height = dst.getHeight();

		Canvas canvas = new Canvas(dst);
		Rect rect = new Rect(0, 0, dst.getWidth(), dst.getHeight());
		canvas.drawBitmap(src, null, rect, null);

		ScriptC_blur blurScript = new ScriptC_blur(_rs, context.getResources(), R.raw.blur);

		Allocation inAllocation = Allocation.createFromBitmap(_rs, dst);

		blurScript.set_gIn(inAllocation);
		blurScript.set_width(width);
		blurScript.set_height(height);
		blurScript.set_radius(Math.round(radius));

		int[] row_indices = new int[height];
		for (int i = 0; i < height; i++) {
			row_indices[i] = i;
		}

		Allocation rows = Allocation.createSized(_rs, Element.U32(_rs), height, Allocation.USAGE_SCRIPT);
		rows.copyFrom(row_indices);

		row_indices = new int[width];
		for (int i = 0; i < width; i++) {
			row_indices[i] = i;
		}

		Allocation columns = Allocation.createSized(_rs, Element.U32(_rs), width, Allocation.USAGE_SCRIPT);
		columns.copyFrom(row_indices);

		blurScript.forEach_blur_h(rows);
		blurScript.forEach_blur_v(columns);
		inAllocation.copyTo(dst);
	}
}

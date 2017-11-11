package com.enrique.stackblur;

import android.graphics.Bitmap;

interface BlurProcess {
	/**
	 * Process the given image, blurring by the supplied radius.
	 * If radius is 0, no blur is performed
	 * It is valid to pass the same bitmap to src and dst.
	 *
	 * @param src    the bitmap to be blurred
	 * @param dst    the bitmap in which the blurred image should be stored
	 * @param radius the radius in pixels to blur the image
	 * @throws IllegalArgumentException if dst is not mutable or radius is negative
	 */
	void blur(Bitmap src, Bitmap dst, float radius);
}

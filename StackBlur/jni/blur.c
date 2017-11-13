#include <jni.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include <android/bitmap.h>

#define LOG_TAG "libbitmaputils"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

#define clamp(a,min,max) \
    ({__typeof__ (a) _a__ = (a); \
      __typeof__ (min) _min__ = (min); \
      __typeof__ (max) _max__ = (max); \
      _a__ < _min__ ? _min__ : _a__ > _max__ ? _max__ : _a__; })

// Based heavily on http://vitiy.info/Code/stackblur.cpp
// See http://vitiy.info/stackblur-algorithm-multi-threaded-blur-for-cpp/
// Stack Blur Algorithm by Mario Klingemann <mario@quasimondo.com>

/// Stackblur algorithm body
static void blur_line(
        uint8_t * bitmap, int32_t w, int32_t h, int32_t img_stride,
        int32_t radius, bool blurAlpha,
        int32_t line_idx, bool horizontal,
        uint8_t * in_stack
) {
    int32_t stride;
    int channels = (blurAlpha) ? 4 : 3;
    uint8_t (*stack)[channels] = (uint8_t(*)[channels]) in_stack;
    uint8_t (*dst)[4], (*src)[4], (*last)[4];
    int32_t div = radius * 2 + 1;
    int32_t div_sum = (radius + 1) * (radius + 1);
    int32_t stack_i, stack_drop;
    int32_t sum[4] = {0}, sum_out[4] = {0}, sum_in[4] = {0};
    if (horizontal) {
        stride = 1;
        dst = src = (uint8_t(*)[4])(&bitmap[img_stride * line_idx]);
        last = &src[(w - 1) * stride];
    } else {
        stride = img_stride / 4;
        dst = src = &(((uint8_t(*)[4])bitmap)[line_idx]);
        last = &src[(h - 1) * stride];
    }

    for (int i = 0; i <= radius; i++) {
        for(int j = 0; j < channels; j++) {
            uint8_t byte = (*src)[j];
            stack[i][j] = byte;
            sum[j] += byte * (i + 1);
            sum_out[j] += byte;
        }
    }
    for (int i = 1; i <= radius; i++) {
        if (src != last) {
            src += stride;
        }
        for(int j = 0; j < channels; j++) {
            stack_i = i + radius;
            uint8_t byte = (*src)[j];
            stack[stack_i][j] = byte;
            sum[j] += byte * (radius + 1 - i);
            sum_in[j] += byte;
        }
    }

    stack_i = radius;
    stack_drop = div - 1;
    while (true) {
        if (src != last) {
            src += stride;
        }

        if (stack_i == div - 1) {
            stack_i = 0;
        } else {
            stack_i += 1;
        }
        if (stack_drop == div - 1) {
            stack_drop = 0;
        } else {
            stack_drop += 1;
        }
        for(int j = 0; j < channels; j++) {
            (*dst)[j] = (uint8_t) (sum[j] / div_sum);
        }

        if (dst == last) {
            break;
        }
        dst += stride;

        for(int j = 0; j < channels; j++) {
            sum[j] -= sum_out[j];
            sum_out[j] -= stack[stack_drop][j];

            uint8_t byte = (*src)[j];
            stack[stack_drop][j] = byte;
            sum_in[j] += byte;
            sum[j] += sum_in[j];

            sum_out[j] += stack[stack_i][j];
            sum_in[j] -= stack[stack_i][j];
        }
    }
}

static int stackblurJob(
        uint8_t* src,      ///< input image data
        int32_t w,         ///< image width
        int32_t h,         ///< image height
        int32_t stride,    ///< number of bytes between rows
        int32_t radius,    ///< blur intensity
        int cores,         ///< total number of working threads
        int core,          ///< current thread number
        bool horizontal    ///< true if blur should be done horizontally
) {
    bool blur_alpha = false;
    int channels = 3 + blur_alpha;
    int32_t min;
    int32_t max;
    int32_t div = radius * 2 + 1;
    uint8_t *stack = malloc(div * channels * sizeof(uint8_t));
    if (!stack) {
        return 1;
    }

    if (horizontal) {
        min = core * h / cores;
        max = (core + 1) * h / cores;
    } else {
        min = core * w / cores;
        max = (core + 1) * w / cores;
    }

    for (int i = min; i < max; i++) {
        blur_line(src, w, h, stride, radius, blur_alpha, i, horizontal, stack);
    }

    free(stack);
    return 0;
}

void throw_oom(JNIEnv* env, const char* message) {
    jclass exClass;
    char *className = "java/lang/OutOfMemoryError";

    exClass = (*env)->FindClass( env, className );
    if (exClass) {
        (*env)->ThrowNew(env, exClass, message);
    }
}

JNIEXPORT void JNICALL Java_com_enrique_stackblur_NativeBlurProcess_functionToBlur(JNIEnv* env, jclass clzz, jobject bitmapOut, jint radius, jint threadCount, jint threadIndex, jboolean horizontal) {
    // Properties
    AndroidBitmapInfo   infoOut;
    void*               pixelsOut;

    int ret;

    // Get image info
    if ((ret = AndroidBitmap_getInfo(env, bitmapOut, &infoOut)) != 0) {
        LOGE("AndroidBitmap_getInfo() failed ! error=%d", ret);
        return;
    }

    // Check image
    if (infoOut.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap format is not RGBA_8888!");
        LOGE("==> %d", infoOut.format);
        return;
    }

    // Lock all images
    if ((ret = AndroidBitmap_lockPixels(env, bitmapOut, &pixelsOut)) != 0) {
        LOGE("AndroidBitmap_lockPixels() failed ! error=%d", ret);
        throw_oom(env, "Unable to lock pixels. Bitmap may be too large");
        return;
    }

    int h = infoOut.height;
    int w = infoOut.width;
    int stride = infoOut.stride;

    ret = stackblurJob((unsigned char*)pixelsOut, w, h, stride, radius, threadCount, threadIndex, horizontal);
    if (ret != 0) {
        LOGE("Unable to allocate stack for stackblur");
        throw_oom(env, "Unable to allocate stack for stackblur");
        return;
    }

    // Unlocks everything
    ret = AndroidBitmap_unlockPixels(env, bitmapOut);
    if (ret != 0) {
        LOGE("AndroidBitmap_unlockPixels() failed ! error=%d", ret);
        throw_oom(env, "Unable to unlock pixels. This should never happen");
        return;
    }
}

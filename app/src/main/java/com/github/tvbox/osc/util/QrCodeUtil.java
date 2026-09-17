package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

/**
 * 二维码生成工具
 */
public class QrCodeUtil {

    private QrCodeUtil() {}

    /**
     * 将文本编码为二维码 Bitmap
     *
     * @param content 文本内容（如配置网页 URL）
     * @param size    边长像素
     * @return 二维码 Bitmap，失败返回 null
     */
    public static Bitmap createQrCode(String content, int size) {
        if (content == null || content.isEmpty() || size <= 0) return null;
        // 限制尺寸上下限，避免像素数溢出或分配超大位图导致 OOM
        if (size < 64) size = 64;
        if (size > 1024) size = 1024;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new MultiFormatWriter()
                    .encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            // 用 long 计算防止整型溢出，并对总像素数设上限
            long total = (long) width * (long) height;
            if (width <= 0 || height <= 0 || total >1024L * 1024L) {
                Timber.w("二维码尺寸异常: %dx%d", width, height);
                return null;
            }
            int[] pixels = new int[(int) total];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            // 二维码为纯黑白，RGB_565 即可，内存占用约为 ARGB_8888 的一半
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Throwable e) {
            // OutOfMemoryError / NegativeArraySizeException 等均在此兜住，不让App闪退
            Timber.e(e, "生成二维码失败");
            return null;
        }
    }
}

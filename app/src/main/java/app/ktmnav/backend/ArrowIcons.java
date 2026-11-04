package app.ktmnav.backend;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Utility for extracting 48x48 pixel arrays from navigation notification icons and saving diagnostic captures.
 * Based on TurnIt ArrowIcons evidence.
 */
public final class ArrowIcons {
    private static final String TAG = "KTMnav.ArrowIcons";
    public static final int SAMPLE_SIZE = 48;

    private ArrowIcons() {
    }

    public static int[] loadArrowPixels(Context context, StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return null;
        }
        try {
            Icon largeIcon = sbn.getNotification().getLargeIcon();
            if (largeIcon == null) {
                return null;
            }
            Drawable drawable = largeIcon.loadDrawable(context);
            if (drawable == null) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
            drawable.draw(canvas);

            int[] pixels = new int[SAMPLE_SIZE * SAMPLE_SIZE];
            bitmap.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
            bitmap.recycle();
            return pixels;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract arrow icon pixels", e);
            return null;
        }
    }

    public static Bitmap toBitmap(int[] pixels) {
        if (pixels == null || pixels.length != SAMPLE_SIZE * SAMPLE_SIZE) {
            return null;
        }
        return Bitmap.createBitmap(pixels, SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
    }

    public static void savePng(File file, int[] pixels) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }
        if (pixels == null || pixels.length != SAMPLE_SIZE * SAMPLE_SIZE) {
            throw new IllegalArgumentException("Pixel array must have exactly " + (SAMPLE_SIZE * SAMPLE_SIZE) + " elements");
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
    }
}

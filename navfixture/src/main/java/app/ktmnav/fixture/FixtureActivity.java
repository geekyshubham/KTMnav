package app.ktmnav.fixture;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/** Synthetic producer for emulator tests; package identity is deliberate to exercise source routing. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("fixture", "Synthetic navigation fixture", NotificationManager.IMPORTANCE_LOW));
        if ("clear".equals(getIntent().getStringExtra("action"))) { manager.cancel(42); finish(); return; }
        Bitmap arrow = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(arrow); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(8); paint.setStrokeJoin(Paint.Join.MITER);
        Path p = new Path();p.moveTo(18,58);p.lineTo(18,23);p.lineTo(53,23);p.moveTo(38,8);p.lineTo(53,23);p.lineTo(38,38);canvas.drawPath(p,paint);
        Notification notification = new Notification.Builder(this,"fixture")
            .setContentTitle("250 m").setContentText("Lakeside Road")
            .setSubText("10:42 · 12.4 km · 24 min").setCategory(Notification.CATEGORY_NAVIGATION)
            .setSmallIcon(Icon.createWithBitmap(arrow)).setLargeIcon(arrow)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
        manager.notify(42,notification); finish();
    }
}

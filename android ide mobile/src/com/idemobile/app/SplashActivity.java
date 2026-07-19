package com.idemobile.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/**
 * Стартовый сплеш-экран.
 *
 * Тема SplashTheme мгновенно рисует фон splash_bg (bitmap gravity="fill") ещё до
 * onCreate — картинка сразу на весь экран. Затем layout activity_splash показывает
 * тот же ImageView в режиме fitXY, который совпадает с фоном темы, поэтому НЕТ
 * «скачка» с маленькой картинки на полноэкранную: оба кадра идентичны.
 */
public class SplashActivity extends Activity {

    private static final long SPLASH_DELAY_MS = 1600L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Совпадение с фоном темы (fill): растягиваем на весь экран, без скачка.
        ImageView img = (ImageView) findViewById(R.id.splash_image);
        if (img != null) img.setScaleType(ImageView.ScaleType.FIT_XY);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        }, SPLASH_DELAY_MS);
    }
}

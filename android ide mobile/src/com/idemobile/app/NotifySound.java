package com.idemobile.app;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;

/**
 * Звук уведомления «агент прислал ответ».
 *
 * Источник звука — файл ресурсов res/raw/notify.mp3. Чтобы поставить свой сигнал,
 * достаточно заменить этот mp3-файл (имя должно остаться notify.mp3) и пересобрать.
 * Воспроизведение идёт через MediaPlayer с потоком STREAM_MUSIC; громкость задаётся
 * долей 0..1 (из настройки notifyVolume) и применяется к самому плееру.
 */
final class NotifySound {

    private NotifySound() {}

    /**
     * Проиграть звук уведомления один раз.
     *
     * @param ctx         любой Context (можно null — тогда берётся контекст приложения)
     * @param volFraction громкость 0..1
     */
    static void play(Context ctx, double volFraction) {
        try {
            if (ctx == null) ctx = App.ctx();
            if (ctx == null) return;

            float vol = (float) Math.max(0f, Math.min(1f, volFraction));

            final MediaPlayer mp = MediaPlayer.create(ctx, R.raw.notify);
            if (mp == null) return;

            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setVolume(vol, vol);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) {
                    try { m.release(); } catch (Exception ignored) {}
                }
            });
            mp.start();
        } catch (Exception ignored) {
            // Звук — не критичная функция: сбои воспроизведения игнорируем.
        }
    }
}

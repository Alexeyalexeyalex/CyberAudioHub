package com.cyberaudio.hub;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения — те, что человек меняет руками через шестерёнку.
 *
 * Всё лежит в тех же SharedPreferences, что адрес сервера и вход: файл один
 * на приложение, и заводить ради двух флажков отдельный незачем.
 */
public final class Settings {

    private static final String PREFS = "cyberaudio";
    private static final String NO_IMAGES = "no_images";

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Не показывать обложки. По умолчанию выключено: картинки — половина
     * того, за что полку узнают с одного взгляда. Включают это те, кто
     * слушает с мобильного интернета и считает каждый мегабайт.
     */
    public static boolean noImages(Context context) {
        return prefs(context).getBoolean(NO_IMAGES, false);
    }

    public static void setNoImages(Context context, boolean on) {
        prefs(context).edit().putBoolean(NO_IMAGES, on).apply();
    }
}

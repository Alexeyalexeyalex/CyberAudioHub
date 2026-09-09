package com.cyberaudio.hub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Оформление под сайт: те же цвета, тот же неон.
 *
 * Кнопки плоские, без обводок. Рамка вокруг каждого элемента дробила экран
 * на клетки; форму задаёт заливка, а отклик на касание — подсветка. Так
 * рисуют управление современные плееры, и на телефоне это читается лучше.
 *
 * Экраны собираются кодом, без XML-разметки: экранов три, стиль у всех
 * один и держится на нескольких помощниках отсюда. В XML это растеклось бы
 * по десятку файлов, и любая правка цвета шла бы по всем.
 */
public final class Ui {

    // Те же значения, что в static/css/style.css, блок :root
    public static final int PRIMARY = Color.parseColor("#00F2FF");
    public static final int SECONDARY = Color.parseColor("#E6ACF0");
    public static final int DARK = Color.parseColor("#0D041A");
    public static final int SURFACE = Color.parseColor("#1D162B");
    public static final int TEXT = Color.parseColor("#F0EEF7");
    public static final int DIM = Color.parseColor("#A7A1B8");
    /**
     * Уже прочитанный текст. На сайте это .word.is-spoken с прозрачностью
     * 0.45; здесь тот же цвет, посчитанный поверх тёмного фона, — гасить
     * прозрачностью пришлось бы каждое слово отдельным пролётом по строке.
     */
    public static final int SPOKEN = Color.parseColor("#6F6B7A");

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, context.getResources().getDisplayMetrics()));
    }

    private static int fade(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Отклик на касание: без него плоская кнопка кажется неживой. */
    private static Drawable ripple(Drawable base, int accent) {
        return new RippleDrawable(
                ColorStateList.valueOf(fade(accent, 70)), base, null);
    }

    /**
     * Подложка карточки. Обводки нет намеренно: рамка вокруг каждой строки
     * превращала список в таблицу. Форму задаёт заливка.
     */
    public static GradientDrawable card(Context context, int unusedStroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(SURFACE);
        shape.setCornerRadius(dp(context, 20));
        return shape;
    }

    public static Button button(Context context, String text, int accent) {
        return button(context, text, accent, false);
    }

    /**
     * Кнопка без обводки: заливка и скруглённые края.
     *
     * filled = true — главное действие: сплошной цвет и тёмная подпись.
     * Высота не меньше 48dp: по кнопке должно быть можно попасть пальцем.
     */
    public static Button button(Context context, String text, int accent,
                                boolean filled) {
        Button view = new Button(context);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(15);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setMinHeight(dp(context, 48));
        view.setStateListAnimator(null);      // без «подпрыгивания» при нажатии

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 14));
        if (filled) {
            shape.setColor(accent);
            view.setTextColor(DARK);
        } else {
            // Приглушённая заливка вместо контура: кнопку видно,
            // но она не спорит с содержимым экрана
            shape.setColor(fade(accent, 30));
            view.setTextColor(accent);
        }
        view.setBackground(ripple(shape, accent));
        view.setPadding(dp(context, 18), dp(context, 10),
                dp(context, 18), dp(context, 10));
        return view;
    }

    public static ImageButton roundButton(Context context, int icon, int accent,
                                          int sizeDp, String description) {
        return roundButton(context, icon, accent, sizeDp, description, false);
    }

    /**
     * Круглая кнопка плеера.
     *
     * Боковые — просто значок без подложки: так их рисуют современные
     * плееры, и ряд перестаёт выглядеть забором из кружков. Главная
     * (Play) остаётся заметной: сплошной круг цвета акцента.
     */
    public static ImageButton roundButton(Context context, int icon, int accent,
                                          int sizeDp, String description,
                                          boolean filled) {
        ImageButton view = new ImageButton(context);
        view.setImageResource(icon);
        view.setContentDescription(description);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setStateListAnimator(null);

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        if (filled) {
            shape.setColor(accent);
            view.setColorFilter(DARK);        // значок на цветном круге
        } else {
            shape.setColor(Color.TRANSPARENT);
            view.setColorFilter(accent);
        }
        view.setBackground(ripple(shape, accent));

        int size = dp(context, Math.max(48, sizeDp));
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        int pad = dp(context, filled ? 18 : 12);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    public static EditText field(Context context, String hint) {
        EditText view = new EditText(context);
        view.setHint(hint);
        view.setHintTextColor(DIM);
        view.setTextColor(TEXT);
        view.setTextSize(16);              // мельче — и телефон приблизит экран
        view.setMinHeight(dp(context, 48));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(SURFACE);
        shape.setCornerRadius(dp(context, 14));
        view.setBackground(shape);
        view.setPadding(dp(context, 14), dp(context, 10),
                dp(context, 14), dp(context, 10));
        return view;
    }

    /** Галочка в стиле сайта: подпись цвета текста, сам флажок — акцентом. */
    public static android.widget.CheckBox check(Context context, String text,
                                                boolean on) {
        android.widget.CheckBox view = new android.widget.CheckBox(context);
        view.setText(text);
        view.setChecked(on);
        view.setTextColor(TEXT);
        view.setTextSize(14);
        view.setMinHeight(dp(context, 44));   // попасть пальцем, не целясь
        view.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        return view;
    }

    public static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(28);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(-0.025f);
        view.setAllCaps(false);
        return view;
    }

    public static TextView label(Context context, String text, int color, float size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    /** Вертикальная колонка с полями — основа всех экранов. */
    public static LinearLayout column(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(DARK);
        int pad = dp(context, 16);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    public static LinearLayout row(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    public static GradientDrawable hero(Context context) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#34203F"), Color.parseColor("#1C1730")});
        shape.setCornerRadius(dp(context, 24));
        return shape;
    }

    /** Нативная нижняя навигация с постоянными, подписанными разделами. */
    public static com.google.android.material.bottomnavigation.BottomNavigationView navigation(
            android.app.Activity activity, int selected) {
        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                new com.google.android.material.bottomnavigation.BottomNavigationView(activity);
        nav.setBackgroundColor(SURFACE);
        nav.setElevation(dp(activity, 8));
        nav.setLabelVisibilityMode(1);
        String[] labels = {"Медиатека", "Папки", "Скачано", "Профиль"};
        for (int i = 0; i < labels.length; i++) {
            nav.getMenu().add(0, i + 1, i, labels[i]).setIcon(new HubIcon(i, DIM, dp(activity, 24)));
        }
        ColorStateList tint = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{PRIMARY, DIM});
        nav.setItemIconTintList(tint);
        nav.setItemTextColor(tint);
        nav.setItemActiveIndicatorColor(ColorStateList.valueOf(Color.parseColor("#263B46")));
        nav.setSelectedItemId(selected + 1);
        nav.setOnItemSelectedListener(item -> {
            int target = item.getItemId() - 1;
            if (target == selected) return true;
            Class<?> screen = target == 1 ? FoldersActivity.class
                    : target == 3 ? ProfileActivity.class : ShelfActivity.class;
            android.content.Intent intent = new android.content.Intent(activity, screen);
            if (target == 2) intent.putExtra(ShelfActivity.EXTRA_OFFLINE, true);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(intent);
            return true;
        });
        return nav;
    }

    public static void screen(android.app.Activity activity, View content, int tab) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);
        root.setFitsSystemWindows(true);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (tab >= 0) root.addView(navigation(activity, tab), new LinearLayout.LayoutParams(-1, -2));
        activity.setContentView(root);
    }

    /** Добавляет ребёнка с отступом снизу — чтобы не плодить LayoutParams. */
    public static void add(ViewGroup parent, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(parent.getContext(), marginDp);
        parent.addView(child, params);
    }

    /** Время в виде 12:34 или 1:02:03. */
    public static String time(int millis) {
        int total = Math.max(0, millis) / 1000;
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int seconds = total % 60;
        // Locale.ROOT: на телефоне с арабскими цифрами время должно
        // читаться теми же знаками, что человек ждёт увидеть в плеере
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                        hours, minutes, seconds)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}

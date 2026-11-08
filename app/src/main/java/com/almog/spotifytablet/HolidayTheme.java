package com.almog.spotifytablet;

import android.icu.util.HebrewCalendar;

import java.util.Calendar;

/**
 * Which Jewish or Israeli holiday it is, for the clock screen. Dates come from Android's own Hebrew calendar (no network).
 * The Hebrew day starts at nightfall, so from 18:00 the next day counts (the Hanukkah candles are lit that evening).
 * Memorial and independence days are by their plain dates; the years when they move by a day are not handled.
 */
public final class HolidayTheme {
    private HolidayTheme() {}

    public static final class Holiday {
        public final String text;   // one or two lines shown above the clock
        public final int color;     // ARGB
        Holiday(String text, int color) { this.text = text; this.color = color; }
    }

    /** The holiday for [nowMs], or null on an ordinary day. */
    public static Holiday forTime(long nowMs) {
        Calendar greg = Calendar.getInstance();
        greg.setTimeInMillis(nowMs);
        if (greg.get(Calendar.HOUR_OF_DAY) >= 18) greg.add(Calendar.DAY_OF_MONTH, 1);

        HebrewCalendar h = new HebrewCalendar();
        h.setTimeInMillis(greg.getTimeInMillis());
        int month = h.get(HebrewCalendar.MONTH);
        int day = h.get(HebrewCalendar.DAY_OF_MONTH);

        // Hanukkah: the 25th of Kislev and the seven days after it; one more candle each night.
        if (month == HebrewCalendar.KISLEV || month == HebrewCalendar.TEVET) {
            HebrewCalendar first = new HebrewCalendar();
            first.setTimeInMillis(greg.getTimeInMillis());
            first.set(HebrewCalendar.MONTH, HebrewCalendar.KISLEV);
            first.set(HebrewCalendar.DAY_OF_MONTH, 25);
            int nights = h.get(HebrewCalendar.DAY_OF_YEAR) - first.get(HebrewCalendar.DAY_OF_YEAR) + 1;
            if (nights >= 1 && nights <= 8) {
                StringBuilder candles = new StringBuilder();
                for (int i = 0; i < nights; i++) candles.append("🕯");
                return new Holiday("🕎  חנוכה שמח\n" + candles, 0xFF7FB2FF);
            }
        }
        if (month == HebrewCalendar.TISHRI) {
            if (day <= 2) return new Holiday("🍎🍯  שנה טובה", 0xFFFFB347);
            if (day == 10) return new Holiday("גמר חתימה טובה", 0xFFFFFFFF);
            if (day >= 15 && day <= 21) return new Holiday("🌿  סוכות שמח", 0xFF8BC34A);
            if (day == 22 || day == 23) return new Holiday("📜  שמחת תורה", 0xFFFFD166);
        }
        if (month == HebrewCalendar.ADAR && day == 14) return new Holiday("🎭  פורים שמח", 0xFFFF7ACB);
        if (month == HebrewCalendar.NISAN && day >= 15 && day <= 21) return new Holiday("🍷  פסח שמח", 0xFFE6CF8A);
        if (month == HebrewCalendar.IYAR && day == 4) return new Holiday("🕯  יום הזיכרון", 0xFFB8B8B8);
        if (month == HebrewCalendar.IYAR && day == 5) return new Holiday("🇮🇱  יום העצמאות שמח", 0xFF6DB3FF);
        if (month == HebrewCalendar.SIVAN && day == 6) return new Holiday("🌾  חג שבועות שמח", 0xFFA6E3A1);
        return null;
    }
}

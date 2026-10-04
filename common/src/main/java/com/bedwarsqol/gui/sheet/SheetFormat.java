package com.bedwarsqol.gui.sheet;

import java.util.Locale;

/** Value text as the prototype prints it ({@code Number(v).toFixed(dp)} plus the unit). */
public final class SheetFormat {

    private SheetFormat() {
    }

    public static String number(SheetSchema.Row r, double v) {
        String s = String.format(Locale.US, "%." + r.decimals() + "f", v);
        if (s.startsWith("-") && s.matches("-0\\.?0*")) s = s.substring(1);
        return s + r.unit();
    }

    /** Snap a slider value to its step and range, as the prototype's {@code slVal}. */
    public static double snap(SheetSchema.Row r, double v) {
        v = Math.round(v / r.step()) * r.step();
        v = Math.min(r.max(), Math.max(r.min(), v));
        double scale = Math.pow(10, r.decimals());
        return Math.round(v * scale) / scale;
    }

    /** The value at fraction {@code p} (0..1) of a slider's range, snapped. */
    public static double atFraction(SheetSchema.Row r, double p) {
        p = Math.min(1, Math.max(0, p));
        return snap(r, r.min() + p * (r.max() - r.min()));
    }

    public static double fraction(SheetSchema.Row r, double v) {
        return (v - r.min()) / (r.max() - r.min());
    }
}

package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.gui.sheet.SheetSchema.Category;
import com.bedwarsqol.gui.sheet.SheetSchema.Module;
import com.bedwarsqol.gui.sheet.SheetSchema.Row;
import com.bedwarsqol.gui.sheet.SheetSchema.Section;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The sheet's search, as the prototype's: a case-insensitive substring of module names, descriptions and keywords,
 * and of row names, descriptions, keywords and option names. Modules whose name matches come first.
 */
public final class SheetSearch {

    private SheetSearch() {
    }

    public static final class Hit {
        public final Module module;
        public final Section section;
        /** The rows that matched, in schema order. */
        public final Set<Row> rows = new LinkedHashSet<Row>();
        public final boolean moduleHit;
        final int score;

        Hit(Module module, Section section, boolean moduleHit, int score) {
            this.module = module;
            this.section = section;
            this.moduleHit = moduleHit;
            this.score = score;
        }
    }

    private static boolean match(String text, String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q);
    }

    public static List<Hit> search(String query, SheetValues values) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Hit> out = new ArrayList<Hit>();
        if (q.isEmpty()) return out;
        for (Category c : SheetSchema.CATS) {
            for (Module m : c.modules) {
                if (!values.has(m.id)) continue;
                boolean modHit = match(m.name, q) || match(m.desc, q) || match(m.kw, q);
                Hit h = new Hit(m, null, modHit, match(m.name, q) ? 0 : 1);
                for (Row r : m.rows) {
                    if (!values.has(r.id)) continue;
                    boolean hit = match(r.name, q) || match(r.desc, q) || match(r.kw, q);
                    for (String o : r.options()) hit |= match(o, q);
                    if (hit) h.rows.add(r);
                }
                if (modHit || !h.rows.isEmpty()) out.add(h);
            }
            for (Section s : c.sections) {
                Hit h = new Hit(null, s, false, 1);
                for (Row r : s.rows) {
                    if (!values.has(r.id)) continue;
                    if (match(r.name, q) || match(r.desc, q) || match(r.kw, q)) h.rows.add(r);
                }
                if (!h.rows.isEmpty()) out.add(h);
            }
        }
        // stable: name matches first, schema order otherwise
        List<Hit> sorted = new ArrayList<Hit>();
        for (Hit h : out) if (h.score == 0) sorted.add(h);
        for (Hit h : out) if (h.score != 0) sorted.add(h);
        return sorted;
    }

    /** The "N matches" count: a module that matched only by itself counts once, else each matched row. */
    public static int count(List<Hit> hits) {
        int n = 0;
        for (Hit h : hits) n += h.module != null && h.moduleHit && h.rows.isEmpty() ? 1 : h.rows.size();
        return n;
    }

    /** Index of the first case-insensitive occurrence of the trimmed query in {@code text}, or -1. */
    public static int markAt(String text, String query) {
        if (query == null) return -1;
        String q = query.trim();
        if (q.isEmpty()) return -1;
        return text.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));
    }
}

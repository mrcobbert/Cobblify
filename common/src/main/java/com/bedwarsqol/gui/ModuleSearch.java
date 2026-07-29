package com.bedwarsqol.gui;

import java.util.List;

/**
 * Fuzzy subsequence matcher for the module Search page. Case-insensitive: a query matches a field if
 * its characters appear in order (not necessarily contiguous), like fzf / Sublime "Goto Anything".
 * Scoring follows Forrest Smith's fts_fuzzy_match (word-boundary, CamelCase and consecutive-run
 * bonuses, leading/unmatched penalties) so the best, tightest matches rank first.
 * See https://www.forrestthewoods.com/blog/reverse_engineering_sublime_texts_fuzzy_match/
 */
final class ModuleSearch {

    private ModuleSearch() {
    }

    static final int NO_MATCH = Integer.MIN_VALUE;

    private static final int SEQUENTIAL_BONUS = 15;     // adjacent matched chars
    private static final int SEPARATOR_BONUS = 30;      // match right after a space / underscore
    private static final int CAMEL_BONUS = 30;          // lowercase -> Uppercase boundary
    private static final int FIRST_LETTER_BONUS = 15;   // first char of the field matched
    private static final int LEADING_LETTER_PENALTY = -5;
    private static final int MAX_LEADING_LETTER_PENALTY = -15;
    private static final int UNMATCHED_LETTER_PENALTY = -1;

    /**
     * Subsequence score of {@code query} within {@code field} (case-insensitive), or {@link #NO_MATCH}
     * if {@code query} is not a subsequence. Fills {@code outMatches} with the matched indices into
     * {@code field} (cleared first) so the caller can highlight them. An empty query scores 0.
     */
    static int scoreField(String query, String field, List<Integer> outMatches) {
        outMatches.clear();
        if (query.isEmpty()) return 0;
        if (field == null || field.isEmpty()) return NO_MATCH;

        int pi = 0, si = 0;
        while (pi < query.length() && si < field.length()) {
            if (Character.toLowerCase(query.charAt(pi)) == Character.toLowerCase(field.charAt(si))) {
                outMatches.add(si);
                pi++;
            }
            si++;
        }
        if (pi != query.length()) { // not a subsequence
            outMatches.clear();
            return NO_MATCH;
        }

        int score = 100;
        int first = outMatches.get(0);
        score += Math.max(MAX_LEADING_LETTER_PENALTY, LEADING_LETTER_PENALTY * first);
        score += UNMATCHED_LETTER_PENALTY * (field.length() - outMatches.size());
        for (int k = 0; k < outMatches.size(); k++) {
            int i = outMatches.get(k);
            if (k > 0 && i == outMatches.get(k - 1) + 1) score += SEQUENTIAL_BONUS;
            if (i == 0) {
                score += FIRST_LETTER_BONUS;
            } else {
                char prev = field.charAt(i - 1), cur = field.charAt(i);
                if (Character.isLowerCase(prev) && Character.isUpperCase(cur)) score += CAMEL_BONUS;
                if (prev == ' ' || prev == '_') score += SEPARATOR_BONUS;
            }
        }
        return score;
    }
}

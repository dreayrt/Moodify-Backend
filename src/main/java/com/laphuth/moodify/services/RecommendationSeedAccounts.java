package com.laphuth.moodify.services;

import java.util.Locale;

/** Reserved synthetic cohort; never represent these accounts as genuine listeners. */
public final class RecommendationSeedAccounts {
    private RecommendationSeedAccounts() {}
    public static boolean isSynthetic(String username,String email) {
        return username!=null&&email!=null&&username.toLowerCase(Locale.ROOT).startsWith("rec_seed_")&&
            email.toLowerCase(Locale.ROOT).endsWith("@recommendation-seed.invalid");
    }
}

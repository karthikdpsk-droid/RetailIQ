package com.retailiq.api.ml;

import java.util.List;
import java.util.Locale;

/** Family labels present in the active forecasting model's fitted categorical encoder. */
public final class SupportedMlFamilies {
    public static final List<String> CODES = List.of(
            "AUTOMOTIVE", "BABY CARE", "BEAUTY", "BEVERAGES", "BOOKS", "BREAD/BAKERY",
            "CELEBRATION", "CLEANING", "DAIRY", "DELI", "EGGS", "FROZEN FOODS",
            "GROCERY I", "GROCERY II", "HARDWARE", "HOME AND KITCHEN I", "HOME AND KITCHEN II",
            "HOME APPLIANCES", "HOME CARE", "LADIESWEAR", "LAWN AND GARDEN", "LINGERIE",
            "LIQUOR,WINE,BEER", "MAGAZINES", "MEATS", "PERSONAL CARE", "PET SUPPLIES",
            "PLAYERS AND ELECTRONICS", "POULTRY", "PREPARED FOODS", "PRODUCE",
            "SCHOOL AND OFFICE SUPPLIES", "SEAFOOD");

    private SupportedMlFamilies() {}

    public static boolean contains(String familyCode) {
        return familyCode != null && CODES.contains(familyCode.trim().toUpperCase(Locale.ROOT));
    }
}

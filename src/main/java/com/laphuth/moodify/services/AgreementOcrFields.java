package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.contract.AgreementDetails;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AgreementOcrFields {
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("d/M/uuuu")
        .withResolverStyle(ResolverStyle.STRICT);

    private AgreementOcrFields() {}

    /** Extract only values explicitly labeled on the same line. Conflicting labels need manual review. */
    static AgreementDetails parse(String text) {
        return parse(labels(text));
    }

    static Map<String, String> labels(String text) {
        if (text == null || text.isBlank()) return Map.of();
        Map<String, String> values = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.replace('\uFF1A', ':');
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            String key = normalize(line.substring(0, separator));
            String value = line.substring(separator + 1).strip();
            if (value.isBlank() || value.matches("[\\s_.–—-]+")) continue;
            String previous = values.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) conflicting.add(key);
        }
        conflicting.forEach(values::remove);
        return values;
    }

    static AgreementDetails parse(Map<String, String> values) {
        AgreementDetails details = new AgreementDetails(
            field(values, "ten ben cap quyen", 200), field(values, "dia chi ben cap quyen", 500),
            field(values, "nguoi dai dien ben cap quyen", 200), field(values, "chuc vu ben cap quyen", 120),
            field(values, "ten ben nhan quyen", 200), field(values, "dia chi ben nhan quyen", 500),
            field(values, "nguoi dai dien ben nhan quyen", 200), field(values, "chuc vu ben nhan quyen", 120),
            field(values, "dia diem du kien ky", 200), date(field(values, "ngay du kien ky", 10))
        );
        return details.isEmpty() ? null : details;
    }

    static String field(Map<String, String> values, String key, int length) {
        String value = values.get(key);
        return value == null || value.length() > length || value.codePoints().anyMatch(Character::isISOControl)
            ? null : value;
    }

    private static String date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value, LOCAL_DATE).toString();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static String normalize(String raw) {
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace('đ', 'd')
            .replace('\u00A0', ' ').strip().replaceAll("\\s+", " ");
    }
}

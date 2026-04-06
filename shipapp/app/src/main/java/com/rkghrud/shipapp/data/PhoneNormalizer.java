package com.rkghrud.shipapp.data;

public final class PhoneNormalizer {
    private PhoneNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }

        String digits = raw.replaceAll("[^\\d]", "");
        if (digits.startsWith("8210")) {
            digits = "0" + digits.substring(2);
        } else if (digits.startsWith("82") && digits.length() >= 11) {
            digits = "0" + digits.substring(2);
        } else if (digits.startsWith("10") && digits.length() == 10) {
            digits = "0" + digits;
        }

        return digits;
    }
}

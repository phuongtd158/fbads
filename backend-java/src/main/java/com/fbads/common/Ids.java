package com.fbads.common;

import java.security.SecureRandom;

/** Mã ngắn cho lịch, rule, dòng nhật ký (8 ký tự a-z0-9, giống bản Node). */
public final class Ids {
    private static final SecureRandom RND = new SecureRandom();
    private static final String ABC = "abcdefghijklmnopqrstuvwxyz0123456789";

    private Ids() {}

    public static String uid() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ABC.charAt(RND.nextInt(ABC.length())));
        return sb.toString();
    }
}

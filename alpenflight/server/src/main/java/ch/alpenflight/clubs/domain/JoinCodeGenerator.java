package ch.alpenflight.clubs.domain;

import java.security.SecureRandom;

public interface JoinCodeGenerator {

    int LENGTH = 8;

    String ALPHABET_WITHOUT_AMBIGUOUS_GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    String generate();

    static JoinCodeGenerator secureRandom() {
        SecureRandom random = new SecureRandom();
        return () -> {
            char[] out = new char[LENGTH];
            for (int i = 0; i < LENGTH; i++) {
                out[i] = ALPHABET_WITHOUT_AMBIGUOUS_GLYPHS.charAt(random.nextInt(ALPHABET_WITHOUT_AMBIGUOUS_GLYPHS.length()));
            }
            return new String(out);
        };
    }
}

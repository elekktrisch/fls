package ch.alpenflight.clubs.domain;

import java.security.SecureRandom;

/**
 * Mints a club join code — a short, human-shareable discovery key a pilot
 * types to file a request to join (S-177/S-178). The code is a quasi-secret,
 * not an authorization token: it resolves to one Club, but admission is still
 * the admin's approval.
 *
 * <p>Domain port (ADR 0023) so {@link Club#rotateJoinCode} mints without
 * depending on a concrete RNG; the Spring-managed {@code SecureRandom}-backed
 * implementation lives in {@code clubs.infra}.
 */
public interface JoinCodeGenerator {

    /** Code length — 8 chars over {@link #ALPHABET} carries ~40 bits of entropy. */
    int LENGTH = 8;

    /**
     * The 31-char alphabet, ambiguous glyphs ({@code I L O 0 1}) stripped so a
     * code dictated over the phone or read off a screen can't be mistyped.
     */
    String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** A fresh {@value #LENGTH}-char code drawn uniformly from {@link #ALPHABET}. */
    String generate();

    /**
     * A standalone {@link SecureRandom}-backed generator, so {@link Club#create}
     * can satisfy the NOT-NULL join-code invariant at construction without an
     * injected bean. The {@code ux_club_join_code} index is the global-uniqueness
     * backstop at creation; explicit rotation goes through the injected port
     * with a collision-retry.
     */
    static JoinCodeGenerator secureRandom() {
        SecureRandom random = new SecureRandom();
        return () -> {
            char[] out = new char[LENGTH];
            for (int i = 0; i < LENGTH; i++) {
                out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
            }
            return new String(out);
        };
    }
}

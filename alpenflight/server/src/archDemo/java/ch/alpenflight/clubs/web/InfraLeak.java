package ch.alpenflight.clubs.web;

import ch.alpenflight.clubs.infra.JpaClubRepository;

/**
 * Deliberate ADR 0023 violation: a class in a {@code ..web..} package
 * depends on a class in a {@code ..infra..} package — Rule 2 forbids
 * the web layer from reaching past application into infra. Lives in the
 * {@code archDemo} source set; {@code verifyArchUnitFailsOnViolation}
 * asserts Rule 2 fires on this class.
 */
public class InfraLeak {

    @SuppressWarnings("unused")
    private final JpaClubRepository repository;

    public InfraLeak(JpaClubRepository repository) {
        this.repository = repository;
    }
}

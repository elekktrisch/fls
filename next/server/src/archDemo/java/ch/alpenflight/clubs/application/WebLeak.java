package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.web.ClubsController;

/**
 * Deliberate ADR 0023 violation: a class in a {@code ..application..}
 * package depends on a class in a {@code ..web..} package — Rule 3
 * forbids the use-case layer from depending on the inbound adapter.
 * Lives in the {@code archDemo} source set;
 * {@code verifyArchUnitFailsOnViolation} asserts Rule 3 fires on this
 * class.
 */
public class WebLeak {

    @SuppressWarnings("unused")
    private final ClubsController controller;

    public WebLeak(ClubsController controller) {
        this.controller = controller;
    }
}

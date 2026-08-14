package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.web.ClubsController;

public class WebLeak {

    @SuppressWarnings("unused")
    private final ClubsController controller;

    public WebLeak(ClubsController controller) {
        this.controller = controller;
    }
}

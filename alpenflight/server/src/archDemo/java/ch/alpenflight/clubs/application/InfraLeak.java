package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.infra.JpaClubRepository;

public class InfraLeak {

    @SuppressWarnings("unused")
    private final JpaClubRepository repository;

    public InfraLeak(JpaClubRepository repository) {
        this.repository = repository;
    }
}

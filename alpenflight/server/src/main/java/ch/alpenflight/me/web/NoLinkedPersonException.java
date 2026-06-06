package ch.alpenflight.me.web;

/**
 * Thrown by the {@code /api/v1/me/person*} self-edit surfaces when the
 * authenticated caller's {@code t_user} row has no linked Person
 * ({@code person_id} is null). The SPA shell already gates the Personal /
 * Pilot / Notifications tabs behind {@code hasPerson}, but the endpoint must
 * be safe on its own — a direct call with no linked Person resolves to a clean
 * {@code 409 Conflict} ("ask your club admin to link your member record")
 * rather than a 500. Mapped by {@link MePersonExceptionHandler}.
 */
class NoLinkedPersonException extends RuntimeException {

    NoLinkedPersonException() {
        super("The authenticated principal has no linked Person record");
    }
}

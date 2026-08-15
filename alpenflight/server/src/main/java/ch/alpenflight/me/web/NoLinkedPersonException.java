package ch.alpenflight.me.web;

class NoLinkedPersonException extends RuntimeException {

    NoLinkedPersonException() {
        super("The authenticated principal has no linked Person record");
    }
}

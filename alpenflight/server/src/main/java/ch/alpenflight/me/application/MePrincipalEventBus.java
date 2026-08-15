package ch.alpenflight.me.application;

public interface MePrincipalEventBus {

    void publish(String sub, String kind, Object payload);
}

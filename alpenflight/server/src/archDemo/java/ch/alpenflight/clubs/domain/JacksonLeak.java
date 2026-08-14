package ch.alpenflight.clubs.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JacksonLeak {

    @SuppressWarnings("unused")
    @JsonProperty("name")
    private String name = "";
}

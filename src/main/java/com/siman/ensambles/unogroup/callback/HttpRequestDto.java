package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/** Espejo de salida de service.HttpRequestRegistrado — HttpRequest del contrato. */
@Getter
@Builder
public class HttpRequestDto {
    private String method;
    private String url;
    private Instant timestamp;
    private String contentType;
    private Map<String, String> headers;
    private String body;
}

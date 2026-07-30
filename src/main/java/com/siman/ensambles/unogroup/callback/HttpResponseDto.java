package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/** Espejo de salida de service.HttpResponseRegistrado — HttpResponse del contrato. */
@Getter
@Builder
public class HttpResponseDto {
    private int statusCode;
    private Instant timestamp;
    private Integer durationMs;
    private String contentType;
    private Map<String, String> headers;
    private String body;
}

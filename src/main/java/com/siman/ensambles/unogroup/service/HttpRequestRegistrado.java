package com.siman.ensambles.unogroup.service;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Contenido real de una request HTTP saliente, ya capturado y enmascarado
 * por {@code client.CapturingFeignClient} — espejo de {@code HttpRequest}
 * del contrato (Guía de Transacciones HTTP §3/§4).
 */
@Getter
@Builder
public class HttpRequestRegistrado {
    private String method;
    private String url;                 // ya enmascarada (query string)
    private Instant timestamp;
    private String contentType;
    private Map<String, String> headers; // ya enmascarados
    private String body;                 // SIN enmascarar (limitación conocida v3)
}

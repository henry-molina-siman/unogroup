package com.siman.ensambles.unogroup.service;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Contenido real de una respuesta HTTP recibida, ya capturado y enmascarado
 * por {@code client.CapturingFeignClient} — espejo de {@code HttpResponse}
 * del contrato. Ausente si la transacción terminó en {@link TransaccionErrorRegistrado}.
 */
@Getter
@Builder
public class HttpResponseRegistrado {
    private int statusCode;
    private Instant timestamp;
    private Integer durationMs;
    private String contentType;
    private Map<String, String> headers; // ya enmascarados
    private String body;                 // SIN enmascarar (limitación conocida v3)
}

package com.siman.ensambles.unogroup.service;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Detalle estructurado de un fallo que impidió completar el ciclo
 * request/response (timeout, conexión rechazada, DNS, error de
 * serialización antes de enviar, etc.) — espejo de {@code TransaccionError}
 * del contrato. Mutuamente excluyente con {@link HttpResponseRegistrado}.
 */
@Getter
@Builder
public class TransaccionErrorRegistrado {
    private String tipo;   // TIMEOUT | CONEXION_RECHAZADA | DNS | SERIALIZACION | DESCONOCIDO
    private String mensaje;
    private Instant timestamp;
    private Integer durationMs;
}

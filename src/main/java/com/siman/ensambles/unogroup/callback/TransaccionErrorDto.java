package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/** Espejo de salida de service.TransaccionErrorRegistrado — TransaccionError del contrato. */
@Getter
@Builder
public class TransaccionErrorDto {
    private String tipo;
    private String mensaje;
    private Instant timestamp;
    private Integer durationMs;
}

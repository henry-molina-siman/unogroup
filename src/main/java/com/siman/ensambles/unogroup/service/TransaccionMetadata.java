package com.siman.ensambles.unogroup.service;

import lombok.Builder;
import lombok.Getter;

/**
 * Clasificación interna de SIMAN para una transacción HTTP — no forma
 * parte del intercambio HTTP en sí (espejo de {@code TransaccionMetadata}
 * del contrato).
 */
@Getter
@Builder
public class TransaccionMetadata {
    private int secuencia;
    private String proposito;   // AUTH_TOKEN | UPLOAD_CREATE | UPLOAD_UPDATE
    private boolean esReintento;
}

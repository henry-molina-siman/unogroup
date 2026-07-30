package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

/** Espejo de salida de service.TransaccionMetadata — TransaccionMetadata del contrato. */
@Getter
@Builder
public class TransaccionMetadataDto {
    private int secuencia;
    private String proposito;
    private boolean esReintento;
}

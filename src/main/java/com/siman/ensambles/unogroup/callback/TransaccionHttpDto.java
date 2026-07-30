package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

/**
 * Espejo de salida de service.TransaccionRegistrada — TransaccionHttp del
 * contrato v3 (reemplaza a la antigua IntentoDto/intentos[]).
 * {@code response} y {@code error} son mutuamente excluyentes.
 */
@Getter
@Builder
public class TransaccionHttpDto {
    private TransaccionMetadataDto metadata;
    private HttpRequestDto request;
    private HttpResponseDto response;
    private TransaccionErrorDto error;
}

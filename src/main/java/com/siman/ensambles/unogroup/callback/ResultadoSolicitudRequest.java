package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Body del callback hacia orquestador-app (POST
 * /internal/orquestador/solicitudes/resultado, contrato ResultadoSolicitud
 * v3.0.0-borrador).
 *
 * <p>{@code nombreArchivo} y {@code payloadPartner} ya no existen en v3 —
 * cada transacción {@code UPLOAD_CREATE}/{@code UPLOAD_UPDATE} dentro de
 * {@code transacciones} ya trae {@code request.body} (el payload real
 * enviado) y {@code request.url} (que incluye el nombre de archivo vía el
 * query param {@code path}) — ver Guía de Transacciones HTTP §4.3.
 */
@Getter
@Builder
public class ResultadoSolicitudRequest {
    private String ordenId;
    private String sku;
    private String resultadoFinal;   // EstadoInterno: ENVIADA_PARTNER | ACEPTADA_PARTNER | RECHAZADA_PARTNER
    private List<TransaccionHttpDto> transacciones;
}

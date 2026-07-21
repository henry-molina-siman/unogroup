package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Body del callback hacia orquestador-app (POST
 * /internal/orquestador/solicitudes/resultado, contrato ResultadoSolicitud).
 *
 * <p>{@code payloadPartner} se declara como {@code Object} a propósito:
 * el segundo {@code oneOf} sin discriminator del contrato
 * (SolutionOneCreatePayload/SolutionOneUpdatePayload) no genera ambigüedad
 * de este lado porque unogroup-app ya sabe qué tipo concreto construyó — se
 * serializa tal cual, sin necesidad de tipar un supertipo común.
 */
@Getter
@Builder
public class ResultadoSolicitudRequest {
    private String ordenId;
    private String sku;
    private String resultadoFinal;   // EstadoInterno: ENVIADA_PARTNER | ACEPTADA_PARTNER | RECHAZADA_PARTNER
    private List<IntentoDto> intentos;
    private Object payloadPartner;
}

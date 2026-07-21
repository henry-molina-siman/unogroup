package com.siman.ensambles.unogroup.controller;

import com.siman.ensambles.unogroup.dto.PayloadEnriquecidoNotificacion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Body de POST /internal/unogroup/solicitudes (contrato SolicitudNotificacion). */
@Getter
@Setter
public class SolicitudNotificacionRequest {

    @NotBlank
    private String ordenId;

    @NotBlank
    private String sku;

    @NotBlank
    private String accion;

    @NotNull
    private Instant timestamp;

    @NotNull
    @Valid
    private PayloadEnriquecidoNotificacion payloadEnriquecido;
}

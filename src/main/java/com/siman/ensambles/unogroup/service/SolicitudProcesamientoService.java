package com.siman.ensambles.unogroup.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.siman.ensambles.unogroup.callback.IntentoDto;
import com.siman.ensambles.unogroup.callback.OrquestadorCallbackSender;
import com.siman.ensambles.unogroup.callback.ResultadoSolicitudRequest;
import com.siman.ensambles.unogroup.client.SolutionOneFileNaming;
import com.siman.ensambles.unogroup.config.SolutionOneProperties;
import com.siman.ensambles.unogroup.controller.SolicitudNotificacionRequest;
import com.siman.ensambles.unogroup.dto.PayloadEnriquecidoNotificacion;
import com.siman.ensambles.unogroup.mapper.SolutionOnePayloadMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Orquesta el procesamiento completo de una notificación ya recibida
 * (202 ya respondido por el controller, ver VirtualThreadConfig):
 * mapper -> construcción de path -> SolutionOneRetryPolicy -> callback.
 * Corre íntegramente en el hilo (virtual) que el controller lanzó — sin
 * scheduler, sin tabla de polling (Diseño §2.11).
 */
@Service
@Slf4j
public class SolicitudProcesamientoService {

    private final SolutionOnePayloadMapper mapper;
    private final SolutionOneRetryPolicy retryPolicy;
    private final OrquestadorCallbackSender callbackSender;
    private final SolutionOneProperties solutionOneProperties;

    // Instancia propia de Jackson 2 (com.fasterxml.jackson), no inyectada: el
    // ObjectMapper que Spring Boot 4.x autoconfigura internamente es de
    // "Jackson 3" (tools.jackson), un tipo distinto e incompatible. Los DTOs
    // hacia Solution One (SolutionOneCreatePayload/UpdatePayload) usan
    // anotaciones Jackson 2 — el mismo Jackson que ya trae feign-jackson.
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public SolicitudProcesamientoService(SolutionOnePayloadMapper mapper, SolutionOneRetryPolicy retryPolicy,
            OrquestadorCallbackSender callbackSender, SolutionOneProperties solutionOneProperties) {
        this.mapper = mapper;
        this.retryPolicy = retryPolicy;
        this.callbackSender = callbackSender;
        this.solutionOneProperties = solutionOneProperties;
    }

    public void procesar(SolicitudNotificacionRequest request) {
        PayloadEnriquecidoNotificacion payload = request.getPayloadEnriquecido();
        String accion = request.getAccion();
        boolean esCreacion = "create".equals(accion);
        String tipoPeticion = esCreacion ? "UPLOAD_CREATE" : "UPLOAD_UPDATE";

        // Gap descubierto durante la implementación: PayloadEnriquecidoActualizacion
        // (contrato OpenAPI) no incluye fechaOrden, pero el algoritmo de nombre de
        // archivo (confirmado solo para creación, ver Diseño §9.6 F19) requiere el
        // tracking_order_time del pedido. Sin acceso a base de datos (unogroup-app
        // es stateless) no hay forma de recuperar el valor original en una
        // actualización — se usa la hora actual como fallback documentado, no
        // confirmado con UnoGroup. Ajustar si F19 se resuelve con un ejemplo real.
        Instant trackingOrderTime = payload.getFechaOrden() != null ? payload.getFechaOrden() : Instant.now();

        String path = SolutionOneFileNaming.construirPath(
                solutionOneProperties.getRutaBase(), accion, trackingOrderTime, request.getOrdenId(), request.getSku());

        Object payloadPartner = mapper.mapear(payload, esCreacion);
        byte[] contenido = serializar(payloadPartner);

        ResultadoProcesamiento resultado = retryPolicy.subir(contenido, path, tipoPeticion);

        // El contrato ResultadoSolicitud.resultadoFinal solo admite 3 valores
        // (ENVIADA_PARTNER/ACEPTADA_PARTNER/RECHAZADA_PARTNER) — no existe un
        // cuarto valor explícito para "no se pudo enviar". ACEPTADA/RECHAZADA
        // dependen de un mecanismo de confirmación de contenido que no existe
        // aún (Diseño §2.10, C1 abierto). RECHAZADA_PARTNER es la aproximación
        // menos mala disponible para un fallo definitivo de envío.
        String resultadoFinal = resultado.exitoso() ? "ENVIADA_PARTNER" : "RECHAZADA_PARTNER";

        ResultadoSolicitudRequest callbackRequest = ResultadoSolicitudRequest.builder()
                .ordenId(request.getOrdenId())
                .sku(request.getSku())
                .resultadoFinal(resultadoFinal)
                .intentos(mapearIntentos(resultado.intentos()))
                .payloadPartner(payloadPartner)
                .build();

        callbackSender.enviar(callbackRequest);
    }

    private byte[] serializar(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el payload hacia Solution One", ex);
        }
    }

    private List<IntentoDto> mapearIntentos(List<IntentoRegistrado> intentos) {
        return intentos.stream()
                .map(i -> IntentoDto.builder()
                        .numero(i.getNumero())
                        .tipoPeticion(i.getTipoPeticion())
                        .codigoHttp(i.getCodigoHttp())
                        .duracionMs(i.getDuracionMs())
                        .esReintento(i.isEsReintento())
                        .exitoso(i.isExitoso())
                        .errorMensaje(i.getErrorMensaje())
                        .build())
                .toList();
    }
}

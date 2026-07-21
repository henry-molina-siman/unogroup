package com.siman.ensambles.unogroup.service;

import com.siman.ensambles.unogroup.callback.OrquestadorCallbackSender;
import com.siman.ensambles.unogroup.callback.ResultadoSolicitudRequest;
import com.siman.ensambles.unogroup.config.SolutionOneProperties;
import com.siman.ensambles.unogroup.controller.SolicitudNotificacionRequest;
import com.siman.ensambles.unogroup.dto.PayloadEnriquecidoNotificacion;
import com.siman.ensambles.unogroup.mapper.SolutionOnePayloadMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica que {@code accion} (recibido explícito en el request, no
 * inferido de la forma del payload) determina el tipoPeticion reportado
 * y la rama del mapper — contrato OpenAPI SolicitudNotificacion.accion.
 */
class SolicitudProcesamientoServiceTest {

    private final SolutionOnePayloadMapper mapper = mock(SolutionOnePayloadMapper.class);
    private final SolutionOneRetryPolicy retryPolicy = mock(SolutionOneRetryPolicy.class);
    private final OrquestadorCallbackSender callbackSender = mock(OrquestadorCallbackSender.class);
    private final SolutionOneProperties properties = new SolutionOneProperties();

    private final SolicitudProcesamientoService service =
            new SolicitudProcesamientoService(mapper, retryPolicy, callbackSender, properties);

    {
        properties.setRutaBase("siman");
    }

    @Test
    void accionCreateUsaTipoPeticionCreateYMapeaComoCreacion() {
        SolicitudNotificacionRequest request = requestCon("create");
        when(mapper.mapear(any(), eq(true))).thenReturn(Map.of("k", "v"));
        when(retryPolicy.subir(any(), anyString(), eq("UPLOAD_CREATE")))
                .thenReturn(new ResultadoProcesamiento(true, List.of()));

        service.procesar(request);

        verify(mapper).mapear(any(), eq(true));
        verify(retryPolicy).subir(any(), anyString(), eq("UPLOAD_CREATE"));
    }

    @Test
    void accionUpdateUsaTipoPeticionUpdateYMapeaComoActualizacion() {
        SolicitudNotificacionRequest request = requestCon("update");
        when(mapper.mapear(any(), eq(false))).thenReturn(Map.of("k", "v"));
        when(retryPolicy.subir(any(), anyString(), eq("UPLOAD_UPDATE")))
                .thenReturn(new ResultadoProcesamiento(true, List.of()));

        service.procesar(request);

        verify(mapper).mapear(any(), eq(false));
        verify(retryPolicy).subir(any(), anyString(), eq("UPLOAD_UPDATE"));
    }

    @Test
    void reportaResultadoFinalEnElCallback() {
        SolicitudNotificacionRequest request = requestCon("update");
        when(mapper.mapear(any(), eq(false))).thenReturn(Map.of("k", "v"));
        when(retryPolicy.subir(any(), anyString(), eq("UPLOAD_UPDATE")))
                .thenReturn(new ResultadoProcesamiento(false, List.of()));

        service.procesar(request);

        var captor = org.mockito.ArgumentCaptor.forClass(ResultadoSolicitudRequest.class);
        verify(callbackSender).enviar(captor.capture());
        assertThat(captor.getValue().getResultadoFinal()).isEqualTo("RECHAZADA_PARTNER");
    }

    private SolicitudNotificacionRequest requestCon(String accion) {
        SolicitudNotificacionRequest request = new SolicitudNotificacionRequest();
        request.setOrdenId("9013059587");
        request.setSku("104929691");
        request.setAccion(accion);
        request.setTimestamp(Instant.now());
        PayloadEnriquecidoNotificacion payload = new PayloadEnriquecidoNotificacion();
        payload.setOrdenId("9013059587");
        request.setPayloadEnriquecido(payload);
        return request;
    }
}

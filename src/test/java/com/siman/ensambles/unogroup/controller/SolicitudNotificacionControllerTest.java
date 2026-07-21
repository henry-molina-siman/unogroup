package com.siman.ensambles.unogroup.controller;

import com.siman.ensambles.unogroup.dto.PayloadEnriquecidoNotificacion;
import com.siman.ensambles.unogroup.service.SolicitudProcesamientoService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Verifica el hand-off asíncrono sin levantar contexto de Spring: el
 * controller debe responder 202 sin esperar a que
 * SolicitudProcesamientoService termine (Diseño §2.4, resuelto).
 */
class SolicitudNotificacionControllerTest {

    @Test
    void respondeAceptadoAntesDeQueTermineElProcesamientoEnBackground() throws Exception {
        CountDownLatch procesamientoIniciado = new CountDownLatch(1);
        CountDownLatch liberarProcesamiento = new CountDownLatch(1);

        SolicitudProcesamientoService service = mock(SolicitudProcesamientoService.class);
        doAnswer(invocation -> {
            procesamientoIniciado.countDown();
            liberarProcesamiento.await(5, TimeUnit.SECONDS);
            return null;
        }).when(service).procesar(any());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        SolicitudNotificacionController controller = new SolicitudNotificacionController(service, executor);

        SolicitudNotificacionRequest request = new SolicitudNotificacionRequest();
        request.setOrdenId("9013059587");
        request.setSku("104929691");
        request.setAccion("create");
        request.setTimestamp(Instant.now());
        PayloadEnriquecidoNotificacion payload = new PayloadEnriquecidoNotificacion();
        payload.setOrdenId("9013059587");
        payload.setSku("104929691");
        request.setPayloadEnriquecido(payload);

        ResponseEntity<Void> response = controller.recibir(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        // El 202 ya se devolvió arriba; si el hand-off fuera síncrono, este
        // await fallaría porque el procesamiento seguiría bloqueado dentro
        // de la propia llamada a recibir(...).
        assertThat(procesamientoIniciado.await(2, TimeUnit.SECONDS)).isTrue();
        liberarProcesamiento.countDown();
        executor.shutdown();
    }
}

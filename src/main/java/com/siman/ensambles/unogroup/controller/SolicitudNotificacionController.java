package com.siman.ensambles.unogroup.controller;

import com.siman.ensambles.unogroup.service.SolicitudProcesamientoService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;

/**
 * POST /internal/unogroup/solicitudes (contrato SolicitudNotificacion).
 * Endpoint interno (ClusterIP, nunca expuesto fuera del clúster). Responde
 * 202 SIEMPRE antes de procesar — comunicación asíncrona, resuelto en
 * Diseño §2.4/doc Implementación §1.4.6. ⚠ F8 (Diseño §9.6): autenticación
 * entre servicios dentro del clúster aún sin definir — hoy no hay ningún
 * chequeo aquí más allá de que el Service es ClusterIP.
 */
@RestController
@RequestMapping("/internal/unogroup/solicitudes")
@Slf4j
public class SolicitudNotificacionController {

    private final SolicitudProcesamientoService service;
    private final ExecutorService unogroupProcessingExecutor;

    public SolicitudNotificacionController(SolicitudProcesamientoService service,
            ExecutorService unogroupProcessingExecutor) {
        this.service = service;
        this.unogroupProcessingExecutor = unogroupProcessingExecutor;
    }

    @PostMapping
    public ResponseEntity<Void> recibir(@Valid @RequestBody SolicitudNotificacionRequest request) {
        unogroupProcessingExecutor.execute(() -> procesarSinPropagarErrores(request));
        return ResponseEntity.accepted().build();
    }

    private void procesarSinPropagarErrores(SolicitudNotificacionRequest request) {
        try {
            service.procesar(request);
        } catch (Exception ex) {
            // No hay nadie esperando esta respuesta (202 ya se envió) — un error
            // no recuperado aquí se perdería en silencio si no se loguea.
            log.error("Fallo inesperado procesando solicitud ordenId={} sku={}: {}",
                    request.getOrdenId(), request.getSku(), ex.getMessage(), ex);
        }
    }
}

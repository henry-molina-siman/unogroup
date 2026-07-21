package com.siman.ensambles.unogroup.callback;

import com.siman.ensambles.unogroup.config.CallbackProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reintenta el callback hacia orquestador-app — 5 intentos, backoff
 * exponencial desde 500ms (x2, ≈15.5s total, doc Implementación §1.4.8).
 * Si se agotan los intentos, registra en logs a nivel ERROR en formato
 * estructurado (ordenId, sku, resultadoFinal, intentos[] completo) — el
 * resultado no se descarta, queda recuperable manualmente desde logs;
 * la reconciliación (zona 3, fuera de alcance de esta implementación,
 * ver Diseño §2.11/§9.6 F16) es la red de seguridad final.
 */
@Component
@Slf4j
public class OrquestadorCallbackSender {

    private final OrquestadorCallbackClient client;
    private final CallbackProperties properties;

    public OrquestadorCallbackSender(OrquestadorCallbackClient client, CallbackProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void enviar(ResultadoSolicitudRequest request) {
        int maxIntentos = properties.getReintentos().getMaxIntentos();
        long backoffMs = properties.getReintentos().getBackoffInicialMs();

        for (int intento = 1; intento <= maxIntentos; intento++) {
            try {
                client.reportar(request);
                return;
            } catch (Exception ex) {
                boolean esUltimoIntento = intento == maxIntentos;
                if (esUltimoIntento) {
                    log.error("No se pudo entregar el callback a orquestador-app tras {} intentos — "
                                    + "ordenId={} sku={} resultadoFinal={} intentos={} — error={}",
                            maxIntentos, request.getOrdenId(), request.getSku(), request.getResultadoFinal(),
                            request.getIntentos(), ex.getMessage());
                    return;
                }
                dormir(backoffMs);
                backoffMs *= properties.getReintentos().getBackoffMultiplicador();
            }
        }
    }

    private void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

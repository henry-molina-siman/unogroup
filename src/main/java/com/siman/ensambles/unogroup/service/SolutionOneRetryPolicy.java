package com.siman.ensambles.unogroup.service;

import com.siman.ensambles.unogroup.client.CapturingFeignClient;
import com.siman.ensambles.unogroup.client.SolutionOneClient;
import com.siman.ensambles.unogroup.client.SolutionOneTokenManager;
import com.siman.ensambles.unogroup.config.SolutionOneProperties;
import feign.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementa exactamente la tabla de reintentos hacia Solution One
 * (Diseño §2.9/§6.6, doc Implementación §1.4.6):
 * 201 éxito; 400 no reintenta; 401 reintenta UNA vez tras renovar token;
 * 403/413 no reintenta, escala; 500 backoff exponencial (1s→2s→4s→8s→16s,
 * máx 5 intentos), luego escala. Corre en el mismo hilo que invoca
 * (virtual thread, ver VirtualThreadConfig) — sin scheduler, sin tabla
 * de polling.
 *
 * <p>Cada llamada real (auth/upload) se registra como una
 * {@link TransaccionRegistrada} — el detalle HTTP (method/url/headers/body,
 * ya enmascarado) lo captura {@link CapturingFeignClient}; esta clase solo
 * anexa la metadata de negocio (secuencia/proposito/esReintento), sin
 * necesidad de workaround alguno de {@code url}/{@code metodoHttp}
 * (v3, ver Guía de Transacciones HTTP §4.4 — el workaround de v2 se elimina).
 */
@Component
@Slf4j
public class SolutionOneRetryPolicy {

    private final SolutionOneClient client;
    private final SolutionOneTokenManager tokenManager;
    private final SolutionOneProperties properties;
    private final CapturingFeignClient capturingFeignClient;

    public SolutionOneRetryPolicy(SolutionOneClient client, SolutionOneTokenManager tokenManager,
            SolutionOneProperties properties, CapturingFeignClient capturingFeignClient) {
        this.client = client;
        this.tokenManager = tokenManager;
        this.properties = properties;
        this.capturingFeignClient = capturingFeignClient;
    }

    public ResultadoProcesamiento subir(byte[] contenido, String path, String tipoPeticion) {
        List<TransaccionRegistrada> transacciones = new ArrayList<>();
        int secuencia = 1;

        String token;
        try {
            token = obtenerTokenYRegistrar(transacciones, secuencia++, false);
        } catch (Exception ex) {
            log.error("No se pudo obtener el token de Solution One antes de subir path={}: {}", path, ex.getMessage());
            return new ResultadoProcesamiento(false, transacciones);
        }

        boolean reintentoPor401Usado = false;
        int numeroUpload = 1;
        long backoffMs = properties.getReintentos().getBackoffInicialMs();
        int maxIntentos = properties.getReintentos().getMaxIntentos();

        while (true) {
            TransaccionRegistrada transaccion = ejecutarUploadYRegistrar(token, path, contenido, tipoPeticion,
                    secuencia++, numeroUpload > 1);
            transacciones.add(transaccion);
            Integer codigo = transaccion.getResponse() != null ? transaccion.getResponse().getStatusCode() : null;

            if (codigo != null && codigo == 201) {
                return new ResultadoProcesamiento(true, transacciones);
            }

            if (codigo != null && codigo == 401 && !reintentoPor401Usado) {
                reintentoPor401Usado = true;
                try {
                    token = obtenerTokenYRegistrar(transacciones, secuencia++, true);
                } catch (Exception ex) {
                    log.error("No se pudo renovar el token de Solution One tras 401 (path={}): {}", path, ex.getMessage());
                    return new ResultadoProcesamiento(false, transacciones);
                }
                numeroUpload++;
                continue;
            }

            if (codigo != null && (codigo == 400 || codigo == 403 || codigo == 413)) {
                log.error("Solution One respondió {} para path={} — no se reintenta, se escala (LifeOne/UnoGroup, ver Diseño C6)",
                        codigo, path);
                return new ResultadoProcesamiento(false, transacciones);
            }

            // 500 (o falla de red/timeout, sin response) — backoff exponencial.
            if (numeroUpload >= maxIntentos) {
                log.error("Se agotaron los {} intentos hacia Solution One para path={}", maxIntentos, path);
                return new ResultadoProcesamiento(false, transacciones);
            }
            dormir(backoffMs);
            backoffMs *= properties.getReintentos().getBackoffMultiplicador();
            numeroUpload++;
        }
    }

    private String obtenerTokenYRegistrar(List<TransaccionRegistrada> transacciones, int secuencia, boolean esReintento) {
        try {
            return tokenManager.renovarToken();
        } finally {
            transacciones.add(construirTransaccion(secuencia, "AUTH_TOKEN", esReintento));
        }
    }

    private TransaccionRegistrada ejecutarUploadYRegistrar(String token, String path, byte[] contenido,
            String tipoPeticion, int secuencia, boolean esReintento) {
        try (Response response = client.subirArchivo("Bearer " + token, path, properties.isMkdirParents(), contenido)) {
            // El body ya fue leído/reconstruido dentro de CapturingFeignClient;
            // aquí solo cerramos el recurso.
        } catch (Exception ex) {
            // Falla de red (timeout, conexión rechazada, DNS) — ya quedó
            // clasificada en CapturingFeignClient; se ignora aquí a propósito.
        }
        return construirTransaccion(secuencia, tipoPeticion, esReintento);
    }

    private TransaccionRegistrada construirTransaccion(int secuencia, String proposito, boolean esReintento) {
        return TransaccionRegistrada.builder()
                .metadata(TransaccionMetadata.builder()
                        .secuencia(secuencia)
                        .proposito(proposito)
                        .esReintento(esReintento)
                        .build())
                .request(capturingFeignClient.tomarUltimaRequest())
                .response(capturingFeignClient.tomarUltimaResponse())
                .error(capturingFeignClient.tomarUltimoError())
                .build();
    }

    private void dormir(long millis) {
        try {
            Thread.sleep(millis); // barato: corre en un virtual thread (spring.threads.virtual.enabled)
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

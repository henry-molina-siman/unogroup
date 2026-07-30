package com.siman.ensambles.unogroup.service;

import com.siman.ensambles.unogroup.client.SolutionOneClient;
import com.siman.ensambles.unogroup.client.SolutionOneTokenManager;
import com.siman.ensambles.unogroup.config.SolutionOneProperties;
import feign.FeignException;
import feign.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 */
@Component
@Slf4j
public class SolutionOneRetryPolicy {

    private final SolutionOneClient client;
    private final SolutionOneTokenManager tokenManager;
    private final SolutionOneProperties properties;

    public SolutionOneRetryPolicy(SolutionOneClient client, SolutionOneTokenManager tokenManager,
            SolutionOneProperties properties) {
        this.client = client;
        this.tokenManager = tokenManager;
        this.properties = properties;
    }

    public ResultadoProcesamiento subir(byte[] contenido, String path, String tipoPeticion) {
        List<IntentoRegistrado> intentos = new ArrayList<>();

        String token;
        try {
            token = obtenerTokenYRegistrar(intentos, false);
        } catch (Exception ex) {
            log.error("No se pudo obtener el token de Solution One antes de subir path={}: {}", path, ex.getMessage());
            return new ResultadoProcesamiento(false, intentos);
        }

        boolean reintentoPor401Usado = false;
        int numeroUpload = 1;
        long backoffMs = properties.getReintentos().getBackoffInicialMs();
        int maxIntentos = properties.getReintentos().getMaxIntentos();

        while (true) {
            IntentoRegistrado intento = ejecutarUpload(token, path, contenido, tipoPeticion, numeroUpload, numeroUpload > 1);
            intentos.add(intento);
            Integer codigo = intento.getCodigoHttp();

            if (codigo != null && codigo == 201) {
                return new ResultadoProcesamiento(true, intentos);
            }

            if (codigo != null && codigo == 401 && !reintentoPor401Usado) {
                reintentoPor401Usado = true;
                try {
                    token = obtenerTokenYRegistrar(intentos, true);
                } catch (Exception ex) {
                    log.error("No se pudo renovar el token de Solution One tras 401 (path={}): {}", path, ex.getMessage());
                    return new ResultadoProcesamiento(false, intentos);
                }
                numeroUpload++;
                continue;
            }

            if (codigo != null && (codigo == 400 || codigo == 403 || codigo == 413)) {
                log.error("Solution One respondió {} para path={} — no se reintenta, se escala (LifeOne/UnoGroup, ver Diseño C6)",
                        codigo, path);
                return new ResultadoProcesamiento(false, intentos);
            }

            // 500 (o falla de red/timeout, codigo == null) — backoff exponencial.
            if (numeroUpload >= maxIntentos) {
                log.error("Se agotaron los {} intentos hacia Solution One para path={}", maxIntentos, path);
                return new ResultadoProcesamiento(false, intentos);
            }
            dormir(backoffMs);
            backoffMs *= properties.getReintentos().getBackoffMultiplicador();
            numeroUpload++;
        }
    }

    private String obtenerTokenYRegistrar(List<IntentoRegistrado> intentos, boolean esReintento) {
        long inicio = System.currentTimeMillis();
        String url = tokenUrl();
        try {
            String token = tokenManager.renovarToken();
            intentos.add(IntentoRegistrado.builder()
                    .numero(esReintento ? 2 : 1)
                    .tipoPeticion("AUTH_TOKEN")
                    .url(url)
                    .metodoHttp("GET")
                    .codigoHttp(200)
                    .duracionMs((int) (System.currentTimeMillis() - inicio))
                    .esReintento(esReintento)
                    .exitoso(true)
                    .build());
            return token;
        } catch (FeignException fe) {
            intentos.add(IntentoRegistrado.builder()
                    .numero(esReintento ? 2 : 1)
                    .tipoPeticion("AUTH_TOKEN")
                    .url(url)
                    .metodoHttp("GET")
                    .codigoHttp(fe.status())
                    .duracionMs((int) (System.currentTimeMillis() - inicio))
                    .esReintento(esReintento)
                    .exitoso(false)
                    .errorMensaje(fe.getMessage())
                    .build());
            throw fe;
        } catch (RuntimeException ex) {
            intentos.add(IntentoRegistrado.builder()
                    .numero(esReintento ? 2 : 1)
                    .tipoPeticion("AUTH_TOKEN")
                    .url(url)
                    .metodoHttp("GET")
                    .codigoHttp(null)
                    .duracionMs((int) (System.currentTimeMillis() - inicio))
                    .esReintento(esReintento)
                    .exitoso(false)
                    .errorMensaje(ex.getMessage())
                    .build());
            throw ex;
        }
    }

    private IntentoRegistrado ejecutarUpload(String token, String path, byte[] contenido, String tipoPeticion,
            int numero, boolean esReintento) {
        long inicio = System.currentTimeMillis();
        String url = uploadUrl();
        try (Response response = client.subirArchivo("Bearer " + token, path,
                properties.isMkdirParents(), contenido)) {
            int status = response.status();
            return IntentoRegistrado.builder()
                    .numero(numero)
                    .tipoPeticion(tipoPeticion)
                    .url(url)
                    .metodoHttp("POST")
                    .codigoHttp(status)
                    .duracionMs((int) (System.currentTimeMillis() - inicio))
                    .esReintento(esReintento)
                    .exitoso(status == 201)
                    .errorMensaje(status == 201 ? null : leerCuerpoError(response))
                    .build();
        } catch (Exception ex) {
            return IntentoRegistrado.builder()
                    .numero(numero)
                    .tipoPeticion(tipoPeticion)
                    .url(url)
                    .metodoHttp("POST")
                    .codigoHttp(null)
                    .duracionMs((int) (System.currentTimeMillis() - inicio))
                    .esReintento(esReintento)
                    .exitoso(false)
                    .errorMensaje(ex.getMessage())
                    .build();
        }
    }

    private String tokenUrl() {
        return properties.getBaseUrl() + properties.getTokenPath();
    }

    private String uploadUrl() {
        return properties.getBaseUrl() + properties.getUploadPath();
    }

    private String leerCuerpoError(Response response) {
        if (response.body() == null) {
            return null;
        }
        try {
            return new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    private void dormir(long millis) {
        try {
            Thread.sleep(millis); // barato: corre en un virtual thread (spring.threads.virtual.enabled)
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

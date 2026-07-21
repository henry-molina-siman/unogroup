package com.siman.ensambles.unogroup.client;

import com.siman.ensambles.unogroup.dto.SolutionOneTokenResponse;
import feign.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Cliente hacia Solution One (Diseño §6.x / doc Implementación §5-6).
 *
 * <p>{@code subirArchivo} devuelve {@link Response} crudo (no un DTO
 * decodificado) a propósito: la política de reintentos
 * ({@code service.SolutionOneRetryPolicy}) necesita inspeccionar el código
 * HTTP exacto (201/400/401/403/413/500) sin que Feign lance una excepción
 * en los casos de error esperados — así se evita depender de un
 * {@code ErrorDecoder} para un flujo que es, en este caso, parte normal
 * del negocio, no una condición excepcional.
 *
 * <p>{@code /api/v2/user/token} responde JSON ({@code access_token} +
 * {@code expires_at}), no un JWT en texto plano — se decodifica como
 * {@link SolutionOneTokenResponse}.
 */
@FeignClient(name = "solutionOneClient",
        url = "${ensambles.adapter.solutionone.base-url}",
        configuration = SolutionOneFeignConfig.class)
public interface SolutionOneClient {

    @GetMapping("${ensambles.adapter.solutionone.token-path:/api/v2/user/token}")
    SolutionOneTokenResponse obtenerToken(@RequestHeader("Authorization") String basicAuthHeader);

    @PostMapping("${ensambles.adapter.solutionone.upload-path:/api/v2/user/files/upload}")
    Response subirArchivo(@RequestHeader("Authorization") String bearerToken,
                          @RequestParam("path") String path,
                          @RequestParam("mkdir_parents") boolean mkdirParents,
                          @RequestBody byte[] contenido);
}

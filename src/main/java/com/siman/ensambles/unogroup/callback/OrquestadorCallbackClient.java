package com.siman.ensambles.unogroup.callback;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "orquestadorCallbackClient",
        url = "${ensambles.callback.orquestador-url}")
public interface OrquestadorCallbackClient {

    @PostMapping("/internal/orquestador/solicitudes/resultado")
    void reportar(@RequestBody ResultadoSolicitudRequest request);
}

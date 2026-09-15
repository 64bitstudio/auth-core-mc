package com.mcortes.authcoremc.geoip;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticket 070 -- el punto de entrada {@code @Scheduled} para
 * {@link GeoIpService#reloadIfChanged}, deliberadamente en su propio bean
 * en vez de plegado en {@code GeoIpService} mismo (mismo criterio ya
 * documentado en {@code TenantPurgeScheduler}: separar el disparador
 * programado del servicio real).
 *
 * <p>{@code initialDelay = 0} -- carga la base de datos apenas arranca la
 * app (si ya existe en el volumen compartido), sin esperar la primera
 * hora completa.
 */
@Component
public class GeoIpDatabaseScheduler {

    private final GeoIpService geoIpService;

    public GeoIpDatabaseScheduler(GeoIpService geoIpService) {
        this.geoIpService = geoIpService;
    }

    /** Cada hora -- el sidecar `geoipupdate` corre a lo mucho una vez por semana; esto solo detecta ese archivo nuevo y recarga en caliente, sin reiniciar el proceso. */
    @Scheduled(initialDelay = 0, fixedRate = 60 * 60 * 1000)
    public void reloadDatabaseIfChanged() {
        geoIpService.reloadIfChanged();
    }
}

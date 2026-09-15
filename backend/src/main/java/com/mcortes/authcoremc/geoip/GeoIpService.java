package com.mcortes.authcoremc.geoip;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ticket 070 -- geolocalización de "Sesiones activas" (Mi Perfil de
 * galgoth-studio, alineación con su mockup original), revirtiendo la
 * decisión explícita del ticket 062 de no geolocalizar. Decisión de
 * Marco: base de datos local GeoLite2-City de MaxMind, no un servicio
 * externo en vivo -- ninguna IP de usuario se manda a un tercero por
 * request, a costa de menos precisión de ciudad que un servicio en vivo.
 *
 * <p>Este servicio NUNCA descarga la base de datos -- eso lo hace el
 * sidecar oficial {@code geoipupdate} de MaxMind (ver
 * {@code deploy/docker-compose.*.yml}), que la deja en un volumen
 * compartido. Acá solo se abre/recarga desde {@code geoip.database-path}
 * -- ver {@link GeoIpDatabaseScheduler} para cuándo se llama
 * {@link #reloadIfChanged()} (separado de este servicio por el mismo
 * motivo que {@code TenantPurgeScheduler}/{@code TenantPurgeService}: un
 * bean que se auto-invoca desde su propio constructor/método programado
 * es más frágil de probar que uno que solo expone el método real).
 *
 * <p>Degradación explícita, no oculta: si el archivo no existe todavía
 * (primera vez, o el sidecar sin `MAXMIND_ACCOUNT_ID`/
 * `MAXMIND_LICENSE_KEY` configurados) o la IP no resuelve (privada,
 * reservada, o simplemente no reconocida por GeoLite2), {@link #lookup}
 * devuelve {@link Optional#empty()} -- "Sesiones activas" ya funciona
 * perfectamente sin ubicación desde el ticket 062, así que esto es un
 * campo opcional que se degrada con gracia, no un error a esconder.
 */
@Service
public class GeoIpService {

    private static final Logger log = LoggerFactory.getLogger(GeoIpService.class);

    private final Path databasePath;
    private final AtomicReference<DatabaseReader> reader = new AtomicReference<>();
    private volatile Instant lastLoadedAt;
    private volatile boolean loggedMissingOnce;

    public GeoIpService(@Value("${geoip.database-path:./geoip/GeoLite2-City.mmdb}") String databasePath) {
        this.databasePath = Path.of(databasePath);
    }

    public Optional<GeoLocation> lookup(String ip) {
        DatabaseReader currentReader = reader.get();
        if (currentReader == null || ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            CityResponse response = currentReader.city(address);
            String city = response.getCity() == null ? null : response.getCity().getName();
            String country = response.getCountry() == null ? null : response.getCountry().getName();
            if (city == null && country == null) {
                return Optional.empty();
            }
            return Optional.of(new GeoLocation(city, country));
        } catch (AddressNotFoundException | UnknownHostException _) {
            // IP privada/reservada (dev local, VPN interna), no resoluble
            // (UnknownHostException) o simplemente no está en la base de
            // datos (AddressNotFoundException) -- no es un error, es "sin
            // ubicación".
            return Optional.empty();
        } catch (IOException | GeoIp2Exception e) {
            log.warn("GeoIP lookup failed for an IP, continuing without location", e);
            return Optional.empty();
        }
    }

    /** Ver {@link GeoIpDatabaseScheduler} para cuándo/con qué frecuencia se llama esto. */
    public void reloadIfChanged() {
        if (!Files.exists(databasePath)) {
            if (!loggedMissingOnce) {
                log.info(
                        "GeoLite2 database not found at {} yet -- session locations will be empty until the geoipupdate sidecar downloads it",
                        databasePath);
                loggedMissingOnce = true;
            }
            return;
        }
        try {
            Instant modifiedAt = Files.getLastModifiedTime(databasePath).toInstant();
            if (modifiedAt.equals(lastLoadedAt)) {
                return;
            }
            DatabaseReader newReader = new DatabaseReader.Builder(databasePath.toFile()).build();
            DatabaseReader previous = reader.getAndSet(newReader);
            lastLoadedAt = modifiedAt;
            loggedMissingOnce = false;
            log.info("Loaded GeoLite2 database from {} (last modified {})", databasePath, modifiedAt);
            closeQuietly(previous);
        } catch (IOException e) {
            log.warn("Failed to load GeoLite2 database from {}, keeping the previous one (if any)", databasePath, e);
        }
    }

    private static void closeQuietly(DatabaseReader previous) {
        if (previous == null) {
            return;
        }
        try {
            previous.close();
        } catch (IOException _) {
            // Best-effort -- a leaked file handle on reload is not worth failing the request over.
        }
    }
}

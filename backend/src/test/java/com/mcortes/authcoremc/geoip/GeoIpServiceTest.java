package com.mcortes.authcoremc.geoip;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Ticket 070 -- real lookups against MaxMind's own published test fixture
 * ({@code src/test/resources/geoip/GeoLite2-City-Test.mmdb}, from
 * https://github.com/maxmind/MaxMind-DB/tree/main/test-data — a small
 * database MaxMind distributes specifically for offline tests like this
 * one, with a handful of well-known IP→location mappings baked in), not a
 * mock of the MaxMind library. No network access, no license key needed.
 */
class GeoIpServiceTest {

    private GeoIpService serviceWithTestDatabase() throws URISyntaxException {
        Path path = Paths.get(getClass().getClassLoader().getResource("geoip/GeoLite2-City-Test.mmdb").toURI());
        GeoIpService service = new GeoIpService(path.toString());
        service.reloadIfChanged();
        return service;
    }

    @Test
    void aKnownPublicIpResolvesToARealCityAndCountry() throws Exception {
        GeoIpService service = serviceWithTestDatabase();

        // 81.2.69.142 -- London, England, GB, uno de los IPs de ejemplo que MaxMind documenta para esta base de prueba.
        Optional<GeoLocation> location = service.lookup("81.2.69.142");

        assertThat(location).isPresent();
        assertThat(location.get().city()).isEqualTo("London");
        assertThat(location.get().country()).isEqualTo("United Kingdom");
    }

    @Test
    void aPrivateIpHasNoLocation() throws Exception {
        GeoIpService service = serviceWithTestDatabase();

        assertThat(service.lookup("192.168.1.1")).isEmpty();
    }

    @Test
    void aNullOrBlankIpHasNoLocation() throws Exception {
        GeoIpService service = serviceWithTestDatabase();

        assertThat(service.lookup(null)).isEmpty();
        assertThat(service.lookup("")).isEmpty();
    }

    @Test
    void whenTheDatabaseFileDoesNotExistEveryLookupIsEmptyInsteadOfThrowing() {
        GeoIpService service = new GeoIpService("/nonexistent/GeoLite2-City.mmdb");
        service.reloadIfChanged();

        assertThat(service.lookup("81.2.69.142")).isEmpty();
    }
}

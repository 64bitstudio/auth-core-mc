package com.mcortes.authcoremc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Ticket 013: enables TenantPurgeService's daily @Scheduled purge job.
// Ticket 002 de platform: comentario sin efecto real, para invalidar la
// caché de compilación de Gradle (comment-only source change) y
// garantizar un compileJava/compileTestJava real, no cacheado, al
// verificar en vivo el punto 1 (recreate seguro de Jenkins).
@EnableScheduling
@SpringBootApplication
public class AuthCoreMcApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthCoreMcApplication.class, args);
	}

}

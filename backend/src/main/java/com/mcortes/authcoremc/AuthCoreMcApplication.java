package com.mcortes.authcoremc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Ticket 013: enables TenantPurgeService's daily @Scheduled purge job.
@EnableScheduling
@SpringBootApplication
public class AuthCoreMcApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthCoreMcApplication.class, args);
	}

}

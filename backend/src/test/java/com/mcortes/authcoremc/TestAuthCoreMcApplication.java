package com.mcortes.authcoremc;

import org.springframework.boot.SpringApplication;

public class TestAuthCoreMcApplication {

	public static void main(String[] args) {
		SpringApplication.from(AuthCoreMcApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

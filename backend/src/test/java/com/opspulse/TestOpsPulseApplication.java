package com.opspulse;

import org.springframework.boot.SpringApplication;

public class TestOpsPulseApplication {

	public static void main(String[] args) {
		SpringApplication.from(OpsPulseApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

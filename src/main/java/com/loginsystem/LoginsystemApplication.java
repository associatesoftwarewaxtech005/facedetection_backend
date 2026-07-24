package com.loginsystem;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class LoginsystemApplication {

	@PostConstruct
	public void init() {
		// Enforce local timezone (IST) for accurate attendance logging
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
	}

	public static void main(String[] args) {
		SpringApplication.run(LoginsystemApplication.class, args);
	}

}


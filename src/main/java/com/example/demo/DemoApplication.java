package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@RestController
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@GetMapping("/")
	public Map<String, Object> home() {
		return Map.of(
			"message", "Spring Boot Application is successfully deployed to VPS via SSH Action!",
			"status", "UP",
			"code", 200
		);
	}

	@GetMapping("/api")
	public Map<String, Object> api() {
		return Map.of(
			"app", "Spring Boot Docker Container",
			"deployment", "SSH Action to VPS",
			"health", "OK"
		);
	}
}


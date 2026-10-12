package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:monetra-demo",
				"spring.mail.host=localhost",
				"monetra.seed-demo-users=true",
				"monetra.demo-password=Test-Demo-Pass-1",
				"paypal.client-id=",
				"paypal.client-secret=",
				"anthropic.api-key="
		})
class DemoDataTest {

	@LocalServerPort
	int port;

	@Autowired
	UserAccountRepository users;

	@Test
	void demoAccountsExistCanLogInAndHaveSampleData() throws Exception {
		assertTrue(users.findByEmail("demo1@monetra.test").isPresent());
		assertTrue(users.findByEmail("demo2@monetra.test").isPresent());

		HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

		HttpResponse<String> login = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(
								"{\"email\":\"demo1@monetra.test\",\"password\":\"Test-Demo-Pass-1\"}"))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, login.statusCode());

		String data = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/data")).GET().build(),
				HttpResponse.BodyHandlers.ofString()).body();

		assertTrue(data.contains("Laptop EMI"), data);
		assertTrue(data.contains("New phone"), data);
	}
}

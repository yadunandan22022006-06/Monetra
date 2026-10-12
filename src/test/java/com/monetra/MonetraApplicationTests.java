package com.monetra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:monetra-test",
		"spring.mail.host=localhost"
})
class MonetraApplicationTests {

	@Test
	void contextLoads() {
	}

}

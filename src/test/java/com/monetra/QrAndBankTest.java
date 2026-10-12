package com.monetra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrAndBankTest {

	@Test
	void phoneKeyUsesLastTenDigits() {
		assertEquals("9876543210", Phones.key("+91 98765 43210"));
		assertEquals("9876543210", Phones.key("09876543210"));
		assertNull(Phones.key("12345"));
		assertNull(Phones.key(null));
		assertEquals("98xxxxxx10", Phones.mask("9876543210"));
	}

	@Test
	void readsAMonetraLink() {
		Map<String, String> p = QrController.parse("monetra://pay?phone=9876543210");
		assertEquals("9876543210", p.get("phone"));
		assertEquals("", p.get("name"));
	}

	@Test
	void readsAUpiLinkWithPhoneAndName() {
		Map<String, String> p = QrController.parse("upi://pay?pa=9876543210@ybl&pn=Rahul%20Kumar&am=500");
		assertEquals("9876543210", p.get("phone"));
		assertEquals("Rahul Kumar", p.get("name"));
	}

	@Test
	void upiLinkWithNonPhoneIdGivesNoPhone() {
		Map<String, String> p = QrController.parse("upi://pay?pa=shop@okbank&pn=Corner%20Shop");
		assertEquals("", p.get("phone"));
		assertEquals("Corner Shop", p.get("name"));
	}

	@Test
	void readsAPlainNumber() {
		assertEquals("9876543210", QrController.parse("+91-98765-43210").get("phone"));
		assertEquals("", QrController.parse("hello").get("phone"));
	}

	@Test
	void drawsAnSvgQrCode() throws Exception {
		String svg = QrController.svg("monetra://pay?phone=9876543210");
		assertTrue(svg.startsWith("<svg"));
		assertTrue(svg.contains("<path"));
	}

	@Test
	void detectsBanksFromIfsc() {
		assertTrue(BankDirectory.validIfsc("HDFC0001234"));
		assertFalse(BankDirectory.validIfsc("HDFC1001234"));
		assertFalse(BankDirectory.validIfsc("hdfc0001234"));
		assertEquals("HDFC Bank", BankDirectory.bankName("HDFC0001234"));
		assertEquals("State Bank of India", BankDirectory.bankName("SBIN0000001"));
		assertEquals("Bank (ABCD)", BankDirectory.bankName("ABCD0123456"));
		assertEquals("", BankDirectory.bankName("bad"));
	}

	@Test
	void validatesAccountNumbers() {
		assertTrue(BankDirectory.validAccount("123456789"));
		assertFalse(BankDirectory.validAccount("12345"));
		assertFalse(BankDirectory.validAccount("12345678a"));
	}
}

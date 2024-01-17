/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws.auth;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.net.URLCodec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;


/**
 * Test class of {@link AWS4SignerBase}
 */
class AWS4SignerBaseTest {

	/**
	 * signer
	 */
	final private AWS4SignerBase signer = new AWS4SignerVMForAuthorizationHeader();

	/**
	 * Test method for
	 * {@link  org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizeHeaderNames(java.util.Map)}.
	 */
	@Test
	void testGetCanonicalizeHeaderNames() {
		final var headerNames = signer.getCanonicalizeHeaderNames(Map.of("header2", "h2", "header1", "h1"));
		Assertions.assertEquals("header1;header2", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedHeaderString(java.util.Map)}.
	 */
	@Test
	void testGetCanonicalizedHeaderStringWithoutHeaders() {
		final var headerNames = signer.getCanonicalizedHeaderString(new HashMap<>());
		Assertions.assertEquals("", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedHeaderString(java.util.Map)}.
	 */
	@Test
	void testGetCanonicalizedHeaderString() {
		final var headerNames = signer
				.getCanonicalizedHeaderString(Map.of("header2", "h  2", "header1", "h1"));
		Assertions.assertEquals("header1:h1\nheader2:h 2\n", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalRequest(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)}.
	 */
	@Test
	void testGetCanonicalRequest() {
		final var headerNames = signer.getCanonicalRequest("path", "GET", "q=1", "header1;header2",
				"header1:h1\nheader2:h 2\n", "bodyhash");
		Assertions.assertEquals("GET\n/path\nq=1\nheader1:h1\nheader2:h 2\n\nheader1;header2\nbodyhash", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	void testGetCanonicalizedResourcePathNull() {
		Assertions.assertEquals("/", signer.getCanonicalizedResourcePath(null));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	void testGetCanonicalizedResourcePathEmpty() {
		Assertions.assertEquals("/", signer.getCanonicalizedResourcePath(""));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	void testGetCanonicalizedResourcePathWithoutFirstSlash() {
		Assertions.assertEquals("/path", signer.getCanonicalizedResourcePath("path"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	void testGetCanonicalizedResourcePathWithFirstSlash() {
		Assertions.assertEquals("/path", signer.getCanonicalizedResourcePath("/path"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	void testGetCanonicalizedResourcePathEncodingException() throws EncoderException {
		final var signer = new AWS4SignerVMForAuthorizationHeader();
		final var urlCodec = Mockito.mock(URLCodec.class);
		ReflectionTestUtils.setField(signer, "urlCodec", urlCodec);
		Mockito.when(urlCodec.encode(ArgumentMatchers.anyString())).thenThrow(new EncoderException());
		Assertions.assertEquals("Error during resource path encoding",
				Assertions.assertThrows(TechnicalException.class, () -> signer.getCanonicalizedResourcePath("/path")).getMessage());
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedQueryString(java.util.Map)}.
	 */
	@Test
	void testGetCanonicalizedQueryStringEmpty() {
		Assertions.assertEquals("", signer.getCanonicalizedQueryString(Map.of()));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedQueryString(java.util.Map)}.
	 */
	@Test
	void testGetCanonicalizedQueryString() {
		Assertions.assertEquals("q1=v1&q2=v2",
				signer.getCanonicalizedQueryString(Map.of("q2", "v2", "q1", "v1")));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getCanonicalizedQueryString(java.util.Map)}.
	 */
	@Test
	void testGetCanonicalizedQueryStringException() throws EncoderException {
		final var signer = new AWS4SignerVMForAuthorizationHeader();
		final var urlCodec = Mockito.mock(URLCodec.class);
		ReflectionTestUtils.setField(signer, "urlCodec", urlCodec);
		Mockito.when(urlCodec.encode(ArgumentMatchers.anyString())).thenThrow(new EncoderException());
		Assertions.assertEquals("Error during parameters encoding",
				Assertions.assertThrows(TechnicalException.class, () -> signer.getCanonicalizedQueryString(Map.of("q2", "v2", "q1", "v1"))).getMessage());
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#getStringToSign(java.lang.String, java.lang.String, java.lang.String)}.
	 */
	@Test
	void testGetStringToSign() {
		Assertions.assertEquals(
				"AWS4-HMAC-SHA256\ndate\nscope\n1f58b9145b24d108d7ac38887338b3ea3229833b9c1e418250343f907bfd1047",
				signer.getStringToSign("date", "scope", "request"));
	}

	/**
	 * Test method for {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#hash(java.lang.String)}.
	 */
	@Test
	void testHash() {
		Assertions.assertEquals("982d9e3eb996f559e633f4d194def3761d909f5a3b647d1a851fead67c32c9d1",
				signer.hash("text"));
	}

	/**
	 * Test method for {@link org.ligoj.app.plugin.vm.aws.auth.AWS4SignerBase#sign(java.lang.String, byte[])}.
	 */
	@Test
	void testSign() {
		Assertions.assertEquals("ee87b52bc435b6a4aeea07fc1e2499fd4a801487ef675a00a629b7264663d8fa",
				Hex.encodeHexString(signer.sign("text", new byte[] { 1, 2, 3, 4, 5 })));
	}

}

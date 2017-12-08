/**
 * 
 */
package org.ligoj.app.plugin.vm.aws.auth;

import java.util.HashMap;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.net.URLCodec;
import org.junit.Assert;
import org.junit.Test;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.common.collect.ImmutableMap;

/**
 * Test class of {@link AWS4SignerBase}
 */
public class AWS4SignerBaseTest {

	/**
	 * signer
	 */
	final private AWS4SignerBase signer = new AWS4SignerVMForAuthorizationHeader();

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizeHeaderNames(java.util.Map)}.
	 */
	@Test
	public void testGetCanonicalizeHeaderNames() throws Exception {
		final String headerNames = signer.getCanonicalizeHeaderNames(ImmutableMap.of("header2", "h2", "header1", "h1"));
		Assert.assertEquals("header1;header2", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedHeaderString(java.util.Map)}.
	 */
	@Test
	public void testGetCanonicalizedHeaderStringWithoutHeaders() throws Exception {
		final String headerNames = signer.getCanonicalizedHeaderString(new HashMap<String, String>());
		Assert.assertEquals("", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedHeaderString(java.util.Map)}.
	 */
	@Test
	public void testGetCanonicalizedHeaderString() throws Exception {
		final String headerNames = signer.getCanonicalizedHeaderString(ImmutableMap.of("header2", "h  2", "header1", "h1"));
		Assert.assertEquals("header1:h1\nheader2:h 2\n", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalRequest(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)}.
	 */
	@Test
	public void testGetCanonicalRequest() throws Exception {
		final String headerNames = signer.getCanonicalRequest("path", "GET", "q=1", "header1;header2", "header1:h1\nheader2:h 2\n", "bodyhash");
		Assert.assertEquals("GET\n/path\nq=1\nheader1:h1\nheader2:h 2\n\nheader1;header2\nbodyhash", headerNames);
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	public void testGetCanonicalizedResourcePathNull() throws Exception {
		Assert.assertEquals("/", signer.getCanonicalizedResourcePath(null));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	public void testGetCanonicalizedResourcePathEmpty() throws Exception {
		Assert.assertEquals("/", signer.getCanonicalizedResourcePath(""));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	public void testGetCanonicalizedResourcePathWithoutFirstSlash() throws Exception {
		Assert.assertEquals("/path", signer.getCanonicalizedResourcePath("path"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test
	public void testGetCanonicalizedResourcePathWithFirstSlash() throws Exception {
		Assert.assertEquals("/path", signer.getCanonicalizedResourcePath("/path"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedResourcePath(java.lang.String)}.
	 */
	@Test(expected = TechnicalException.class)
	public void testGetCanonicalizedResourcePathEncodingException() throws Exception {
		final AWS4SignerBase signer = new AWS4SignerVMForAuthorizationHeader();
		final URLCodec urlCodec = Mockito.mock(URLCodec.class);
		ReflectionTestUtils.setField(signer, "urlCodec", urlCodec);
		Mockito.when(urlCodec.encode(ArgumentMatchers.anyString())).thenThrow(new EncoderException());
		signer.getCanonicalizedResourcePath("/path");
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedQueryString(java.util.Map)}.
	 */
	@Test
	public void testGetCanonicalizedQueryStringEmpty() throws Exception {
		Assert.assertEquals("", signer.getCanonicalizedQueryString(ImmutableMap.of()));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedQueryString(java.util.Map)}.
	 */
	@Test
	public void testGetCanonicalizedQueryString() throws Exception {
		Assert.assertEquals("q1=v1&q2=v2", signer.getCanonicalizedQueryString(ImmutableMap.of("q2", "v2", "q1", "v1")));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getCanonicalizedQueryString(java.util.Map)}.
	 */
	@Test(expected = TechnicalException.class)
	public void testGetCanonicalizedQueryStringException() throws Exception {
		final AWS4SignerBase signer = new AWS4SignerVMForAuthorizationHeader();
		final URLCodec urlCodec = Mockito.mock(URLCodec.class);
		ReflectionTestUtils.setField(signer, "urlCodec", urlCodec);
		Mockito.when(urlCodec.encode(ArgumentMatchers.anyString())).thenThrow(new EncoderException());
		signer.getCanonicalizedQueryString(ImmutableMap.of("q2", "v2", "q1", "v1"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#getStringToSign(java.lang.String, java.lang.String, java.lang.String)}.
	 */
	@Test
	public void testGetStringToSign() throws Exception {
		Assert.assertEquals("AWS4-HMAC-SHA256\ndate\nscope\n1f58b9145b24d108d7ac38887338b3ea3229833b9c1e418250343f907bfd1047",
				signer.getStringToSign("date", "scope", "request"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#hash(java.lang.String)}.
	 */
	@Test
	public void testHash() throws Exception {
		Assert.assertEquals("982d9e3eb996f559e633f4d194def3761d909f5a3b647d1a851fead67c32c9d1", signer.hash("text"));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerBase#sign(java.lang.String, byte[])}.
	 */
	@Test
	public void testSign() throws Exception {
		Assert.assertEquals("ee87b52bc435b6a4aeea07fc1e2499fd4a801487ef675a00a629b7264663d8fa",
				Hex.encodeHexString(signer.sign("text", new byte[]{1, 2, 3, 4, 5})));
	}

}

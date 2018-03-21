/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
/**
 * 
 */
package org.ligoj.app.plugin.vm.aws.auth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test class of {@link AWS4SignerVMForAuthorizationHeader}
 */
public class AWS4SignerVMForAuthorizationHeaderTest {

	/**
	 * signer
	 */
	final private AWS4SignerVMForAuthorizationHeader signer = new AWS4SignerVMForAuthorizationHeader();

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerForAuthorizationHeader#computeSignature(org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery)}.
	 */
	@Test
	public void testComputeSignature() {
		ReflectionTestUtils.setField(signer, "clock", Clock
				.fixed(LocalDateTime.of(2017, 5, 29, 22, 15).toInstant(ZoneOffset.UTC), ZoneOffset.UTC.normalized()));
		final AWS4SignatureQuery signatureQuery = AWS4SignatureQuery.builder().accessKey("awsAccessKey")
				.secretKey("awsSecretKey").region("eu-west-1").method("GET").service("s3").path("path").build();
		Assertions.assertEquals(
				"AWS4-HMAC-SHA256 Credential=awsAccessKey/20170529/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=6a48aa41b25ea6d1b0e636c78ea971de060256ea2a2b2e6b103d6fbf14c7d21a",
				signer.computeSignature(signatureQuery));
	}

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerForAuthorizationHeader#computeSignature(org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery)}.
	 */
	@Test
	public void testComputeSignatureWithBody() {
		ReflectionTestUtils.setField(signer, "clock", Clock
				.fixed(LocalDateTime.of(2017, 5, 29, 22, 15).toInstant(ZoneOffset.UTC), ZoneOffset.UTC.normalized()));
		final AWS4SignatureQuery signatureQuery = AWS4SignatureQuery.builder().accessKey("awsAccessKey")
				.secretKey("awsSecretKey").region("eu-west-1").method("GET").service("s3").path("path").body("body")
				.build();
		Assertions.assertEquals(
				"AWS4-HMAC-SHA256 Credential=awsAccessKey/20170529/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=704a07b30cf11a27123ea3b430680a37ffe311a858496440ab519d0cc5adaa8f",
				signer.computeSignature(signatureQuery));
	}

}

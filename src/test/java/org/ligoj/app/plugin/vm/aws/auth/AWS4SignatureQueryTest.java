/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws.auth;

import java.util.Collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;

/**
 * Test class of {@link AWS4SignatureQuery}
 */
public class AWS4SignatureQueryTest {

	@Test
	public void builderNoHost() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			AWS4SignatureQuery.builder().build();
		});
	}

	@Test
	public void builderNoPath() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			AWS4SignatureQuery.builder().build();
		});
	}

	@Test
	public void builderNoService() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			AWS4SignatureQuery.builder().path("/").build();
		});
	}

	@Test
	public void builderNoRegion() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			AWS4SignatureQuery.builder().path("/").service("ec2").build();
		});
	}

	@Test
	public void builderNoAccessKey() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			AWS4SignatureQuery.builder().path("/").service("ec2").region("eu-west-1").build();
		});
	}

	@Test
	public void builderNoSecretKey() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			AWS4SignatureQuery.builder().path("/").service("ec2").region("eu-west-1").accessKey("--access-key--").build();
		});
	}

	@Test
	public void builder() {
		AWS4SignatureQueryBuilder builder = AWS4SignatureQuery.builder();
		builder.toString();
		builder = builderCommon(builder);
		builder = builder.method("GET");
		builder.toString();
		builder.build();
	}

	@Test
	public void builderNullMethod() {
		AWS4SignatureQueryBuilder builder = AWS4SignatureQuery.builder();
		builder.toString();
		builder = builderCommon(builder);
		AWS4SignatureQueryBuilder builder0 = builder.method(null);
		builder0.toString();
		Assertions.assertThrows(NullPointerException.class, () -> {
			builder0.build();
		});
	}

	private AWS4SignatureQueryBuilder builderCommon(AWS4SignatureQueryBuilder builderParam) {
		AWS4SignatureQueryBuilder builder = builderParam;
		builder.toString();
		builder = builder.path("/");
		builder.toString();
		builder = builder.service("ec2");
		builder.toString();
		builder = builder.region("eu-west-1");
		builder.toString();
		builder = builder.accessKey("--access-key--");
		builder.toString();
		builder = builder.secretKey("--secret-key--");
		builder.toString();
		builder.build();
		builder = builder.body("-BODY-");
		builder.toString();
		builder = builder.headers(Collections.emptyMap());
		builder.toString();
		builder = builder.queryParameters(Collections.emptyMap());
		builder.toString();
		return builder;
	}
}

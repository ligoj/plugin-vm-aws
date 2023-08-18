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
class AWS4SignatureQueryTest {

	@Test
	void builder() {
		var builder = AWS4SignatureQuery.builder();
		Assertions.assertNotNull(builder.toString());
		builder = builderCommon(builder);
		builder = builder.method("GET");
		Assertions.assertNotNull(builder.toString());
		Assertions.assertEquals("s3-eu-west-1.amazonaws.com", builder.service("s3").build().getHost());
		Assertions.assertEquals("ec2.eu-west-1.amazonaws.com", builder.service("ec2").build().getHost());
	}

	private AWS4SignatureQueryBuilder builderCommon(AWS4SignatureQueryBuilder builderParam) {
		var builder = builderParam;
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

package org.ligoj.app.plugin.vm.aws.auth;

import java.util.Collections;

import org.junit.Test;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;

/**
 * Test class of {@link AWS4SignatureQuery}
 */
public class AWS4SignatureQueryTest {

	@Test(expected = NullPointerException.class)
	public void builderNoHost() {
		AWS4SignatureQuery.builder().build();
	}

	@Test(expected = NullPointerException.class)
	public void builderNoPath() {
		AWS4SignatureQuery.builder().host("localhost").build();
	}

	@Test(expected = NullPointerException.class)
	public void builderNoService() {
		AWS4SignatureQuery.builder().host("localhost").path("/").build();
	}

	@Test(expected = NullPointerException.class)
	public void builderNoRegion() {
		AWS4SignatureQuery.builder().host("localhost").path("/").service("ec2").build();
	}

	@Test(expected = NullPointerException.class)
	public void builderNoAccessKey() {
		AWS4SignatureQuery.builder().host("localhost").path("/").service("ec2").region("eu-west-1").build();
	}

	@Test(expected = NullPointerException.class)
	public void builderNoSecretKey() {
		AWS4SignatureQuery.builder().host("localhost").path("/").service("ec2").region("eu-west-1").accessKey("--access-key--").build();
	}

	@Test
	public void builder() {
		AWS4SignatureQueryBuilder builder = AWS4SignatureQuery.builder();
		builder.toString();
		builder = builder.host("localhost");
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
		builder = builder.method("GET");
		builder.toString();
		builder.build();
	}
}

/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.auth;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Getter;

/**
 * Query used to sign AWS V4 API Query
 */
@Builder
@Getter
public class AWS4SignatureQuery {

	/**
	 * query path
	 */
	private String path;

	/**
	 * HTTP method such as "POST", "GET",... Default is "POST"
	 */
	@Builder.Default
	private String method = "POST";

	/**
	 * AWS service name : s3, ec2, ...
	 */
	private String service;
	/**
	 * region name : eu-west-1, ...
	 */
	private String region;
	/**
	 * AWS Access Key
	 */
	private String accessKey;
	/**
	 * AWS Secret Key : I won't give my secret key for the javadoc :)
	 */
	private String secretKey;
	/**
	 * query headers
	 */
	private Map<String, String> headers;
	/**
	 * query parameters
	 */
	private Map<String, String> queryParameters;
	/**
	 * query body
	 */
	private String body;

	/**
	 * Builder class : used to initialize some attributes with default values
	 */
	public static class AWS4SignatureQueryBuilder {
		/**
		 * query headers
		 */
		private Map<String, String> headers = new HashMap<>();

		/**
		 * query parameters
		 */
		private Map<String, String> queryParameters = new HashMap<>();
	}

	/**
	 * Return the corresponding host.
	 *
	 * @return The corresponding host.
	 */
	public String getHost() {
		return getService() + (getService().equals("s3") ? "-" : ".") + getRegion() + ".amazonaws.com";
	}

}

package org.ligoj.app.plugin.vm.aws.auth;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

/**
 * query used to sign AWS V4 API Query
 * 
 * @author alocquet
 */
@Builder
@Getter
public class AWS4SignatureQuery {

	/**
	 * query host
	 */
	@NonNull
	private String host;
	/**
	 * query path
	 */
	@NonNull
	private String path;
	/**
	 * http method : GET, POST, ...
	 */
	@NonNull
	private String httpMethod;
	/**
	 * aws service name : s3, ec2, ...
	 */
	@NonNull
	private String serviceName;
	/**
	 * region name : eu-west-1, ...
	 */
	@NonNull
	private String regionName;
	/**
	 * AWS Access Key
	 */
	@NonNull
	private String awsAccessKey;
	/**
	 * AWS Secret Key : I won't give my secret key for the javadoc :)
	 */
	@NonNull
	private String awsSecretKey;
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
	 * builder class : used to initialize some attributes with defautl values
	 * 
	 * @author alocquet
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

}

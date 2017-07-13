package org.ligoj.app.plugin.vm.aws.auth;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.commons.codec.binary.Hex;

/**
 * AWS4 signer used to sign requests with an 'Authorization' header.
 */
public class AWS4SignerForAuthorizationHeader extends AWS4SignerBase {

	/** SHA256 hash of an empty request body **/
	public static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	/**
	 * format strings for the date/time and date stamps required during signing
	 **/
	public static final String ISO8601BasicFormat = "yyyyMMdd'T'HHmmss'Z'";
	public static final String DateStringFormat = "yyyyMMdd";

	/**
	 * clock used to date query
	 */
	private Clock clock = Clock.systemUTC();

	/**
	 * Computes an AWS4 signature for a request, ready for inclusion as an
	 * 'Authorization' header.
	 *
	 * @param query
	 *            the query
	 * @return The computed authorization string for the request. This value
	 *         needs to be set as the header 'Authorization' on the subsequent
	 *         HTTP request.
	 */
	public String computeSignature(final AWS4SignatureQuery query) {
		// first get the date and time for the subsequent request, and convert
		// to ISO 8601 format for use in signature generation
		final ZonedDateTime now = ZonedDateTime.now(clock);

		final String dateTimeStamp = DateTimeFormatter.ofPattern(ISO8601BasicFormat).format(now);
		final String bodyHash;
		if (query.getBody() == null) {
			bodyHash = EMPTY_BODY_SHA256;
		} else {
			bodyHash = hash(query.getBody());
		}
		// update the headers with required 'x-amz-date' and 'host' values
		query.getHeaders().put("x-amz-date", dateTimeStamp);
		query.getHeaders().put("x-amz-content-sha256", bodyHash);
		query.getHeaders().put("Host", query.getHost());

		// canonicalize the headers; we need the set of header names as well as
		// the
		// names and values to go into the signature process
		final String canonicalizedHeaderNames = getCanonicalizeHeaderNames(query.getHeaders());
		final String canonicalizedHeaders = getCanonicalizedHeaderString(query.getHeaders());

		// if any query string parameters have been supplied, canonicalize them
		final String canonicalizedQueryParameters = getCanonicalizedQueryString(query.getQueryParameters());

		// canonicalize the various components of the request
		final String canonicalRequest = getCanonicalRequest(query.getPath(), query.getHttpMethod(),
				canonicalizedQueryParameters, canonicalizedHeaderNames, canonicalizedHeaders, bodyHash);

		// construct the string to be signed
		final String dateStamp = DateTimeFormatter.ofPattern(DateStringFormat).format(now);
		final String scope = dateStamp + "/" + query.getRegionName() + "/" + query.getServiceName() + "/" + TERMINATOR;
		final String stringToSign = getStringToSign(dateTimeStamp, scope, canonicalRequest);

		// compute the signing key
		final byte[] kSecret = (SCHEME + query.getAwsSecretKey()).getBytes();
		final byte[] kDate = sign(dateStamp, kSecret);
		final byte[] kRegion = sign(query.getRegionName(), kDate);
		final byte[] kService = sign(query.getServiceName(), kRegion);
		final byte[] kSigning = sign(TERMINATOR, kService);
		final byte[] signature = sign(stringToSign, kSigning);

		final String credentialsAuthorizationHeader = "Credential=" + query.getAwsAccessKey() + "/" + scope;
		final String signedHeadersAuthorizationHeader = "SignedHeaders=" + canonicalizedHeaderNames;
		final String signatureAuthorizationHeader = "Signature=" + Hex.encodeHexString(signature);

		final String authorizationHeader = SCHEME + "-" + ALGORITHM + " " + credentialsAuthorizationHeader + ", "
				+ signedHeadersAuthorizationHeader + ", " + signatureAuthorizationHeader;

		return authorizationHeader;
	}
}
/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws.auth;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

/**
 * AWS4 signer used to sign requests with an 'Authorization' header.
 */
@Service
public class AWS4SignerVMForAuthorizationHeader extends AWS4SignerBase {

	/** SHA256 hash of an empty request body **/
	private static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	/**
	 * format strings for the date/time and date stamps required during signing
	 **/
	private static final String ISO8601_FORMAT = "yyyyMMdd'T'HHmmss'Z'";
	private static final String DATE_FORMAT = "yyyyMMdd";

	/**
	 * clock used to date query
	 */
	private final Clock clock = Clock.systemUTC();

	/**
	 * Computes an AWS4 signature for a request, ready for inclusion as an 'Authorization' header.
	 *
	 * @param query the query
	 * @return The computed authorization string for the request. This value needs to be set as the header
	 *         'Authorization' on the subsequent HTTP request.
	 */
	public String computeSignature(final AWS4SignatureQuery query) {
		// first get the date and time for the subsequent request, and convert
		// to ISO 8601 format for use in signature generation
		final var now = ZonedDateTime.now(clock);

		final var dateTimeStamp = DateTimeFormatter.ofPattern(ISO8601_FORMAT).format(now);
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
		// the names and values to go into the signature process
		final var canonicalizedHeaderNames = getCanonicalizeHeaderNames(query.getHeaders());
		final var canonicalizedHeaders = getCanonicalizedHeaderString(query.getHeaders());

		// if any query string parameters have been supplied, canonicalize them
		final var canonicalizedQueryParameters = getCanonicalizedQueryString(query.getQueryParameters());

		// canonicalize the various components of the request
		final var canonicalRequest = getCanonicalRequest(query.getPath(), query.getMethod(),
				canonicalizedQueryParameters, canonicalizedHeaderNames, canonicalizedHeaders, bodyHash);

		// construct the string to be signed
		final var dateStamp = DateTimeFormatter.ofPattern(DATE_FORMAT).format(now);
		final var scope = dateStamp + "/" + query.getRegion() + "/" + query.getService() + "/" + TERMINATOR;
		final var stringToSign = getStringToSign(dateTimeStamp, scope, canonicalRequest);

		// compute the signing key
		final var kSecret = (SCHEME + query.getSecretKey()).getBytes();
		final var kDate = sign(dateStamp, kSecret);
		final var kRegion = sign(query.getRegion(), kDate);
		final var kService = sign(query.getService(), kRegion);
		final var kSigning = sign(TERMINATOR, kService);
		final var signature = sign(stringToSign, kSigning);

		final var credentialsAuthorizationHeader = "Credential=" + query.getAccessKey() + "/" + scope;
		final var signedHeadersAuthorizationHeader = "SignedHeaders=" + canonicalizedHeaderNames;
		final var signatureAuthorizationHeader = "Signature=" + Hex.encodeHexString(signature);

		return SCHEME + "-" + ALGORITHM + " " + credentialsAuthorizationHeader + ", " + signedHeadersAuthorizationHeader
				+ ", " + signatureAuthorizationHeader;
	}
}
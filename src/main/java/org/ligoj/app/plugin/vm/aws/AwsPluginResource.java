package org.ligoj.app.plugin.vm.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.VmServicePlugin;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignerForAuthorizationHeader;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.plugin.AbstractXmlApiToolPluginResource;
import org.ligoj.app.resource.plugin.CurlCacheToken;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * AWS VM resource.
 */
@Path(AwsPluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
public class AwsPluginResource extends AbstractXmlApiToolPluginResource implements VmServicePlugin {

	/**
	 * Plug-in key.
	 */
	public static final String URL = VmResource.SERVICE_URL + "/aws";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * AWS URL entry point.
	 */
	public static final String PARAMETER_URL = KEY + ":url";

	/**
	 * AWS account number, used for login.
	 */
	public static final String PARAMETER_ACCOUNT = KEY + ":account";

	/**
	 * AWS user name.
	 */
	public static final String PARAMETER_USER = KEY + ":user";

	/**
	 * AWS password.
	 */
	public static final String PARAMETER_PASSWORD = KEY + ":password";

	/**
	 * The EC2 identifier.
	 */
	public static final String PARAMETER_VM = KEY + ":vm-id";

	/**
	 * The EC2 Access key.
	 */
	public static final String PARAMETER_ACCESS_KEY = KEY + ":access-key-id";

	/**
	 * The EC2 Secret key.
	 */
	public static final String PARAMETER_SECRET_KEY = KEY + ":secret-access-key";

	@Autowired
	private CurlCacheToken curlCacheToken;

	@Value("${saas.service-vm-vcloud-auth-retries:2}")
	private int retries;

	private AWS4SignerForAuthorizationHeader signer = new AWS4SignerForAuthorizationHeader();

	/**
	 * Cache the API token.
	 */
	protected String authenticate(final String url, final String authentication, final VCloudCurlProcessor processor) {
		return curlCacheToken.getTokenCache(AwsPluginResource.class, url + "##" + authentication, k -> {

			// Authentication request
			final List<CurlRequest> requests = new ArrayList<>();
			requests.add(new CurlRequest(HttpMethod.POST, url, null, VCloudCurlProcessor.LOGIN_CALLBACK,
					"Authorization:Basic " + authentication));
			processor.process(requests);
			return processor.token;
		}, retries, () -> new ValidationJsonException(PARAMETER_URL, "vcloud-login"));
	}

	/**
	 * Prepare an authenticated connection to vCloud. The given processor would
	 * be updated with the security token.
	 */
	protected void authenticate(final Map<String, String> parameters, final VCloudCurlProcessor processor) {
		final String user = parameters.get(PARAMETER_USER);
		final String password = StringUtils.trimToEmpty(parameters.get(PARAMETER_PASSWORD));
		final String account = StringUtils.trimToEmpty(parameters.get(PARAMETER_ACCOUNT));
		final String url = StringUtils.appendIfMissing(parameters.get(PARAMETER_URL), "/") + "sessions";

		// Encode the authentication 'user@organization:password'
		final String authentication = Base64
				.encodeBase64String((user + "@" + account + ":" + password).getBytes(StandardCharsets.UTF_8));

		// Authentication request using cache
		processor.setToken(authenticate(url, authentication, processor));

	}

	/**
	 * Validate the VM configuration.
	 *
	 * @param parameters
	 *            the space parameters.
	 * @return Virtual Machine description.
	 */
	protected Vm validateVm(final Map<String, String> parameters) {

		final String vmId = parameters.get(PARAMETER_VM);
		final String awsAccessKey = parameters.get(PARAMETER_ACCESS_KEY);
		final String awsSecretKey = parameters.get(PARAMETER_SECRET_KEY);

		// Get the VM if exists
		final List<Vm> vms = this.getDescribeInstances(awsAccessKey, awsSecretKey, "eu-west-1", vmId);

		// Check the VM has been found
		if (vms.isEmpty()) {
			// Invalid id
			throw new ValidationJsonException(PARAMETER_VM, "aws-vm", vmId);
		}
		return vms.get(0);
	}

	@Override
	public void link(final int subscription) throws Exception {
		// TODO Validate the virtual machine name
		// validateVm(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Find the virtual machines matching to the given criteria. Look into
	 * virtual machine name only.
	 *
	 * @param node
	 *            the node to be tested with given parameters.
	 * @param criteria
	 *            the search criteria. Case is insensitive.
	 * @return virtual machines.
	 */
	@GET
	@Path("{node:[a-z].*}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<Vm> findAllByName(@PathParam("node") final String node, @PathParam("criteria") final String criteria) {
		// Get the VMs and parse them
		return null;
	}

	/**
	 * Return a snapshot of the console.
	 *
	 * @param subscription
	 *            the valid screenshot of the console.
	 * @return the valid screenshot of the console.
	 */
	@GET
	@Path("{subscription:\\d+}/console.png")
	@Produces("image/png")
	public StreamingOutput getConsole(@PathParam("subscription") final int subscription) {
		final Map<String, String> parameters = subscriptionResource.getParameters(subscription);
		final VCloudCurlProcessor processor = new VCloudCurlProcessor();
		authenticate(parameters, processor);

		// Get the screen thumbnail
		return output -> {
			final String url = StringUtils.appendIfMissing(parameters.get(PARAMETER_URL), "/") + "vApp/vm-"
					+ parameters.get(PARAMETER_VM) + "/screen";
			final CurlRequest curlRequest = new CurlRequest("GET", url, null, (request, response) -> {
				if (response.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
					// Copy the stream
					IOUtils.copy(response.getEntity().getContent(), output);
					output.flush();
				}
				return false;
			});
			processor.process(curlRequest);
		};
	}

	/**
	 * Return the list of available vm
	 *
	 * @param subscription
	 *            the valid screenshot of the console.
	 * @return the valid screenshot of the console.
	 */
	@GET
	@Path("instance")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<INamableBean<String>> findGroupsByName(@QueryParam("accessKey") final String accessKey,
			@QueryParam("secretKey") String secretKey, @QueryParam("search[value]") String criteria) {
		List<Vm> data = getDescribeInstances(accessKey, secretKey, "eu-west-1");
		return data.stream().map(vm -> {
			final INamableBean item = new NamedBean();
			item.setId(vm.getId());
			item.setName(vm.getName());
			return item;
		}).collect(Collectors.toList());
	}

	/**
	 * call aws to obtain the list of available instances for a region.
	 *
	 *
	 * @param awsAccessKey
	 * @param awsSecretKey
	 * @param region
	 * @return
	 */
	public List<Vm> getDescribeInstances(String awsAccessKey, String awsSecretKey, String region) {
		return getDescribeInstances(awsAccessKey, awsSecretKey, region, null);
	}

	/**
	 * call aws to obtain the list of available instances for a region.
	 *
	 *
	 * @param awsAccessKey
	 * @param awsSecretKey
	 * @param region
	 * @param vmId
	 * @return
	 */
	private List<Vm> getDescribeInstances(String awsAccessKey, String awsSecretKey, String region, String vmId) {
		String query = "Action=DescribeInstances&Version=2016-11-15";
		if (!StringUtils.isEmpty(vmId)) {
			query += "&Filter.1.Name=instance-id&Filter.1.Value.1=" + vmId;
		}
		AWS4SignatureQuery signatureQuery = AWS4SignatureQuery.builder().awsAccessKey(awsAccessKey)
				.awsSecretKey(awsSecretKey).httpMethod("POST").path("/").serviceName("ec2")
				.host("ec2." + region + ".amazonaws.com").regionName(region).body(query).build();

		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getHttpMethod(),
				"https://" + signatureQuery.getHost() + signatureQuery.getPath(), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);

		final boolean httpResult = new CurlProcessor().process(request);

		if (httpResult) {
			try {
				return toVms(request.getResponse());
			} catch (SAXException | ParserConfigurationException | IOException | XPathExpressionException e) {

			}
		}
		return Collections.EMPTY_LIST;
	}

	/**
	 * Build a described {@link Vm} bean from a XML VMRecord entry.
	 */
	private Vm toVm(final Element record) {
		final Vm result = new Vm();
		result.setId(record.getElementsByTagName("instanceId").item(0).getTextContent());
		result.setName(record.getElementsByTagName("keyName").item(0).getTextContent());
		result.setDescription(record.getElementsByTagName("instanceType").item(0).getTextContent());
		Element stateElement = (Element) record.getElementsByTagName("instanceState").item(0);
		result.setStatus(stateElement.getElementsByTagName("code").item(0).getTextContent().equals("16")
				? VmStatus.POWERED_ON : VmStatus.POWERED_OFF);

		return result;
	}

	/**
	 * Build described beans from a XML result.
	 */
	private List<Vm> toVms(final String vmAsXml)
			throws SAXException, IOException, ParserConfigurationException, XPathExpressionException {
		final NodeList tags = getNodeInstances(vmAsXml,
				"/DescribeInstancesResponse/reservationSet/item/instancesSet/item");
		return IntStream.range(0, tags.getLength()).mapToObj(tags::item).map(n -> (Element) n).map(this::toVm)
				.collect(Collectors.toList());
	}

	protected NodeList getNodeInstances(final String input, final String tag)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(tag).evaluate(
				parse(IOUtils.toInputStream(ObjectUtils.defaultIfNull(input, ""), StandardCharsets.UTF_8)),
				XPathConstants.NODESET);
	}

	/**
	 * Return a vCloud's resource after an authentication. Return
	 * <code>null</code> when the resource is not found. Authentication will be
	 * done to get the data.
	 */
	protected String getVCloudResource(final Map<String, String> parameters, final String resource) {
		return authenticateAndExecute(parameters, HttpMethod.GET, resource);
	}

	/**
	 * Return a vCloud's resource after an authentication. Return
	 * <code>null</code> when the resource is not found. Authentication is
	 * started from there.
	 */
	protected String authenticateAndExecute(final Map<String, String> parameters, final String method,
			final String resource) {
		final VCloudCurlProcessor processor = new VCloudCurlProcessor();
		authenticate(parameters, processor);
		return execute(processor, method, parameters.get(PARAMETER_URL), resource);
	}

	/**
	 * Return/execute a vCloud resource. Return <code>null</code> when the
	 * resource is not found. Authentication should be proceeded before for
	 * authenticated query.
	 */
	protected String execute(final CurlProcessor processor, final String method, final String url,
			final String resource) {
		// Get the resource using the preempted authentication
		final CurlRequest request = new CurlRequest(method,
				StringUtils.appendIfMissing(url, "/") + StringUtils.removeStart(resource, "/"), null);
		request.setSaveResponse(true);

		// Execute the requests
		processor.process(request);
		processor.close();
		return request.getResponse();
	}

	@Override
	public String getKey() {
		return AwsPluginResource.KEY;
	}

	/**
	 * Check the server is available with administration right.
	 */
	private void validateAdminAccess(final Map<String, String> parameters) throws Exception {
		if (getVersion(parameters) == null) {
			throw new ValidationJsonException(PARAMETER_URL, "vcloud-admin");
		}
	}

	@Override
	public String getVersion(final Map<String, String> parameters) throws Exception {
		return StringUtils.trimToNull(
				getTags(ObjectUtils.defaultIfNull(getVCloudResource(parameters, "/admin"), "<a><Description/></a>"),
						"Description").item(0).getTextContent());
	}

	@Override
	public String getLastVersion() throws Exception {
		// Get the download json from the default repository
		final String portletVersions = new CurlProcessor().get(
				"https://my.vmware.com/web/vmware/downloads?p_p_id=ProductIndexPortlet_WAR_itdownloadsportlet&p_p_lifecycle=2&p_p_resource_id=allProducts");

		// Extract the version from the rw String, because of the non stable
		// content format, but the links
		// Search for : "target":
		// "./info/slug/datacenter_cloud_infrastructure/vmware_vcloud_suite/6_0"
		final int linkIndex = Math.min(ObjectUtils.defaultIfNull(portletVersions, "").indexOf("vmware_vcloud_suite/")
				+ "vmware_vcloud_suite/".length(), portletVersions.length());
		return portletVersions.substring(linkIndex,
				Math.max(portletVersions.indexOf('\"', linkIndex), portletVersions.length()));
	}

	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) throws Exception {
		// Status is UP <=> Administration access is UP (if defined)
		validateAdminAccess(parameters);
		return true;
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final String node, final Map<String, String> parameters)
			throws Exception {
		final SubscriptionStatusWithData status = new SubscriptionStatusWithData();
		status.put("vm", validateVm(parameters));
		return status;
	}

	@Override
	public void execute(final int subscription, final VmOperation operation) {
		// STOP :
		// http://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_StopInstances.html
		// https://ec2.amazonaws.com/?Action=StopInstances&InstanceId.1=_ID_&AUTHPARAMS

		// START :
		// http://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_StartInstances.html
		// https://ec2.amazonaws.com/?Action=StartInstances&InstanceId.1=_ID_&AUTHPARAMS

		// http://docs.aws.amazon.com/AWSEC2/latest/APIReference/Query-Requests.html
		// http://docs.aws.amazon.com/general/latest/gr/signature-v4-test-suite.html
		// http://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-examples-using-sdks.html
		// https://github.com/aws/aws-sdk-java
		// http://docs.aws.amazon.com/AWSEC2/latest/APIReference/CommonParameters.html
		// http://docs.aws.amazon.com/general/latest/gr/signature-version-4.html

		// http://docs.aws.amazon.com/general/latest/gr/sigv4-signed-request-examples.html

		//
		// You create a canonical request.
		// You use the canonical request and some other information to create a
		// string to sign.
		// You use your AWS secret access key to derive a signing key, and then
		// use that signing key and the string to sign to create a signature.
		// You add the resulting signature to the HTTP request in a header or as
		// a query string parameter.
	}

}

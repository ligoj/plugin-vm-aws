package org.ligoj.app.plugin.vm.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.VmServicePlugin;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignerVMForAuthorizationHeader;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.plugin.AbstractXmlApiToolPluginResource;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * AWS VM resource.
 */
@Path(VmAwsPluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
public class VmAwsPluginResource extends AbstractXmlApiToolPluginResource implements VmServicePlugin {

	/**
	 * Plug-in key.
	 */
	public static final String URL = VmResource.SERVICE_URL + "/aws";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_ACCESS_KEY_ID = KEY + ":access-key-id";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_SECRET_ACCESS_KEY = KEY + ":secret-access-key";

	/**
	 * AWS Account Id.
	 */
	public static final String PARAMETER_ACCOUNT = KEY + ":account";

	/**
	 * The EC2 identifier.
	 */
	public static final String PARAMETER_INSTANCE_ID = KEY + ":instance-id";

	/**
	 * Configuration key used for {@link #DEFAULT_REGION}
	 */
	public static final String CONF_REGION = KEY + ":region";

	/**
	 * The default region, fixed for now.
	 */
	private static final String DEFAULT_REGION = "eu-west-1";

	/**
	 * VM operation mapping.
	 * 
	 * STOP :<br>
	 * http://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_StopInstances.html
	 * https://ec2.amazonaws.com/?Action=StopInstances&InstanceId.1=_ID_&AUTHPARAMS
	 * 
	 * START :<br>
	 * http://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_StartInstances.html
	 * https://ec2.amazonaws.com/?Action=StartInstances&InstanceId.1=_ID_&AUTHPARAMS
	 * 
	 */
	public static final Map<VmOperation, String> OPERATION_TO_ACTION = new EnumMap<>(VmOperation.class);
	static {
		OPERATION_TO_ACTION.put(VmOperation.OFF, "StopInstances&Force=true");
		OPERATION_TO_ACTION.put(VmOperation.SHUTDOWN, "StopInstances");
		OPERATION_TO_ACTION.put(VmOperation.ON, "StartInstances");
		OPERATION_TO_ACTION.put(VmOperation.REBOOT, "RebootInstances");
		OPERATION_TO_ACTION.put(VmOperation.RESET, "RebootInstances");
	}

	@Autowired
	private AWS4SignerVMForAuthorizationHeader signer;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	private ConfigurationResource configuration;

	/**
	 * Create Curl request for AWS service. Initialize default values for
	 * awsAccessKey, awsSecretKey and regionName and compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used
	 *            for this call (headers, parameters, host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest prepareCallAWSService(final AWS4SignatureQueryBuilder signatureBuilder, final int subscription) {
		return prepareCallAWSService(signatureBuilder, subscriptionResource.getParameters(subscription));
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for
	 * awsAccessKey, awsSecretKey and regionName and compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used
	 *            for this call (headers, parameters, host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest prepareCallAWSService(final AWS4SignatureQueryBuilder signatureBuilder, final Map<String, String> parameters) {
		final AWS4SignatureQuery signatureQuery = signatureBuilder.awsAccessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.awsSecretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).regionName(getRegion()).build();
		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getHttpMethod(),
				"https://" + signatureQuery.getHost() + signatureQuery.getPath(), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	/**
	 * Validate the VM configuration.
	 *
	 * @param parameters
	 *            the space parameters.
	 * @return Virtual Machine description.
	 */
	protected Vm validateVm(final Map<String, String> parameters)
			throws ValidationJsonException, XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final String instanceId = parameters.get(PARAMETER_INSTANCE_ID);

		// Get the VM if exists
		return this.getDescribeInstances(parameters, "&Filter.1.Name=instance-id&Filter.1.Value.1=" + instanceId).stream().findFirst()
				.orElseThrow(() -> new ValidationJsonException(PARAMETER_INSTANCE_ID, "aws-vm", instanceId));
	}

	@Override
	public void link(final int subscription) throws Exception {
		validateVm(subscriptionResource.getParameters(subscription));
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
	public List<Vm> findAllByName(@PathParam("node") final String node, @PathParam("criteria") final String criteria)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		// Check the node exists
		if (nodeRepository.findOneVisible(node, securityHelper.getLogin()) == null) {
			return Collections.emptyList();
		}

		// Get the VMs and parse them
		return this.getDescribeInstances(pvResource.getNodeParameters(node), "&Filter.1.Name=tag:Name&Filter.1.Value.1=" + criteria);
	}

	/**
	 * call aws to obtain the list of available instances for a region.
	 *
	 *
	 * @param parameters
	 *            Subscription parameters.
	 * @param region
	 * @return
	 */
	public List<Vm> getDescribeInstances(final Map<String, String> parameters)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		return getDescribeInstances(parameters, null);
	}

	/**
	 * Get all instances visible for given AWS access key.
	 *
	 *
	 * @param parameters
	 *            Subscription parameters.
	 * @param filter
	 *            Optional instance identifier to find. For sample :
	 *            "&Filter.1.Name=instance-id&Filter.1.Value.1=my_insance_id"
	 * @return The matching instances.
	 */
	private List<Vm> getDescribeInstances(final Map<String, String> parameters, final String filter)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		String query = "Action=DescribeInstances&Version=2016-11-15";
		if (StringUtils.isNotEmpty(filter)) {
			query += filter;
		}
		final String response = StringUtils.defaultIfEmpty(processEC2(parameters, query),
				"<DescribeInstancesResponse><reservationSet><item><instancesSet></instancesSet></item></reservationSet></DescribeInstancesResponse>");
		return toVms(response);
	}

	/**
	 * Build a described {@link Vm} bean from a XML VMRecord entry.
	 */
	private Vm toVm(final Element record) {
		final Vm result = new Vm();
		result.setId(record.getElementsByTagName("instanceId").item(0).getTextContent());
		result.setName(record.getElementsByTagName("keyName").item(0).getTextContent());
		result.setDescription(record.getElementsByTagName("instanceType").item(0).getTextContent());
		final Element stateElement = (Element) record.getElementsByTagName("instanceState").item(0);
		result.setStatus(stateElement.getElementsByTagName("code").item(0).getTextContent().equals("16") ? VmStatus.POWERED_ON
				: VmStatus.POWERED_OFF);
		return result;
	}

	/**
	 * Build described beans from a XML result.
	 */
	private List<Vm> toVms(final String vmAsXml) throws SAXException, IOException, ParserConfigurationException, XPathExpressionException {
		final NodeList tags = getNodeInstances(vmAsXml, "/DescribeInstancesResponse/reservationSet/item/instancesSet/item");
		return IntStream.range(0, tags.getLength()).mapToObj(tags::item).map(n -> (Element) n).map(this::toVm).collect(Collectors.toList());
	}

	protected NodeList getNodeInstances(final String input, final String tag)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(tag).evaluate(
				parse(IOUtils.toInputStream(ObjectUtils.defaultIfNull(input, ""), StandardCharsets.UTF_8)), XPathConstants.NODESET);
	}

	/**
	 * Return/execute a vCloud resource. Return <code>null</code> when the
	 * resource is not found. Authentication should be proceeded before for
	 * authenticated query.
	 */
	protected String execute(final CurlProcessor processor, final String method, final String url, final String resource) {
		// Get the resource using the preempted authentication
		final CurlRequest request = new CurlRequest(method, StringUtils.appendIfMissing(url, "/") + StringUtils.removeStart(resource, "/"),
				null);
		request.setSaveResponse(true);

		// Execute the requests
		processor.process(request);
		processor.close();
		return request.getResponse();
	}

	@Override
	public String getKey() {
		return VmAwsPluginResource.KEY;
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param subscription
	 *            subscription
	 * @return true if aws connection is up
	 * @throws Exception
	 */
	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) throws Exception {
		// Status is UP <=> Administration access is UP (if defined)
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().httpMethod("GET").serviceName("s3")
				.host("s3-" + getRegion() + ".amazonaws.com").path("/");
		return new CurlProcessor().process(prepareCallAWSService(signatureQueryBuilder, parameters));
	}

	@Override
	public void delete(int subscription, boolean remoteData) throws Exception {
		// No custom data by default
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final String node, final Map<String, String> parameters) throws Exception {
		final SubscriptionStatusWithData status = new SubscriptionStatusWithData();
		status.put("vm", validateVm(parameters));
		return status;
	}

	@Override
	public void execute(final int subscription, final VmOperation operation) {
		if (Optional.ofNullable(OPERATION_TO_ACTION.get(operation))
				.map(a -> processEC2(subscription, p -> "Action=" + a + "&InstanceId.1=" + p.get(PARAMETER_INSTANCE_ID)))
				.orElse(null) == null) {
			// The result is not correct
			throw new BusinessException("vm-operation-execute");
		}
	}

	private String processEC2(final Map<String, String> parameters, final String query) {
		final AWS4SignatureQuery signatureQuery = AWS4SignatureQuery.builder().awsAccessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.awsSecretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).httpMethod("POST").path("/").serviceName("ec2")
				.host("ec2." + getRegion() + ".amazonaws.com").regionName(getRegion()).body(query).build();

		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getHttpMethod(),
				"https://" + signatureQuery.getHost() + signatureQuery.getPath(), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		new CurlProcessor().process(request);
		return request.getResponse();
	}

	private String processEC2(final int subscription, final Function<Map<String, String>, String> queryProvider) {
		final Map<String, String> parameters = pvResource.getSubscriptionParameters(subscription);
		return processEC2(parameters, queryProvider.apply(parameters));
	}

	protected String getRegion() {
		return configuration.get(CONF_REGION, DEFAULT_REGION);
	}

}

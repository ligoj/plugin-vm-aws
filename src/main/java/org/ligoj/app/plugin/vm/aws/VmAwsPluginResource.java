package org.ligoj.app.plugin.vm.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignerVMForAuthorizationHeader;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.plugin.AbstractXmlApiToolPluginResource;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.csv.CsvForBean;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import lombok.extern.slf4j.Slf4j;

/**
 * AWS VM resource.
 */
@Path(VmAwsPluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class VmAwsPluginResource extends AbstractXmlApiToolPluginResource implements VmServicePlugin, InitializingBean {

	private static final String API_VERSION = "2016-11-15";

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
	public static final String PARAMETER_INSTANCE_ID = KEY + ":id";

	/**
	 * Configuration key used for {@link #DEFAULT_REGION}
	 */
	public static final String CONF_REGION = KEY + ":region";

	/**
	 * The default region, fixed for now.
	 */
	private static final String DEFAULT_REGION = "eu-west-1";

	/**
	 * EC2 state for terminated.
	 */
	private static final int STATE_TERMINATED = 80;

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
	/**
	 * VM code to {@link VmStatus} mapping.
	 */
	public static final Map<Integer, VmStatus> CODE_TO_STATUS = new HashMap<>();
	static {
		CODE_TO_STATUS.put(16, VmStatus.POWERED_ON);
		CODE_TO_STATUS.put(48, VmStatus.POWERED_OFF);
		CODE_TO_STATUS.put(STATE_TERMINATED, VmStatus.POWERED_OFF); // TERMINATED
		CODE_TO_STATUS.put(0, VmStatus.POWERED_ON); // PENDING - BUSY
		CODE_TO_STATUS.put(32, VmStatus.POWERED_OFF); // SHUTTING_DOWN - BUSY
		CODE_TO_STATUS.put(64, VmStatus.POWERED_OFF); // STOPPING - BUSY
	}
	/**
	 * VM busy AWS state codes
	 */
	public static final int[] BUSY_CODES = { 0, 32, 64 };

	@Autowired
	private AWS4SignerVMForAuthorizationHeader signer;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private CsvForBean csvForBean;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	/**
	 * Well known instance types with details and load on initialization.
	 * 
	 * @see "csv/instance-type-details.csv"
	 */
	private Map<String, InstanceType> instanceTypes;

	/**
	 * Validate the VM configuration.
	 *
	 * @param parameters
	 *            the space parameters.
	 * @return Virtual Machine description.
	 */
	protected Vm validateVm(final Map<String, String> parameters) throws Exception {
		final String instanceId = parameters.get(PARAMETER_INSTANCE_ID);

		// Get the VM if exists
		return this.getDescribeInstances(parameters, "&Filter.1.Name=instance-id&Filter.1.Value.1=" + instanceId).stream().findFirst()
				.orElseThrow(() -> new ValidationJsonException(PARAMETER_INSTANCE_ID, "aws-instance-id", instanceId));
	}

	@Override
	public void link(final int subscription) throws Exception {
		validateVm(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Find the virtual machines matching to the given criteria. Look into
	 * virtual machine name and identifier.
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
	public List<Vm> findAllByNameOrId(@PathParam("node") final String node, @PathParam("criteria") final String criteria)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		// Check the node exists
		if (nodeRepository.findOneVisible(node, securityHelper.getLogin()) == null) {
			return Collections.emptyList();
		}

		// Get all VMs and then filter by its name or id
		return this.getDescribeInstances(pvResource.getNodeParameters(node), "").stream().filter(
				vm -> StringUtils.containsIgnoreCase(vm.getName(), criteria) || StringUtils.containsIgnoreCase(vm.getId(), criteria))
				.sorted().collect(Collectors.toList());
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
		String query = "Action=DescribeInstances";
		if (StringUtils.isNotEmpty(filter)) {
			query += filter;
		}
		final String response = StringUtils.defaultIfEmpty(processEC2(parameters, query),
				"<DescribeInstancesResponse><reservationSet><item><instancesSet></instancesSet></item></reservationSet></DescribeInstancesResponse>");
		return toVms(response);
	}

	/**
	 * Build described beans from a XML result.
	 */
	private List<Vm> toVms(final String vmAsXml) throws SAXException, IOException, ParserConfigurationException, XPathExpressionException {
		final NodeList items = getXmlTags(vmAsXml, "/DescribeInstancesResponse/reservationSet/item/instancesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> toVm((Element) n)).collect(Collectors.toList());
	}

	/**
	 * Build a described {@link Vm} bean from a XML VMRecord entry.
	 */
	private Vm toVm(final Element record) {
		final Vm result = new Vm();
		result.setId(getTagText(record, "instanceId"));
		result.setName(Objects.toString(getName(record), result.getId()));
		result.setDescription(getResourceTag(record, "description"));
		final int state = getEc2State(record);
		result.setStatus(CODE_TO_STATUS.get(state));
		result.setBusy(Arrays.binarySearch(BUSY_CODES, state) >= 0);
		result.setContainerName(getTagText(record, "vpcId"));

		final InstanceType type = instanceTypes.get(getTagText(record, "instanceType"));
		// Instance type details
		result.setMemoryMB(Optional.ofNullable(type).map(InstanceType::getRam).map(m -> (int) (m * 1024d)).orElse(0));
		result.setNumberOfCpus(Optional.ofNullable(type).map(InstanceType::getCpu).orElse(0));
		result.setDeployed(state != STATE_TERMINATED);
		return result;
	}

	private int getEc2State(final Element record) {
		return getEc2State(record, "instanceState");
	}

	private int getEc2State(final Element record, final String tag) {
		final Element stateElement = (Element) record.getElementsByTagName(tag).item(0);
		return Integer.valueOf(getTagText(stateElement, "code"));
	}

	/**
	 * Return XML tag text content
	 */
	private String getTagText(final Element element, final String tag) {
		return element.getElementsByTagName(tag).item(0).getTextContent();
	}

	/**
	 * Return the resource tag value or <code>null</code>
	 */
	private String getResourceTag(final Element record, final String name) {
		final NodeList tags = ((Element) record.getElementsByTagName("tagSet").item(0)).getElementsByTagName("item");
		for (int index = 0; index < tags.getLength(); index++) {
			final Element tag = (Element) tags.item(index);
			if (getTagText(tag, "key").equalsIgnoreCase(name)) {
				return getTagText(tag, "value");
			}
		}

		// No tag found
		return null;
	}

	/**
	 * Return the tag "name" value or <code>null</code>
	 */
	private String getName(final Element record) {
		return getResourceTag(record, "name");
	}

	protected NodeList getXmlTags(final String input, final String expression)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final XPath xPath = XPathFactory.newInstance().newXPath();
		return (NodeList) xPath.compile(expression).evaluate(
				parse(IOUtils.toInputStream(ObjectUtils.defaultIfNull(input, ""), StandardCharsets.UTF_8)), XPathConstants.NODESET);
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
	 * @return <code>true</code> if AWS connection is up
	 */
	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) throws Exception {
		return validateAccess(parameters);
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node, final Map<String, String> parameters) throws Exception { // NOSONAR
		final SubscriptionStatusWithData status = new SubscriptionStatusWithData();
		status.put("vm", validateVm(parameters));
		status.put("schedules", vmScheduleRepository.countBySubscription(subscription));
		return status;
	}

	@Override
	public void execute(final int subscription, final VmOperation operation) throws Exception {
		final String response = Optional.ofNullable(OPERATION_TO_ACTION.get(operation))
				.map(a -> processEC2(subscription, p -> "Action=" + a + "&InstanceId.1=" + p.get(PARAMETER_INSTANCE_ID))).orElse(null);
		if (!logTransitionState(response)) {
			// The result is not correct
			throw new BusinessException("vm-operation-execute");
		}
	}

	/**
	 * Log the instance state transition and indicates the transition was a
	 * success.
	 * 
	 * @param response
	 *            the EC2 response markup.
	 * @return <code>true</code> when the transition succeed.
	 */
	private boolean logTransitionState(final String response) throws Exception {
		final NodeList items = getXmlTags(ObjectUtils.defaultIfNull(response, "<a></a>"),
				"/*[contains(local-name(),'InstancesResponse')]/instancesSet/item");
		return IntStream.range(0, items.getLength())
				.mapToObj(items::item).map(n -> (Element) n).peek(e -> log.info("Instance {} goes from {} to {} state",
						getTagText(e, "instanceId"), getEc2State(e, "previousState"), getEc2State(e, "currentState")))
				.findFirst().isPresent();

	}

	private String processEC2(final int subscription, final Function<Map<String, String>, String> queryProvider) {
		final Map<String, String> parameters = pvResource.getSubscriptionParameters(subscription);
		return processEC2(parameters, queryProvider.apply(parameters));
	}

	private String processEC2(final Map<String, String> parameters, final String query) {
		final AWS4SignatureQueryBuilder signatureQuery = AWS4SignatureQuery.builder().accessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).path("/").service("ec2")
				.host("ec2." + getRegion() + ".amazonaws.com").body(query + "&Version=" + API_VERSION);
		final CurlRequest request = newRequest(signatureQuery, parameters);
		new CurlProcessor().process(request);
		return request.getResponse();
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
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final int subscription) {
		return newRequest(signatureBuilder, subscriptionResource.getParameters(subscription));
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
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final Map<String, String> parameters) {
		final AWS4SignatureQuery signatureQuery = signatureBuilder.accessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).region(getRegion()).build();
		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getMethod(),
				"https://" + signatureQuery.getHost() + signatureQuery.getPath(), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	/**
	 * Return the default region for this plug-in.
	 */
	protected String getRegion() {
		return configuration.get(CONF_REGION, DEFAULT_REGION);
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param parameters
	 *            Subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	protected boolean validateAccess(final Map<String, String> parameters) throws Exception {
		// Call S3 ls service
		// TODO Use EC2 instead of S3
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().method("GET").service("s3")
				.host("s3-" + getRegion() + ".amazonaws.com").path("/");
		return new CurlProcessor().process(newRequest(signatureQueryBuilder, parameters));
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		instanceTypes = csvForBean.toBean(InstanceType.class, "csv/instance-type-details.csv").stream()
				.collect(Collectors.toMap(InstanceType::getId, Function.identity()));

	}
}

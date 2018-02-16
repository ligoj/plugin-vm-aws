package org.ligoj.app.plugin.vm.aws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import javax.xml.xpath.XPathExpressionException;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.plugin.vm.VmNetwork;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.VmServicePlugin;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignerVMForAuthorizationHeader;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.plugin.vm.snapshot.Snapshot;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.app.resource.plugin.XmlUtils;
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
public class VmAwsPluginResource extends AbstractToolPluginResource
		implements VmServicePlugin, InitializingBean, Snapshotting {

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
	private static final int STATE_TERMINATED = 48;

	/**
	 * VM operation mapping.
	 * 
	 * @see <a href="http://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_StopInstances.html">StopInstances</a>
	 * @see <a href="http://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_StartInstances.html">StartInstances</a>
	 */
	private static final Map<VmOperation, String> OPERATION_TO_ACTION = new EnumMap<>(VmOperation.class);
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
	private static final Map<Integer, VmStatus> CODE_TO_STATUS = new HashMap<>();
	static {
		CODE_TO_STATUS.put(16, VmStatus.POWERED_ON);
		CODE_TO_STATUS.put(STATE_TERMINATED, VmStatus.POWERED_OFF); // TERMINATED
		CODE_TO_STATUS.put(80, VmStatus.POWERED_OFF);
		CODE_TO_STATUS.put(0, VmStatus.POWERED_ON); // PENDING - BUSY
		CODE_TO_STATUS.put(32, VmStatus.POWERED_OFF); // SHUTTING_DOWN - BUSY
		CODE_TO_STATUS.put(64, VmStatus.POWERED_OFF); // STOPPING - BUSY
	}
	/**
	 * VM busy AWS state codes
	 */
	private static final int[] BUSY_CODES = { 0, 32, 64 };

	@Autowired
	private AWS4SignerVMForAuthorizationHeader signer;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	protected VmAwsSnapshotResource snapshotResource;

	@Autowired
	private CsvForBean csvForBean;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	protected XmlUtils xml;

	/**
	 * Well known instance types with details and load on initialization.
	 * 
	 * @see "csv/instance-type-details.csv"
	 */
	private Map<String, InstanceType> instanceTypes;

	@Override
	public AwsVm getVmDetails(final Map<String, String> parameters) throws Exception {
		final String instanceId = parameters.get(PARAMETER_INSTANCE_ID);

		// Get the VM if exists
		return this
				.getDescribeInstances(parameters, "&Filter.1.Name=instance-id&Filter.1.Value.1=" + instanceId,
						this::toVmDetails)
				.stream().findFirst()
				.orElseThrow(() -> new ValidationJsonException(PARAMETER_INSTANCE_ID, "aws-instance-id", instanceId));
	}

	@Override
	public void link(final int subscription) throws Exception {
		getVmDetails(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Find the virtual machines matching to the given criteria. Look into virtual machine name and identifier.
	 *
	 * @param node
	 *            the node to be tested with given parameters.
	 * @param criteria
	 *            the search criteria. Case is insensitive.
	 * @return virtual machines.
	 */
	@GET
	@Path("{node:service:.+}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<AwsVm> findAllByNameOrId(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		// Check the node exists
		if (nodeRepository.findOneVisible(node, securityHelper.getLogin()) == null) {
			return Collections.emptyList();
		}

		// Get all VMs and then filter by its name or id
		// Note : AWS does not support RegExp on tag
		return this.getDescribeInstances(pvResource.getNodeParameters(node), "", this::toVm).stream()
				.filter(vm -> StringUtils.containsIgnoreCase(vm.getName(), criteria)
						|| StringUtils.containsIgnoreCase(vm.getId(), criteria))
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
	 * @param parser
	 *            The mapper from {@link Element} to {@link AwsVm}.
	 * @return The matching instances.
	 */
	private List<AwsVm> getDescribeInstances(final Map<String, String> parameters, final String filter,
			final Function<Element, AwsVm> parser)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		String query = "Action=DescribeInstances";
		if (StringUtils.isNotEmpty(filter)) {
			query += filter;
		}
		final String response = StringUtils.defaultIfEmpty(processEC2(parameters, query),
				"<DescribeInstancesResponse><reservationSet><item><instancesSet></instancesSet></item></reservationSet></DescribeInstancesResponse>");
		return toVms(response, parser);
	}

	/**
	 * Build described beans from a XML result.
	 */
	private List<AwsVm> toVms(final String vmAsXml, final Function<Element, AwsVm> parser)
			throws SAXException, IOException, ParserConfigurationException, XPathExpressionException {
		final NodeList items = xml.getXpath(vmAsXml,
				"/DescribeInstancesResponse/reservationSet/item/instancesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> parser.apply((Element) n))
				.collect(Collectors.toList());
	}

	/**
	 * Build a described {@link AwsVm} bean from a XML VMRecord entry.
	 */
	private AwsVm toVm(final Element record) {
		final AwsVm result = new AwsVm();
		result.setId(xml.getTagText(record, "instanceId"));
		result.setName(Objects.toString(getName(record), result.getId()));
		result.setDescription(getResourceTag(record, "description"));
		final int state = getEc2State(record);
		result.setStatus(CODE_TO_STATUS.get(state));
		result.setBusy(Arrays.binarySearch(BUSY_CODES, state) >= 0);
		result.setVpc(xml.getTagText(record, "vpcId"));
		result.setAz(xml.getTagText((Element) record.getElementsByTagName("placement").item(0), "availabilityZone"));

		final InstanceType type = instanceTypes.get(xml.getTagText(record, "instanceType"));
		// Instance type details
		result.setRam(Optional.ofNullable(type).map(InstanceType::getRam).map(m -> (int) (m * 1024d)).orElse(0));
		result.setCpu(Optional.ofNullable(type).map(InstanceType::getCpu).orElse(0));
		result.setDeployed(result.getStatus() == VmStatus.POWERED_ON);
		return result;
	}

	/**
	 * Build a described {@link AwsVm} bean from a XML VMRecord entry.
	 */
	private AwsVm toVmDetails(final Element record) {
		final AwsVm result = toVm(record);

		// Network details
		result.setNetworks(new ArrayList<VmNetwork>());

		// Get network data for each network references
		addNetworkDetails(record, result.getNetworks());
		return result;
	}

	/**
	 * Fill the given VM networks with its network details.
	 */
	private void addNetworkDetails(final Element networkNode, final Collection<VmNetwork> networks) {
		// Private IP (optional)
		addNetworkDetails(networkNode, networks, "private", "privateIpAddress", "privateDnsName");

		// Public IP (optional)
		addNetworkDetails(networkNode, networks, "public", "ipAddress", "dnsName");
	}

	/**
	 * Fill the given VM networks with a specific network details.
	 */
	private void addNetworkDetails(final Element networkNode, final Collection<VmNetwork> networks, final String type,
			final String ipAttr, final String dnsAttr) {
		// When IP is available, add the corresponding network
		Optional.ofNullable(xml.getTagText(networkNode, ipAttr))
				.ifPresent(i -> networks.add(new VmNetwork(type, i, xml.getTagText(networkNode, dnsAttr))));
	}

	private int getEc2State(final Element record) {
		return getEc2State(record, "instanceState");
	}

	private int getEc2State(final Element record, final String tag) {
		final Element stateElement = (Element) record.getElementsByTagName(tag).item(0);
		return Integer.valueOf(xml.getTagText(stateElement, "code"));
	}

	@Override
	public String getKey() {
		return VmAwsPluginResource.KEY;
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	@Override
	public boolean checkStatus(final Map<String, String> parameters) {
		return validateAccess(parameters);
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) throws Exception { // NOSONAR
		final SubscriptionStatusWithData status = new SubscriptionStatusWithData();
		status.put("vm", getVmDetails(parameters));
		status.put("schedules", vmScheduleRepository.countBySubscription(subscription));
		return status;
	}

	@Override
	public void execute(final int subscription, final VmOperation operation)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final String response = Optional.ofNullable(OPERATION_TO_ACTION.get(operation)).map(
				a -> processEC2(subscription, p -> "Action=" + a + "&InstanceId.1=" + p.get(PARAMETER_INSTANCE_ID)))
				.orElse(null);
		if (!logTransitionState(response)) {
			// The result is not correct
			throw new BusinessException("vm-operation-execute");
		}
	}

	/**
	 * Log the instance state transition and indicates the transition was a success.
	 * 
	 * @param response
	 *            the EC2 response markup.
	 * @return <code>true</code> when the transition succeed.
	 */
	private boolean logTransitionState(final String response)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final NodeList items = xml.getXpath(ObjectUtils.defaultIfNull(response, "<a></a>"),
				"/*[contains(local-name(),'InstancesResponse')]/instancesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> (Element) n)
				.peek(e -> log.info("Instance {} goes from {} to {} state", xml.getTagText(e, "instanceId"),
						getEc2State(e, "previousState"), getEc2State(e, "currentState")))
				.findFirst().isPresent();

	}

	/**
	 * Return the default region for API call by this plug-in.
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
	protected boolean validateAccess(final Map<String, String> parameters) {
		// Call S3 ls service
		// TODO Use EC2 instead of S3
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().method("GET").service("s3")
				.host("s3-" + getRegion() + ".amazonaws.com").path("/");
		return new CurlProcessor().process(newRequest(signatureQueryBuilder, parameters));
	}

	@Override
	public void afterPropertiesSet() throws IOException {
		instanceTypes = csvForBean.toBean(InstanceType.class, "csv/instance-type-details.csv").stream()
				.collect(Collectors.toMap(InstanceType::getId, Function.identity()));

	}

	/**
	 * Return the resource tag value or <code>null</code>
	 */
	protected String getResourceTag(final Element record, final String name) {
		return Optional.ofNullable(record.getElementsByTagName("tagSet").item(0))
				.map(n -> ((Element) n).getElementsByTagName("item"))
				.map(n -> IntStream.range(0, n.getLength()).mapToObj(n::item).map(t -> (Element) t)
						.filter(t -> xml.getTagText(t, "key").equalsIgnoreCase(name))
						.map(t -> xml.getTagText(t, "value")).findFirst().orElse(null))
				.orElse(null);
	}

	/**
	 * Return the tag "name" value or <code>null</code>
	 */
	protected String getName(final Element record) {
		return getResourceTag(record, "name");
	}

	protected String processEC2(final int subscription, final Function<Map<String, String>, String> queryProvider) {
		final Map<String, String> parameters = pvResource.getSubscriptionParameters(subscription);
		return processEC2(parameters, queryProvider.apply(parameters));
	}

	private String processEC2(final Map<String, String> parameters, final String query) {
		final AWS4SignatureQueryBuilder signatureQuery = AWS4SignatureQuery.builder()
				.accessKey(parameters.get(VmAwsPluginResource.PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(VmAwsPluginResource.PARAMETER_SECRET_ACCESS_KEY)).path("/").service("ec2")
				.host("ec2." + getRegion() + ".amazonaws.com")
				.body(query + "&Version=" + VmAwsPluginResource.API_VERSION);
		final CurlRequest request = newRequest(signatureQuery, parameters);
		new CurlProcessor().process(request);
		return request.getResponse();
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for awsAccessKey, awsSecretKey and regionName and
	 * compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used for this call (headers, parameters,
	 *            host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final int subscription) {
		return newRequest(signatureBuilder, subscriptionResource.getParameters(subscription));
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for awsAccessKey, awsSecretKey and regionName and
	 * compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used for this call (headers, parameters,
	 *            host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder,
			final Map<String, String> parameters) {
		final AWS4SignatureQuery signatureQuery = signatureBuilder
				.accessKey(parameters.get(VmAwsPluginResource.PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(VmAwsPluginResource.PARAMETER_SECRET_ACCESS_KEY)).region(getRegion()).build();
		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getMethod(),
				"https://" + signatureQuery.getHost() + signatureQuery.getPath(), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	@Override
	public void snapshot(final int subscription, final Map<String, String> parameters,
			final VmSnapshotStatus transientTask) throws Exception {
		snapshotResource.create(subscription, parameters, transientTask);
	}

	@Override
	public List<Snapshot> findAllSnapshots(final int subscription, final String criteria) {
		return snapshotResource.findAllByNameOrId(subscription, StringUtils.trimToEmpty(criteria));
	}

	@Override
	public void completeStatus(final VmSnapshotStatus task) {
		snapshotResource.completeStatus(task);
	}

	@Override
	public void delete(int subscription, Map<String, String> parameters, VmSnapshotStatus transientTask)
			throws Exception {
		snapshotResource.delete(subscription, parameters, transientTask);
	}

}

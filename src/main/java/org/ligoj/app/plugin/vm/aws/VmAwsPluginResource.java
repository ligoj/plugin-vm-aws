/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.plugin.vm.VmNetwork;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignerVMForAuthorizationHeader;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.execution.VmExecutionServicePlugin;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.plugin.vm.snapshot.Snapshot;
import org.ligoj.app.plugin.vm.snapshot.Snapshotting;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.plugin.XmlUtils;
import org.ligoj.bootstrap.core.csv.CsvForBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlRequest;
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

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * AWS VM resource.
 */
@Path(VmAwsPluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class VmAwsPluginResource extends AbstractToolPluginResource
		implements VmExecutionServicePlugin, InitializingBean, Snapshotting {

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
	 * AWS Account ID.
	 */
	public static final String PARAMETER_ACCOUNT = KEY + ":account";

	/**
	 * AWS Region API Id.
	 */
	public static final String PARAMETER_REGION = KEY + ":region";

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
	protected ConfigurationResource configuration;

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

	/**
	 * Fill the given VM networks with its network details.
	 *
	 * @param networkNode The network XML node.
	 * @param networks    The target collection.
	 */
	protected void addNetworkDetails(final Element networkNode, final Collection<VmNetwork> networks) {
		// Private IP (optional)
		addNetworkDetails(networkNode, networks, "private", "privateIpAddress", "privateDnsName");

		// Public IP (optional)
		addNetworkDetails(networkNode, networks, "public", "ipAddress", "dnsName");

		// IPv6 (optional)
		final var xPath = xml.xpathFactory.newXPath();
		try {
			final var ipv6 = (NodeList) xPath.evaluate("networkInterfaceSet/item/ipv6AddressesSet", networkNode,
					XPathConstants.NODESET);
			IntStream.range(0, ipv6.getLength()).mapToObj(ipv6::item)
					.forEach(i -> addNetworkDetails((Element) i, networks, "public", "item", "dnsName"));
		} catch (final XPathExpressionException e) {
			log.warn("Unable to evaluate IPv6", e);
		}
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

	@Override
	public void afterPropertiesSet() throws IOException {
		instanceTypes = csvForBean.toBean(InstanceType.class, "csv/instance-type-details.csv").stream()
				.collect(Collectors.toMap(InstanceType::getId, Function.identity()));

	}

	/**
	 * Check AWS connection and account.
	 *
	 * @param parameters The subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	@Override
	public boolean checkStatus(final Map<String, String> parameters) {
		return validateAccess(parameters);
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) throws Exception { // NOSONAR
		final var status = new SubscriptionStatusWithData();
		status.put("vm", getVmDetails(parameters));
		status.put("schedules", vmScheduleRepository.countBySubscription(subscription));
		return status;
	}

	@Override
	public void completeStatus(final VmSnapshotStatus task) {
		snapshotResource.completeStatus(task);
	}

	@Override
	public void delete(final VmSnapshotStatus transientTask) throws Exception {
		snapshotResource.delete(transientTask);
	}

	@Override
	public void execute(final VmExecution execution) throws Exception {
		final int subscription = execution.getSubscription().getId();
		final var parameters = pvResource.getSubscriptionParameters(subscription);
		// Propagate the instance identifiers
		execution.setVm(getVmDetails(parameters).getName() + "," + parameters.get(PARAMETER_INSTANCE_ID));

		// Execute the operation
		final var response = Optional.ofNullable(OPERATION_TO_ACTION.get(execution.getOperation())).map(
				a -> processEC2(subscription, p -> "Action=" + a + "&InstanceId.1=" + p.get(PARAMETER_INSTANCE_ID)))
				.orElse(null);
		if (!logTransitionState(response)) {
			// The result is not correct
			throw new BusinessException("vm-operation-execute");
		}
	}

	/**
	 * Find the virtual machines matching to the given criteria. Look into virtual machine name and identifier.
	 *
	 * @param node     the node to be tested with given parameters.
	 * @param criteria the search criteria. Case is insensitive.
	 * @param uriInfo  Additional subscription parameters.
	 * @return virtual machines.
	 * @throws Exception When AWS content cannot be read.
	 */
	@GET
	@Path("{node:service:.+}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<AwsVm> findAllByNameOrId(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria, @Context final UriInfo uriInfo) throws Exception {
		// Check the node exists
		if (nodeRepository.findOneVisible(node, securityHelper.getLogin()) == null) {
			return Collections.emptyList();
		}

		// Merge the node parameters to the node ones
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters(node));
		uriInfo.getQueryParameters().forEach((p, v) -> parameters.putIfAbsent(p, v.getFirst()));

		// Get all VMs and then filter by its name or id
		// Note : AWS does not support RegExp on tag
		return this.getDescribeInstances(parameters, "", this::toVm).stream()
				.filter(vm -> StringUtils.containsIgnoreCase(vm.getName(), criteria)
						|| StringUtils.containsIgnoreCase(vm.getId(), criteria))
				.sorted().toList();
	}

	@Override
	public List<Snapshot> findAllSnapshots(final int subscription, final String criteria) {
		return snapshotResource.findAllByNameOrId(subscription, StringUtils.trimToEmpty(criteria));
	}

	/**
	 * Get all instances visible for given AWS access key.
	 *
	 * @param parameters Subscription parameters.
	 * @param filter     Optional instance identifier to find. For sample :
	 *                   "&Filter.1.Name=instance-id&Filter.1.Value.1=my_instance_id"
	 * @param parser     The mapper from {@link Element} to {@link AwsVm}.
	 * @return The matching instances.
	 * @throws Exception When AWS content cannot be read.
	 */
	private List<AwsVm> getDescribeInstances(final Map<String, String> parameters, final String filter,
			final Function<Element, AwsVm> parser) throws Exception {
		var query = "Action=DescribeInstances";
		if (StringUtils.isNotEmpty(filter)) {
			query += filter;
		}
		final var response = StringUtils.defaultIfEmpty(processEC2(parameters, query),
				"<DescribeInstancesResponse><reservationSet><item><instancesSet></instancesSet></item></reservationSet></DescribeInstancesResponse>");
		return toVms(response, parser);
	}

	private int getEc2State(final Element record) {
		return getEc2State(record, "instanceState");
	}

	private int getEc2State(final Element record, final String tag) {
		final var stateElement = (Element) record.getElementsByTagName(tag).item(0);
		return Integer.parseInt(xml.getTagText(stateElement, "code"));
	}

	@Override
	public String getKey() {
		return VmAwsPluginResource.KEY;
	}

	/**
	 * Return the tag "name" value or <code>null</code>
	 *
	 * @param record The XML element.
	 * @return The "name" tag text value of <code>null</code> when not found.
	 */
	private String getName(final Element record) {
		return getResourceTag(record, "name");
	}

	/**
	 * Return the region from the subscription's parameters or the default one.
	 *
	 * @param parameters The subscription parameters.
	 * @return The right region to use. Never <code>null</code>.
	 */
	private String getRegion(final Map<String, String> parameters) {
		return Optional.ofNullable(parameters.get(PARAMETER_REGION))
				.orElseGet(() -> configuration.get(CONF_REGION, DEFAULT_REGION));
	}

	/**
	 * Return the resource tag value or <code>null</code>
	 */
	private String getResourceTag(final Element record, final String name) {
		return Optional.ofNullable(record.getElementsByTagName("tagSet").item(0))
				.map(n -> ((Element) n).getElementsByTagName("item"))
				.map(n -> IntStream.range(0, n.getLength()).mapToObj(n::item).map(t -> (Element) t)
						.filter(t -> xml.getTagText(t, "key").equalsIgnoreCase(name))
						.map(t -> xml.getTagText(t, "value")).findFirst().orElse(null))
				.orElse(null);
	}

	@Override
	public AwsVm getVmDetails(final Map<String, String> parameters) throws Exception {
		final var instanceId = parameters.get(PARAMETER_INSTANCE_ID);

		// Get the VM if exists
		return getDescribeInstances(parameters, "&Filter.1.Name=instance-id&Filter.1.Value.1=" + instanceId,
				this::toVmDetails).stream().findFirst().orElseThrow(
						() -> new ValidationJsonException(PARAMETER_INSTANCE_ID, "aws-instance-id", instanceId));
	}

	@Override
	public void link(final int subscription) throws Exception {
		getVmDetails(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Log the instance state transition and indicates the transition was a success.
	 *
	 * @param response the EC2 response markup.
	 * @return <code>true</code> when the transition succeed.
	 */
	private boolean logTransitionState(final String response)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final var items = xml.getXpath(ObjectUtils.defaultIfNull(response, "<a></a>"),
				"/*[contains(local-name(),'InstancesResponse')]/instancesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> (Element) n)
				.peek(e -> log.info("Instance {} goes from {} to {} state", xml.getTagText(e, "instanceId"),
						getEc2State(e, "previousState"), getEc2State(e, "currentState")))
				.findFirst().isPresent();

	}

	/**
	 * Create Curl request for AWS service. Initialize default values for awsAccessKey, awsSecretKey and regionName and
	 * compute signature.
	 *
	 * @param builder    {@link AWS4SignatureQueryBuilder} initialized with values used for this call (headers,
	 *                   parameters, host, ...)
	 * @param parameters The subscription's parameters.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder builder, final Map<String, String> parameters) {
		final var query = builder.accessKey(parameters.get(VmAwsPluginResource.PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(VmAwsPluginResource.PARAMETER_SECRET_ACCESS_KEY))
				.region(getRegion(parameters)).path("/").build();
		final var authorization = signer.computeSignature(query);
		final var request = new CurlRequest(query.getMethod(), toUrl(query), query.getBody());
		request.getHeaders().putAll(query.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	/**
	 * Execute an EC2 query using the given subscription parameters.
	 *
	 * @param subscription  The subscription holding the parameters.
	 * @param queryProvider The query string provider that would be placed into the AWS body.
	 *
	 * @return The response. <code>null</code> when failed.
	 */
	protected String processEC2(final int subscription, final Function<Map<String, String>, String> queryProvider) {
		final var parameters = pvResource.getSubscriptionParameters(subscription);
		return processEC2(parameters, queryProvider.apply(parameters));
	}

	/**
	 * Execute an EC2 query using the given subscription parameters.
	 *
	 * @param parameters The subscription's parameters.
	 * @param query      The query string that would be placed into the AWS body.
	 *
	 * @return The response. <code>null</code> when failed.
	 */
	protected String processEC2(final Map<String, String> parameters, final String query) {
		final var signatureQuery = AWS4SignatureQuery.builder().service("ec2")
				.body(query + "&Version=" + VmAwsPluginResource.API_VERSION);
		final var request = newRequest(signatureQuery, parameters);
		try (var curl = new CurlProcessor()) {
			curl.process(request);
		}
		return request.getResponse();
	}

	@Override
	public void snapshot(final VmSnapshotStatus transientTask) throws Exception {
		snapshotResource.create(transientTask);
	}

	/**
	 * Return the URL from a query.
	 *
	 * @param query Source {@link AWS4SignatureQuery}
	 * @return The base host URL from a query.
	 */
	protected String toUrl(final AWS4SignatureQuery query) {
		return "https://" + query.getHost() + query.getPath();
	}

	/**
	 * Build a described {@link AwsVm} bean from an XML VMRecord entry.
	 */
	private AwsVm toVm(final Element record) {
		final var result = new AwsVm();
		result.setId(xml.getTagText(record, "instanceId"));
		result.setName(Objects.toString(getName(record), result.getId()));
		result.setDescription(getResourceTag(record, "description"));
		final var state = getEc2State(record);
		result.setStatus(CODE_TO_STATUS.get(state));
		result.setBusy(Arrays.binarySearch(BUSY_CODES, state) >= 0);
		result.setVpc(xml.getTagText(record, "vpcId"));
		result.setAz(xml.getTagText((Element) record.getElementsByTagName("placement").item(0), "availabilityZone"));

		final var type = instanceTypes.get(xml.getTagText(record, "instanceType"));
		// Instance type details
		result.setRam(Optional.ofNullable(type).map(InstanceType::getRam).map(m -> (int) (m * 1024d)).orElse(0));
		result.setCpu(Optional.ofNullable(type).map(InstanceType::getCpu).orElse(0));
		result.setDeployed(result.getStatus() == VmStatus.POWERED_ON);
		return result;
	}

	/**
	 * Build a described {@link AwsVm} bean from an XML VMRecord entry.
	 */
	private AwsVm toVmDetails(final Element record) {
		final var result = toVm(record);

		// Network details
		result.setNetworks(new ArrayList<>());

		// Get network data for each network references
		addNetworkDetails(record, result.getNetworks());
		return result;
	}

	/**
	 * Build described beans from a XML result.
	 */
	private List<AwsVm> toVms(final String vmAsXml, final Function<Element, AwsVm> parser) throws Exception {
		final var items = xml.getXpath(vmAsXml, "/DescribeInstancesResponse/reservationSet/item/instancesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> parser.apply((Element) n))
				.toList();
	}

	/**
	 * Check AWS connection and account.
	 *
	 * @param parameters Subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	protected boolean validateAccess(final Map<String, String> parameters) {
		// Call STS service
		final var query = "Action=GetCallerIdentity&Version=2011-06-15";
		final var builder = AWS4SignatureQuery.builder().service("sts").body(query);
		try (var curl = new CurlProcessor()) {
			return curl.process(newRequest(builder, parameters));
		}
	}

}

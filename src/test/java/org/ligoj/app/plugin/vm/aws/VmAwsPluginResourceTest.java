/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.Transactional;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.MatcherUtil;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.ParameterValueRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.vm.execution.Vm;
import org.ligoj.app.plugin.vm.model.VmExecution;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.node.ParameterValueResource;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link VmAwsPluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class VmAwsPluginResourceTest extends AbstractServerTest {

	private static final String MOCK_URL = "http://localhost:" + MOCK_PORT + "/mock";

	private VmAwsPluginResource resource;

	@Autowired
	private ParameterValueResource pvResource;


	@Autowired
	private ParameterValueRepository pvRepository;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	protected int subscription;

	@Autowired
	private ConfigurationResource configuration;

	@BeforeEach
	public void prepareData() throws Exception {
		// Only with Spring context
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Parameter.class, Project.class, Subscription.class,
				ParameterValue.class, VmSchedule.class }, StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Invalidate cache
		cacheManager.getCache("curl-tokens").clear();
		cacheManager.getCache("node-parameters").clear();

		resource = new VmAwsPluginResource() {
			@Override
			public boolean validateAccess(final Map<String, String> parameters) {
				return true;
			}
		};
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		configuration.delete("service:vm:aws:region");
		resource.afterPropertiesSet();

		// Coverage only
		resource.getKey();
	}

	@Test
	public void delete() throws Exception {
		resource.delete(subscription, false);
	}

	@Test
	public void link() throws Exception {
		mockAwsVm().link(this.subscription);
	}

	@Test
	public void linkFailed() {
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			mockEc2("eu-west-1",
					"Action=DescribeInstances&Version=2016-11-15&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678",
					HttpStatus.SC_OK, IOUtils.toString(
							new ClassPathResource("mock-server/aws/describe-empty.xml").getInputStream(), "UTF-8"))
									.link(this.subscription);
		}), "service:vm:aws:id", "aws-instance-id");
	}

	@Test
	public void getVmDetailsNotFound() {
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		parameters.put(VmAwsPluginResource.PARAMETER_INSTANCE_ID, "0");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			mockEc2("eu-west-1", "&Filter.1.Name=instance-id&Filter.1.Value.1=0&Version=2016-11-15", HttpStatus.SC_OK,
					IOUtils.toString(new ClassPathResource("mock-server/aws/describe-empty.xml").getInputStream(),
							"UTF-8")).getVmDetails(parameters);
		}), VmAwsPluginResource.PARAMETER_INSTANCE_ID, "aws-instance-id");
	}

	@Test
	public void getVmDetails() throws Exception {
		checkVmDetails(mockAwsVm().getVmDetails(new HashMap<>(pvResource.getSubscriptionParameters(subscription))));
	}

	@Test
	public void addNetworkDetailsNullNode() throws IOException {
		mockAwsVm().addNetworkDetails(null, Collections.emptyList());
	}

	@Test
	public void getVmDetailsNoPublic() throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getSubscriptionParameters(subscription));
		final AwsVm vm = mockEc2("eu-west-1",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15",
				HttpStatus.SC_OK,
				IOUtils.toString(
						new ClassPathResource("mock-server/aws/describe-12345678-no-public.xml").getInputStream(),
						"UTF-8")).getVmDetails(parameters);
		checkVm(vm);

		// Check network
		Assertions.assertEquals(1, vm.getNetworks().size());
		Assertions.assertEquals("10.0.0.236", vm.getNetworks().get(0).getIp());
		Assertions.assertEquals("private", vm.getNetworks().get(0).getType());
		Assertions.assertEquals("ip-10-0-0-236.eu-west-1.compute.internal", vm.getNetworks().get(0).getDns());
	}

	@Test
	public void getVmDetailsTerminatedNoTag() throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getSubscriptionParameters(subscription));
		final AwsVm vm = mockEc2("eu-west-1",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15",
				HttpStatus.SC_OK,
				IOUtils.toString(
						new ClassPathResource("mock-server/aws/describe-12345679-terminated.xml").getInputStream(),
						"UTF-8")).getVmDetails(parameters);

		// Check terminated instance attribute
		Assertions.assertEquals("i-12345678", vm.getId());
		Assertions.assertEquals("i-12345678", vm.getName());
		Assertions.assertNull(vm.getDescription());
		Assertions.assertEquals(VmStatus.POWERED_OFF, vm.getStatus());
		Assertions.assertFalse(vm.isBusy());
		Assertions.assertNull(vm.getVpc());
		Assertions.assertFalse(vm.isDeployed());

		// From the instance type details
		Assertions.assertEquals(1024, vm.getRam());
		Assertions.assertEquals(1, vm.getCpu());

		// Check network
		Assertions.assertEquals(0, vm.getNetworks().size());
	}

	@Test
	public void checkSubscriptionStatus() throws Exception {
		final SubscriptionStatusWithData nodeStatusWithData = mockAwsVm().checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkVm((AwsVm) nodeStatusWithData.getData().get("vm"));
		Assertions.assertEquals(1, ((Integer) nodeStatusWithData.getData().get("schedules")).intValue());
	}

	@Test
	public void checkStatus() throws Exception {
		Assertions.assertTrue(mockAws("s3", "eu-west-1", null, HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-12345678.xml").getInputStream(),
						"UTF-8")).checkStatus("service:vm:aws:test",
								pvResource.getNodeParameters("service:vm:aws:test")));
	}

	@Test
	public void findAllByNameOrIdNoVisible() throws Exception {
		final List<AwsVm> projects = resource.findAllByNameOrId("service:vm:aws:any", "INSTANCE_ ", newUriInfo());
		Assertions.assertEquals(0, projects.size());
	}

	@Test
	public void findAllByNameOrId() throws Exception {
		final List<AwsVm> projects = mockEc2Ok("eu-west-1").findAllByNameOrId("service:vm:aws:test", "INSTANCE_",
				newUriInfo());
		Assertions.assertEquals(6, projects.size());
		checkVm(projects.get(0));
	}

	@Test
	public void findAllByNameOrIdNotFoundInRegion() throws Exception {
		Assertions.assertEquals(0,
				mockEc2Ok("eu-west-x").findAllByNameOrId("service:vm:aws:test", "INSTANCE_", newUriInfo()).size());

	}

	@Test
	public void findAllByNameOrIdNotFoundInRegion2() throws Exception {
		Assertions.assertTrue(pvResource.getNodeParameters("service:vm:aws:test").containsKey("service:vm:aws:region"));

		// Remove "service:vm:aws:region" parameter, this way, it can be set with a query parameter
		em.remove(pvRepository.findBy("parameter.id", "service:vm:aws:region"));
		cacheManager.getCache("node-parameters").evict("service:vm:aws:test");
		Assertions.assertFalse(pvResource.getNodeParameters("service:vm:aws:test").containsKey("service:vm:aws:region"));

		Assertions.assertEquals(0, mockEc2Ok("eu-west-1")
				.findAllByNameOrId("service:vm:aws:test", "INSTANCE_", newUriInfoRegion("eu-west-3")).size());
	}

	@Test
	public void findAllByNameOrIdOverrideParameter() throws Exception {
		// "service:vm:aws:region" parameter is already defined in the node, so will stay "eu-west-1"
		Assertions.assertEquals(0, mockEc2Ok("eu-west-3")
				.findAllByNameOrId("service:vm:aws:test", "INSTANCE_", newUriInfoRegion("eu-west-3")).size());
	}

	@Test
	public void findAllByNameOrIdAnotherRegion() throws Exception {
		Assertions.assertEquals(0, mockEc2Ok("eu-west-3")
				.findAllByNameOrId("service:vm:aws:test", "INSTANCE_", newUriInfoRegion("eu-west-3")).size());

	}

	@Test
	public void findAllByNameOrIdNoName() throws Exception {
		final List<AwsVm> projects = mockEc2Ok("eu-west-1").findAllByNameOrId("service:vm:aws:test", "i-00000006",
				newUriInfo());
		Assertions.assertEquals(1, projects.size());
		final Vm item = projects.get(0);
		Assertions.assertEquals("i-00000006", item.getId());
		Assertions.assertEquals("i-00000006", item.getName());
	}

	@Test
	public void findAllByNameOrIdById() throws Exception {
		final List<AwsVm> projects = mockEc2Ok("eu-west-1").findAllByNameOrId("service:vm:aws:test", "i-00000005",
				newUriInfo());
		Assertions.assertEquals(1, projects.size());
		final Vm item = projects.get(0);
		Assertions.assertEquals("i-00000005", item.getId());
		Assertions.assertEquals("INSTANCE_STOPPING", item.getName());
	}

	private VmAwsPluginResource mockEc2Ok(final String region) throws IOException {
		return mockEc2(region, "Action=DescribeInstances&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe.xml").getInputStream(), "UTF-8"));
	}

	private UriInfo newUriInfoRegion(final String region) {
		final UriInfo uriInfo = newUriInfo();
		uriInfo.getQueryParameters().putSingle("service:vm:aws:region", region);
		return uriInfo;
	}

	@Test
	public void findAllByNameOrIdEmpty() throws Exception {
		final List<AwsVm> projects = mockEc2("eu-west-1", "Action=DescribeInstances&Version=2016-11-15",
				HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-empty.xml").getInputStream(), "UTF-8"))
						.findAllByNameOrId("service:vm:aws:test", "INSTANCE_", newUriInfo());
		Assertions.assertEquals(0, projects.size());
	}

	@Test
	public void executeShutDown() throws Exception {
		execute(VmOperation.SHUTDOWN, "Action=StopInstances&InstanceId.1=i-12345678");
	}

	@Test
	public void executeError() throws IOException {
		final VmAwsPluginResource resource = mockEc2("eu-west-1",
				"Action=StopInstances&InstanceId.1=i-12345678&Version=2016-11-15", HttpStatus.SC_BAD_REQUEST,
				IOUtils.toString(new ClassPathResource("mock-server/aws/stopInstancesError.xml").getInputStream(),
						"UTF-8"));
		addVmDetailsMock(resource);
		Assertions.assertEquals("vm-operation-execute", Assertions.assertThrows(BusinessException.class, () -> {
			resource.execute(newExecution(VmOperation.SHUTDOWN));
		}).getMessage());
	}

	@Test
	public void executeOff() throws Exception {
		execute(VmOperation.OFF, "Action=StopInstances&Force=true&InstanceId.1=i-12345678");
	}

	@Test
	public void executeStart() throws Exception {
		execute(VmOperation.ON, "Action=StartInstances&InstanceId.1=i-12345678");
	}

	@Test
	public void executeReboot() throws Exception {
		execute(VmOperation.REBOOT, "Action=RebootInstances&InstanceId.1=i-12345678");
	}

	@Test
	public void executeReset() throws Exception {
		execute(VmOperation.RESET, "Action=RebootInstances&InstanceId.1=i-12345678");
	}

	@Test
	public void executeUnamanagedAction() throws IOException {
		final VmAwsPluginResource resource = mockAwsVm();

		// Details only is available
		addVmDetailsMock(resource);
		Assertions.assertEquals("vm-operation-execute", Assertions.assertThrows(BusinessException.class, () -> {
			resource.execute(newExecution(VmOperation.SUSPEND));
		}).getMessage());
	}

	@Test
	public void executeFailed() throws IOException {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		addQueryMock(resource, "ec2", "eu-west-1", "Action=StopInstances&InstanceId.1=i-12345678&Version=2016-11-15",
				HttpStatus.SC_INTERNAL_SERVER_ERROR, "");
		addVmDetailsMock(resource);
		httpServer.start();
		Assertions.assertEquals("vm-operation-execute", Assertions.assertThrows(BusinessException.class, () -> {
			resource.execute(newExecution(VmOperation.SHUTDOWN));
		}).getMessage());
	}

	/**
	 * prepare call to AWS using default region.
	 */
	@Test
	public void newRequest() {
		final CurlRequest request = resource.newRequest(
				AWS4SignatureQuery.builder().path("/").body("body").service("s3"),
				subscriptionResource.getParameters(subscription));
		Assertions.assertTrue(request.getHeaders().containsKey("Authorization"));
		Assertions.assertEquals("https://s3-eu-west-1.amazonaws.com/", request.getUrl());
		Assertions.assertEquals("POST", request.getMethod());
		Assertions.assertEquals("body", request.getContent());
	}

	/**
	 * prepare call to AWS with a custom region configured with configuration API.
	 */
	@Test
	public void newRequestCustomRegion() {
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		parameters.remove(VmAwsPluginResource.PARAMETER_REGION);
		configuration.put("service:vm:aws:region", "middle");
		final CurlRequest request = resource
				.newRequest(AWS4SignatureQuery.builder().path("/").body("body").service("s3"), parameters);
		Assertions.assertTrue(request.getHeaders().containsKey("Authorization"));
		Assertions.assertEquals("https://s3-middle.amazonaws.com/", request.getUrl());
		Assertions.assertEquals("POST", request.getMethod());
		Assertions.assertEquals("body", request.getContent());
	}

	/**
	 * prepare call to AWS with a custom region configured with subscription parameter.
	 */
	@Test
	public void newRequestCustomRegion2() {
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		parameters.put(VmAwsPluginResource.PARAMETER_REGION, "middle");
		final CurlRequest request = resource
				.newRequest(AWS4SignatureQuery.builder().path("/").body("body").service("s3"), parameters);
		Assertions.assertTrue(request.getHeaders().containsKey("Authorization"));
		Assertions.assertEquals("https://s3-middle.amazonaws.com/", request.getUrl());
		Assertions.assertEquals("POST", request.getMethod());
		Assertions.assertEquals("body", request.getContent());
	}

	private VmAwsPluginResource mockAwsVm() throws IOException {
		return mockEc2("eu-west-1",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15",
				HttpStatus.SC_OK, IOUtils.toString(
						new ClassPathResource("mock-server/aws/describe-12345678.xml").getInputStream(), "UTF-8"));
	}

	@Test
	public void checkSubscriptionStatusDown() {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		Mockito.doReturn(false).when(resource).validateAccess(ArgumentMatchers.anyMap());
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		parameters.put(VmAwsPluginResource.PARAMETER_INSTANCE_ID, "0");
		Mockito.doReturn(MOCK_URL + "/" + counterQuery + "/").when(resource)
				.toUrl(ArgumentMatchers.argThat(new ArgumentMatcher<AWS4SignatureQuery>() {

					@Override
					public boolean matches(final AWS4SignatureQuery query) {
						return query.getRegion().equals("eu-west-1") && query.getService().equals("s3");
					}
				}));
		httpServer.stubFor(post(urlEqualTo("/mock")).willReturn(aResponse().withStatus(404).withBody("")));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.checkSubscriptionStatus(subscription, null, parameters);
		}), "service:vm:aws:id", "aws-instance-id");
	}

	@Test
	public void validateAccessUp() throws Exception {
		Assertions.assertTrue(validateAccess(HttpStatus.SC_OK));
	}

	@Test
	public void validateAccessRegionConfiguration() throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		parameters.remove("service:vm:aws:region");
		resource.configuration.put("service:vm:aws:region", "middle");
		Mockito.doReturn(MOCK_URL + "/" + counterQuery + "/").when(resource)
				.toUrl(ArgumentMatchers.argThat(new ArgumentMatcher<AWS4SignatureQuery>() {

					@Override
					public boolean matches(final AWS4SignatureQuery query) {
						return query.getRegion().equals("middle") && query.getService().equals("s3");
					}

				}));

		httpServer.stubFor(post(urlEqualTo("/mock")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		resource.validateAccess(parameters);
	}

	@Test
	public void validateAccessDown() throws Exception {
		Assertions.assertFalse(validateAccess(HttpStatus.SC_FORBIDDEN));
	}

	private void execute(final VmOperation operation, final String body) throws Exception {
		final VmExecution execution = newExecution(operation);
		final VmAwsPluginResource resource = mockEc2("eu-west-1", body + "&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/stopInstances.xml").getInputStream(), "UTF-8"));
		addVmDetailsMock(resource);
		resource.execute(execution);
		Assertions.assertEquals("INSTANCE_ON,i-12345678", execution.getVm());
	}

	private void addVmDetailsMock(final VmAwsPluginResource resource) throws IOException {
		addQueryMock(resource, "ec2", "eu-west-1",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15",
				HttpStatus.SC_OK, IOUtils.toString(
						new ClassPathResource("mock-server/aws/describe-12345678.xml").getInputStream(), "UTF-8"));
	}

	private static int counterQuery = 0;

	private VmAwsPluginResource mockEc2(final String region, final String body, final int status,
			final String response) {
		return mockAws("ec2", region, body, status, response);
	}

	private VmAwsPluginResource mockAws(final String service, final String region, final String body, final int status,
			final String response) {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		addQueryMock(resource, service, region, body, status, response);
		httpServer.start();
		return resource;
	}

	private void addQueryMock(final VmAwsPluginResource resource, final String service, final String region,
			final String body, final int status, final String response) {
		counterQuery++;
		Mockito.doReturn(MOCK_URL + "/" + counterQuery + "/").when(resource)
				.toUrl(ArgumentMatchers.argThat(new ArgumentMatcher<AWS4SignatureQuery>() {

					@Override
					public boolean matches(final AWS4SignatureQuery query) {
						return query.getService().equals(service) && query.getRegion().equals(region)
								&& (body == query.getBody() || query.getBody().equals(body));
					}
				}));
		httpServer.stubFor(post(urlEqualTo("/mock/" + counterQuery + "/"))
				.willReturn(aResponse().withStatus(status).withBody(response)));
	}

	private void checkVm(final AwsVm item) {
		Assertions.assertEquals("i-12345678", item.getId());
		Assertions.assertEquals("INSTANCE_ON", item.getName());
		Assertions.assertEquals("Custom description", item.getDescription());
		Assertions.assertEquals(VmStatus.POWERED_ON, item.getStatus());
		Assertions.assertFalse(item.isBusy());
		Assertions.assertEquals("vpc-11112222", item.getVpc());
		Assertions.assertTrue(item.isDeployed());

		// From the instance type details
		Assertions.assertEquals(1024, item.getRam());
		Assertions.assertEquals(1, item.getCpu());
	}

	private void checkVmDetails(final AwsVm item) {
		checkVm(item);

		// Check network
		Assertions.assertEquals(3, item.getNetworks().size());
		Assertions.assertEquals("10.0.0.236", item.getNetworks().get(0).getIp());
		Assertions.assertEquals("private", item.getNetworks().get(0).getType());
		Assertions.assertEquals("ip-10-0-0-236.eu-west-1.compute.internal", item.getNetworks().get(0).getDns());
		Assertions.assertEquals("1.2.3.4", item.getNetworks().get(1).getIp());
		Assertions.assertEquals("public", item.getNetworks().get(1).getType());
		Assertions.assertEquals("ec2-1.2.3.4.eu-west-1.compute.amazonaws.com", item.getNetworks().get(1).getDns());

		// IPv6
		Assertions.assertEquals("public", item.getNetworks().get(2).getType());
		Assertions.assertEquals("0000:0000:0000:0000:0000:0000:0000:0000", item.getNetworks().get(2).getIp());
		Assertions.assertEquals("eu-west-1b", item.getAz());
	}

	private boolean validateAccess(int status) throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		parameters.put("service:vm:aws:region", "any");
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.anyMap());

		httpServer.stubFor(get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(status)));
		httpServer.start();
		return resource.validateAccess(parameters);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, VmAwsPluginResource.KEY);
	}

	@Override
	protected String getAuthenticationName() {
		return DEFAULT_USER;
	}

	@Test
	public void snapshot() throws Exception {
		final VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource.snapshotResource = Mockito.mock(VmAwsSnapshotResource.class);
		final VmSnapshotStatus transientTask = new VmSnapshotStatus();
		resource.snapshot(transientTask);
		Mockito.verify(resource.snapshotResource, Mockito.times(1)).create(transientTask);
	}

	@Test
	public void deleteSnapshot() throws Exception {
		final VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource.snapshotResource = Mockito.mock(VmAwsSnapshotResource.class);
		final VmSnapshotStatus transientTask = new VmSnapshotStatus();
		resource.delete(transientTask);
		Mockito.verify(resource.snapshotResource, Mockito.times(1)).delete(transientTask);
	}

	@Test
	public void findAllSnapshots() throws Exception {
		final VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource.snapshotResource = Mockito.mock(VmAwsSnapshotResource.class);
		resource.findAllSnapshots(subscription, "criteria");
		Mockito.verify(resource.snapshotResource, Mockito.times(1)).findAllByNameOrId(subscription, "criteria");
	}

	@Test
	public void completeStatus() throws Exception {
		final VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource.snapshotResource = Mockito.mock(VmAwsSnapshotResource.class);

		final VmSnapshotStatus task = new VmSnapshotStatus();
		resource.completeStatus(task);
		Mockito.verify(resource.snapshotResource, Mockito.times(1)).completeStatus(task);
	}

	private VmExecution newExecution(final VmOperation operation) {
		final VmExecution execution = new VmExecution();
		execution.setSubscription(subscriptionRepository.findOneExpected(subscription));
		execution.setOperation(operation);
		return execution;
	}
}

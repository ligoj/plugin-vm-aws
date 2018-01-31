package org.ligoj.app.plugin.vm.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.MatcherUtil;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.Vm;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.vm.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.node.ParameterValueResource;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.sf.ehcache.CacheManager;

/**
 * Test class of {@link VmAwsPluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class VmAwsPluginResourceTest extends AbstractServerTest {

	private static final String MOCK_URL = "http://localhost:" + MOCK_PORT + "/mock";

	@Autowired
	private VmAwsPluginResource resource;

	@Autowired
	private ParameterValueResource pvResource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	protected int subscription;

	@BeforeEach
	public void prepareData() throws Exception {
		// Only with Spring context
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class, VmSchedule.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Coverage only
		resource.getKey();

		// Invalidate vCloud cache
		CacheManager.getInstance().getCache("curl-tokens").removeAll();

		resource = new VmAwsPluginResource() {
			@Override
			public boolean validateAccess(final Map<String, String> parameters) throws Exception {
				return true;
			}
		};
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.afterPropertiesSet();
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
	public void linkFailed() throws Exception {
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			mockAws("ec2.eu-west-1.amazonaws.com",
					"Action=DescribeInstances&Version=2016-11-15&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678", HttpStatus.SC_OK,
					IOUtils.toString(new ClassPathResource("mock-server/aws/describe-empty.xml").getInputStream(), "UTF-8"))
							.link(this.subscription);
		}), "yyyyy", "unknown-id");
	}

	@Test
	public void getVmDetailsNotFound() throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		parameters.put(VmAwsPluginResource.PARAMETER_INSTANCE_ID, "0");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			mockAws("ec2.eu-west-1.amazonaws.com", "&Filter.1.Name=instance-id&Filter.1.Value.1=0&Version=2016-11-15", HttpStatus.SC_OK,
					IOUtils.toString(new ClassPathResource("mock-server/aws/describe-empty.xml").getInputStream(), "UTF-8"))
							.getVmDetails(parameters);
		}), VmAwsPluginResource.PARAMETER_INSTANCE_ID, "aws-instance-id");
	}

	@Test
	public void getVmDetails() throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getSubscriptionParameters(subscription));
		final AwsVm vm = mockAwsVm().getVmDetails(parameters);
		checkVmDetails(vm);
	}

	@Test
	public void getVmDetailsNoPublic() throws Exception {
		final Map<String, String> parameters = new HashMap<>(pvResource.getSubscriptionParameters(subscription));
		final AwsVm vm = mockAws("ec2.eu-west-1.amazonaws.com",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-12345678-no-public.xml").getInputStream(), "UTF-8"))
						.getVmDetails(parameters);
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
		final AwsVm vm = mockAws("ec2.eu-west-1.amazonaws.com",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-12345679-terminated.xml").getInputStream(), "UTF-8"))
						.getVmDetails(parameters);

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
		Assertions.assertTrue(mockAws("s3-eu-west-1.amazonaws.com", null, HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-12345678.xml").getInputStream(), "UTF-8"))
						.checkStatus("service:vm:aws:test", pvResource.getNodeParameters("service:vm:aws:test")));
	}

	@Test
	public void findAllByNameOrIdNoVisible() throws Exception {
		final List<AwsVm> projects = resource.findAllByNameOrId("service:vm:aws:any", "INSTANCE_ ");
		Assertions.assertEquals(0, projects.size());
	}

	@Test
	public void findAllByNameOrId() throws Exception {
		final List<AwsVm> projects = mockAws("ec2.eu-west-1.amazonaws.com", "Action=DescribeInstances&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe.xml").getInputStream(), "UTF-8"))
						.findAllByNameOrId("service:vm:aws:test", "INSTANCE_");
		Assertions.assertEquals(6, projects.size());
		checkVm(projects.get(0));
	}

	@Test
	public void findAllByNameOrIdNoName() throws Exception {
		final List<AwsVm> projects = mockAws("ec2.eu-west-1.amazonaws.com", "Action=DescribeInstances&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe.xml").getInputStream(), "UTF-8"))
						.findAllByNameOrId("service:vm:aws:test", "i-00000006");
		Assertions.assertEquals(1, projects.size());
		final Vm item = projects.get(0);
		Assertions.assertEquals("i-00000006", item.getId());
		Assertions.assertEquals("i-00000006", item.getName());
	}

	@Test
	public void findAllByNameOrIdById() throws Exception {
		final List<AwsVm> projects = mockAws("ec2.eu-west-1.amazonaws.com", "Action=DescribeInstances&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe.xml").getInputStream(), "UTF-8"))
						.findAllByNameOrId("service:vm:aws:test", "i-00000005");
		Assertions.assertEquals(1, projects.size());
		final Vm item = projects.get(0);
		Assertions.assertEquals("i-00000005", item.getId());
		Assertions.assertEquals("INSTANCE_STOPPING", item.getName());
	}

	@Test
	public void findAllByNameOrIdEmpty() throws Exception {
		final List<AwsVm> projects = mockAws("ec2.eu-west-1.amazonaws.com", "Action=DescribeInstances&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-empty.xml").getInputStream(), "UTF-8"))
						.findAllByNameOrId("service:vm:aws:test", "INSTANCE_");
		Assertions.assertEquals(0, projects.size());
	}

	@Test
	public void executeShutDown() throws Exception {
		execute(VmOperation.SHUTDOWN, "Action=StopInstances&InstanceId.1=i-12345678");
	}

	@Test
	public void executeError() throws Exception {
		Assertions.assertEquals("tttt", Assertions.assertThrows(BusinessException.class, () -> {
			mockAws("ec2.eu-west-1.amazonaws.com", "Action=StopInstances&InstanceId.1=i-12345678&Version=2016-11-15",
					HttpStatus.SC_BAD_REQUEST,
					IOUtils.toString(new ClassPathResource("mock-server/aws/stopInstancesError.xml").getInputStream(), "UTF-8"))
							.execute(subscription, VmOperation.SHUTDOWN);
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
	public void executeUnamanagedAction() throws Exception {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.any(Map.class));
		Assertions.assertEquals("tttt", Assertions.assertThrows(BusinessException.class, () -> {
			resource.execute(subscription, VmOperation.SUSPEND);
		}).getMessage());
	}

	@Test
	public void executeFailed() throws Exception {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.argThat(new ArgumentMatcher<AWS4SignatureQueryBuilder>() {

			@Override
			public boolean matches(final AWS4SignatureQueryBuilder argument) {
				final AWS4SignatureQuery query = argument.region("default").build();
				return query.getHost().equals("ec2.eu-west-1.amazonaws.com")
						&& query.getBody().equals("Action=StopInstances&InstanceId.1=i-12345678&Version=2016-11-15");
			}
		}), ArgumentMatchers.any(Map.class));
		httpServer.stubFor(get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR)));
		httpServer.start();
		Assertions.assertEquals("tttt", Assertions.assertThrows(BusinessException.class, () -> {
			resource.execute(subscription, VmOperation.SHUTDOWN);
		}).getMessage());
	}

	/**
	 * prepare call to AWS
	 * 
	 * @throws Exception
	 *             exception
	 */
	@Test
	public void newRequest() throws Exception {
		final CurlRequest request = resource.newRequest(AWS4SignatureQuery.builder().host("mock").path("/").body("body").service("s3"),
				subscription);
		Assertions.assertTrue(request.getHeaders().containsKey("Authorization"));
		Assertions.assertEquals("https://mock/", request.getUrl());
		Assertions.assertEquals("POST", request.getMethod());
		Assertions.assertEquals("body", request.getContent());
	}

	private VmAwsPluginResource mockAwsVm() throws IOException {
		return mockAws("ec2.eu-west-1.amazonaws.com",
				"Action=DescribeInstances&Filter.1.Name=instance-id&Filter.1.Value.1=i-12345678&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/describe-12345678.xml").getInputStream(), "UTF-8"));
	}

	@Test
	public void checkSubscriptionStatusDown() throws Exception {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		Mockito.doReturn(false).when(resource).validateAccess(ArgumentMatchers.anyMap());
		final Map<String, String> parameters = new HashMap<>(pvResource.getNodeParameters("service:vm:aws:test"));
		parameters.put(VmAwsPluginResource.PARAMETER_INSTANCE_ID, "0");
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.argThat(new ArgumentMatcher<AWS4SignatureQueryBuilder>() {

			@Override
			public boolean matches(final AWS4SignatureQueryBuilder argument) {
				final AWS4SignatureQuery query = argument.region("any").accessKey("default").secretKey("default").build();
				return query.getHost().equals("ec2.eu-west-1.amazonaws.com");
			}
		}), ArgumentMatchers.any(Map.class));
		httpServer.stubFor(get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(404).withBody("")));
		httpServer.start();

		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.checkSubscriptionStatus(subscription, null, parameters);
		}), "yyyyyy", "unknown-id");
	}

	@Test
	public void validateAccessUp() throws Exception {
		Assertions.assertTrue(validateAccess(HttpStatus.SC_OK));
	}

	@Test
	public void validateAccessDown() throws Exception {
		Assertions.assertFalse(validateAccess(HttpStatus.SC_FORBIDDEN));
	}

	private void execute(final VmOperation operation, final String body) throws Exception {
		mockAws("ec2.eu-west-1.amazonaws.com", body + "&Version=2016-11-15", HttpStatus.SC_OK,
				IOUtils.toString(new ClassPathResource("mock-server/aws/stopInstances.xml").getInputStream(), "UTF-8"))
						.execute(subscription, operation);
	}

	private VmAwsPluginResource mockAws(final String host, final String body, final int status, final String response) {
		final VmAwsPluginResource resource = Mockito.spy(this.resource);
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.argThat(new ArgumentMatcher<AWS4SignatureQueryBuilder>() {

			@Override
			public boolean matches(final AWS4SignatureQueryBuilder argument) {
				final AWS4SignatureQuery query = argument.region("any").accessKey("default").secretKey("default").build();
				return query.getHost().equals(host) && (body == query.getBody() || query.getBody().equals(body));
			}
		}), ArgumentMatchers.any(Map.class));
		httpServer.stubFor(get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(status).withBody(response)));
		httpServer.start();
		return resource;
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
		Assertions.assertEquals(2, item.getNetworks().size());
		Assertions.assertEquals("10.0.0.236", item.getNetworks().get(0).getIp());
		Assertions.assertEquals("private", item.getNetworks().get(0).getType());
		Assertions.assertEquals("ip-10-0-0-236.eu-west-1.compute.internal", item.getNetworks().get(0).getDns());
		Assertions.assertEquals("1.2.3.4", item.getNetworks().get(1).getIp());
		Assertions.assertEquals("public", item.getNetworks().get(1).getType());
		Assertions.assertEquals("ec2-1.2.3.4.eu-west-1.compute.amazonaws.com", item.getNetworks().get(1).getDns());
		Assertions.assertEquals("eu-west-1b", item.getAz());
	}

	private boolean validateAccess(int status) throws Exception {
		VmAwsPluginResource resource = new VmAwsPluginResource();
		SpringUtils.getApplicationContext().getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn("any").when(resource).getRegion();
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.anyMap());

		httpServer.stubFor(get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(status)));
		httpServer.start();
		return resource.validateAccess(pvResource.getNodeParameters("service:vm:aws:test"));
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, VmAwsPluginResource.KEY);
	}

	@Override
	protected String getAuthenticationName() {
		return DEFAULT_USER;
	}
}

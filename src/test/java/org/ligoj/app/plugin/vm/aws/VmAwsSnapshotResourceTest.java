/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.transaction.Transactional;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.iam.IUserRepository;
import org.ligoj.app.iam.IamConfiguration;
import org.ligoj.app.iam.IamProvider;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.model.SnapshotOperation;
import org.ligoj.app.plugin.vm.model.VmSchedule;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.plugin.vm.snapshot.Snapshot;
import org.ligoj.app.plugin.vm.snapshot.VmSnapshotResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xml.sax.SAXException;

/**
 * Test class of {@link VmAwsSnapshotResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class VmAwsSnapshotResourceTest extends AbstractServerTest {

	private VmAwsSnapshotResource resource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	protected int subscription;

	@BeforeEach
	public void prepareData() throws Exception {
		// Only with Spring context
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Parameter.class, Project.class, Subscription.class,
				ParameterValue.class, VmSchedule.class }, StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Invalidate vCloud cache
		cacheManager.getCache("curl-tokens").clear();

		resource = new VmAwsSnapshotResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.snapshotResource = Mockito.mock(VmSnapshotResource.class);
		resource.resource = Mockito.mock(VmAwsPluginResource.class);
	}

	@Test
	public void findAllByNameOrId() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(2, snapshosts.size());
		final Snapshot snapshot = checkAmiPart(snapshosts.get(0));
		Assertions.assertNotNull(snapshot.getDate());
		Assertions.assertNotNull(snapshot.getAuthor().getFirstName());
	}

	/**
	 * Last snapshot task is locally and remotely finished without error.
	 */
	@Test
	public void findAllByNameOrIdTaskFinished() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setFinishedRemote(true);
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin");
		status.setSnapshotInternalId("ami-00000001");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(2, snapshosts.size());
		checkAmiPart(snapshosts.get(0));
	}

	/**
	 * Last snapshot task is locally and remotely finished without error.
	 */
	@Test
	public void findAllByNameOrIdTaskFailed() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setFinishedRemote(true);
		status.setEnd(new Date());
		status.setFailed(true);
		status.setAuthor("ligoj-admin");
		status.setSnapshotInternalId("ami-00000004");
		status.setStatusText("some-error");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(3, snapshosts.size());
		checkAmiPart(snapshosts.get(1));

		// Failed task, unlisted AMI, failed and not created AMI
		Assertions.assertEquals("ligoj-admin", snapshosts.get(0).getAuthor().getId());
		Assertions.assertEquals("ami-00000004", snapshosts.get(0).getId());
		Assertions.assertFalse(snapshosts.get(0).isPending());
		Assertions.assertFalse(snapshosts.get(0).isAvailable());
		Assertions.assertEquals("some-error", snapshosts.get(0).getStatusText());
	}

	/**
	 * Last snapshot task is locally finished but the AMI is not yet listed and not found by direct lookup.
	 */
	@Test
	public void findAllByNameOrIdTaskFinishedLocallyNoAws() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(3, snapshosts.size());
		checkAmiPart(snapshosts.get(1));

		// Unfinished task, unlisted AMI and not created AMI
		Assertions.assertEquals("ligoj-admin2", snapshosts.get(0).getAuthor().getId());
		Assertions.assertEquals("ami-00000004", snapshosts.get(0).getId());
		Assertions.assertTrue(snapshosts.get(0).isPending());
		Assertions.assertFalse(snapshosts.get(0).isAvailable());
		Assertions.assertEquals("not-found", snapshosts.get(0).getStatusText());
	}

	/**
	 * Last snapshot task is locally finished but the AMI was not yet listed, is found by direct lookup and now listed.
	 */
	@Test
	public void findAllByNameOrIdTaskFinishedJustListed() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all-with-00000004.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(3, snapshosts.size());
		checkAmiPart(snapshosts.get(1));

		// Finished task, unlisted AMI and not created AMI (user from AMI)
		Assertions.assertEquals("ligoj-admin", snapshosts.get(0).getAuthor().getId());
		Assertions.assertEquals("ami-00000004", snapshosts.get(0).getId());
		Assertions.assertFalse(snapshosts.get(0).isPending());
		Assertions.assertTrue(snapshosts.get(0).isAvailable());
		Assertions.assertEquals("available", snapshosts.get(0).getStatusText());
	}

	/**
	 * Last snapshot task is locally not finished and AMI identifier is not yet known.
	 */
	@Test
	public void findAllByNameOrIdTaskNoAmi() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setAuthor("ligoj-admin2");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(3, snapshosts.size());
		checkAmiPart(snapshosts.get(1));

		// Unfinished task, unlisted AMI and not created AMI
		Assertions.assertEquals("ligoj-admin2", snapshosts.get(0).getAuthor().getId());
		Assertions.assertNull(snapshosts.get(0).getId());
		Assertions.assertTrue(snapshosts.get(0).isPending());
		Assertions.assertFalse(snapshosts.get(0).isAvailable());
		Assertions.assertEquals("not-created", snapshosts.get(0).getStatusText());
	}

	/**
	 * Last snapshot task is locally finished but the AMI is not yet listed.
	 */
	@Test
	public void findAllByNameOrIdTaskFinishedNotListed() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(3, snapshosts.size());
		checkAmiPart(snapshosts.get(1));

		// Unfinished task, unlisted AMI and not created AMI
		final Snapshot snapshot = snapshosts.get(0);
		Assertions.assertEquals("ligoj-admin2", snapshot.getAuthor().getId());
		Assertions.assertEquals("ami-00000004", snapshot.getId());
		Assertions.assertTrue(snapshot.isPending());
		Assertions.assertFalse(snapshot.isAvailable());
		Assertions.assertEquals("not-finished-remote", snapshot.getStatusText());
	}

	/**
	 * Unfinished locally : still not finished
	 */
	@Test
	public void completeStatusNoAmiId() throws IOException {
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-empty.xml");
		VmSnapshotStatus status = new VmSnapshotStatus();
		Assertions.assertFalse(status.isFinished());
		resource.completeStatus(status);
		Assertions.assertFalse(status.isFinished());
	}

	/**
	 * Finished locally, lookup by id failed : finished remotely with failure
	 */
	@Test
	public void completeStatusNoAmi() throws IOException {
		// Lookup by id failed
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-empty.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		resource.completeStatus(status);

		Assertions.assertTrue(status.isFinished());
		Assertions.assertTrue(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("not-found", status.getStatusText());
	}

	/**
	 * Finished locally, lookup by id succeed : not finished remotely
	 */
	@Test
	public void completeStatusUnlisted() throws IOException {
		// Lookup by id succeed
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004.xml");

		// Not yet listed
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		resource.completeStatus(status);

		Assertions.assertTrue(status.isFinished());
		Assertions.assertFalse(status.isFailed());
		Assertions.assertFalse(status.isFinishedRemote());
		Assertions.assertEquals("not-finished-remote", status.getStatusText());
	}

	/**
	 * Finished locally, but not for a CREATE snapshot.
	 */
	@Test
	void completeStatusNotCreate() {
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		status.setOperation(SnapshotOperation.DELETE);
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		// This would do nothing for this operation
		resource.completeStatus(status);

		// Status is not changed
		Assertions.assertFalse(status.isFinishedRemote());

	}

	/**
	 * Finished locally, lookup by id succeed and listed : finished remotely
	 */
	@Test
	public void completeStatus() throws IOException {
		// Lookup by id succeed
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004.xml");

		// Not yet listed with correct tags
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all-with-00000004.xml");
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setEnd(new Date());
		status.setAuthor("ligoj-admin2");
		status.setSnapshotInternalId("ami-00000004");
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);

		resource.completeStatus(status);

		Assertions.assertTrue(status.isFinished());
		Assertions.assertFalse(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
	}

	@Test
	public void findAllByNameOrIdInvalidContent() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all-invalid.html");
		Assertions.assertEquals("DescribeImages-failed",
				Assertions.assertThrows(BusinessException.class, () -> resource.findAllByNameOrId(subscription, ""))
						.getMessage());
	}

	@Test
	public void findAllByNameOrIdFilterSnapShot() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "snap-0a9e26b713aeec40e");
		Assertions.assertEquals(1, snapshosts.size());
		checkAmiPart(snapshosts.get(0));
	}

	@Test
	public void findAllByNameOrIdFilterId() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "ami-00000001");
		Assertions.assertEquals(1, snapshosts.size());
		checkAmiPart(snapshosts.get(0));
	}

	@Test
	public void findAllByNameOrIdFilterName() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "sample-ligo2");
		Assertions.assertEquals(1, snapshosts.size());
		checkAmiPart(snapshosts.get(0));
	}

	@Test
	public void findAllByNameOrIdInvalidDate() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all-invalid-date.xml");

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "n");
		Assertions.assertEquals(2, snapshosts.size());
		Assertions.assertEquals(0, checkAmiPart(snapshosts.get(1)).getDate().getTime());
	}

	@Test
	public void findAllByNameOrIdNotFoundAuthor() throws IOException {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all.xml");

		// Mock IAM
		resource.iamProvider = new IamProvider[] { Mockito.mock(IamProvider.class) };
		final IamConfiguration iamConfiguration = Mockito.mock(IamConfiguration.class);
		Mockito.doReturn(iamConfiguration).when(resource.iamProvider[0]).getConfiguration();
		final IUserRepository userRepository = Mockito.mock(IUserRepository.class);
		Mockito.doReturn(userRepository).when(iamConfiguration).getUserRepository();

		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "n");
		Assertions.assertEquals(2, snapshosts.size());
		Assertions.assertNull(checkAmiPart(snapshosts.get(0)).getAuthor().getCompany());
	}

	@Test
	public void findAllByNameOrIdInvalidVolume() throws Exception {
		mockAws("Action=DescribeImages&Owner.1=self&Filter.1.Name=tag:ligoj:subscription&Filter.1.Value="
				+ subscription, "mock-server/aws/describe-images-all-invalid-volume.xml");

		// Only one volume snapshot match inside the AMI
		final List<Snapshot> snapshosts = resource.findAllByNameOrId(subscription, "snap-");
		Assertions.assertEquals(1, snapshosts.size());
		checkAmiPart(snapshosts.get(0));

		// Without filter, the other AMI matches, and does not contains any volume snapshot (invalid)
		final List<Snapshot> snapshostsNoFilter = resource.findAllByNameOrId(subscription, "");
		Assertions.assertEquals(2, snapshostsNoFilter.size());
		Assertions.assertEquals(0, snapshostsNoFilter.get(1).getVolumes().size());
	}

	@Test
	public void create() throws Exception {
		final VmSnapshotStatus status = mockStatus();
		mockAws("Action=CreateImage&NoReboot=false&InstanceId=i-12345678&Name=ligoj-snapshot/" + subscription + "/"
				+ new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(status.getStart())
				+ "&Description=Snapshot+created+from+Ligoj", "mock-server/aws/create-images.xml");
		mockAws("Action=CreateTags&ResourceId.1=ami-00000004&Tag.1.Key=ligoj:subscription&Tag.1.Value=" + subscription
				+ "&Tag.2.Key=ligoj:audit&Tag.2.Value=ligoj-admin", "mock-server/aws/create-tags.xml");

		// Main call
		resource.create(status);
		checkCreate(status);
	}

	@Test
	public void createNoReboot() throws Exception {
		final VmSnapshotStatus status = mockStatus();
		status.setStop(false);
		mockAws("Action=CreateImage&NoReboot=true&InstanceId=i-12345678&Name=ligoj-snapshot/" + subscription + "/"
				+ new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(status.getStart())
				+ "&Description=Snapshot+created+from+Ligoj", "mock-server/aws/create-images.xml");
		mockAws("Action=CreateTags&ResourceId.1=ami-00000004&Tag.1.Key=ligoj:subscription&Tag.1.Value=" + subscription
				+ "&Tag.2.Key=ligoj:audit&Tag.2.Value=ligoj-admin", "mock-server/aws/create-tags.xml");

		// Main call
		resource.create(status);
		checkCreate(status);
	}

	private void checkCreate(final VmSnapshotStatus status) {
		Assertions.assertTrue(status.isFinished());
		Assertions.assertFalse(status.isFailed());
		Assertions.assertEquals("checking-availability", status.getPhase());
		Assertions.assertEquals(2, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertEquals("ami-00000004", status.getSnapshotInternalId());
	}

	@Test
	public void createAmiFail() throws Exception {
		final VmSnapshotStatus status = mockStatus();

		// Main call
		resource.create(status);

		Assertions.assertTrue(status.isFinished());
		Assertions.assertTrue(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("creating-ami", status.getPhase());
		Assertions.assertEquals(VmAwsPluginResource.KEY + ":ami-create-failed", status.getStatusText());
		Assertions.assertEquals(0, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertNull(status.getSnapshotInternalId());
	}

	@Test
	public void deleteSearchingNotFound() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockDeleteStatus();

		// Main call
		resource.delete(status);

		Assertions.assertTrue(status.isFinished());
		Assertions.assertTrue(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("searching-ami", status.getPhase());
		Assertions.assertEquals(VmAwsPluginResource.KEY + ":ami-not-found", status.getStatusText());
		Assertions.assertEquals(0, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertEquals("ami-00000004", status.getSnapshotInternalId());
	}

	@Test
	public void deleteDeregisteringFailed() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockDeleteStatus();
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004.xml");
		checkDeregisteringFail(status);
	}

	@Test
	public void deleteDeregisteringReturnFalse() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockDeleteStatus();
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004.xml");
		mockAws("Action=DeregisterImage&ImageId=ami-00000004", "mock-server/aws/deregister-image-return-false.xml");
		checkDeregisteringFail(status);
	}

	private VmSnapshotStatus mockDeleteStatus() {
		final VmSnapshotStatus status = mockStatus();
		status.setOperation(SnapshotOperation.DELETE);
		status.setSnapshotInternalId("ami-00000004");
		return status;
	}

	private void checkDeregisteringFail(final VmSnapshotStatus status)
			throws SAXException, IOException, ParserConfigurationException {
		resource.delete(status);
		Assertions.assertTrue(status.isFinished());
		Assertions.assertTrue(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("deregistering-ami", status.getPhase());
		Assertions.assertEquals(VmAwsPluginResource.KEY + ":ami-unregistering-failed", status.getStatusText());
		Assertions.assertEquals(1, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertEquals("ami-00000004", status.getSnapshotInternalId());
	}

	@Test
	public void deleteSnapshotsFail() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockDeleteStatus();
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004-multiple-volumes.xml");
		mockAws("Action=DeregisterImage&ImageId=ami-00000004", "mock-server/aws/deregister-image.xml");
		checkDeleteSnapshotsFail(status);
	}

	@Test
	public void deleteSnapshotsReturnFalse() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockDeleteStatus();
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004-multiple-volumes.xml");
		mockAws("Action=DeregisterImage&ImageId=ami-00000004", "mock-server/aws/deregister-image.xml");
		mockAws("Action=DeleteSnapshot&SnapshotId.1=snap-0a9e26b713aeec40e&SnapshotId.2=snap-0a9e26b713aeec40f",
				"mock-server/aws/delete-snapshot-return-false.xml");
		checkDeleteSnapshotsFail(status);
	}

	private void checkDeleteSnapshotsFail(final VmSnapshotStatus status)
			throws SAXException, IOException, ParserConfigurationException {
		resource.delete(status);
		Assertions.assertTrue(status.isFinished());
		Assertions.assertTrue(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("deleting-snapshots", status.getPhase());
		Assertions.assertEquals(VmAwsPluginResource.KEY + ":ami-deleting-snapshots-failed", status.getStatusText());
		Assertions.assertEquals(2, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertEquals("ami-00000004", status.getSnapshotInternalId());
	}

	@Test
	public void delete() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockDeleteStatus();
		mockAws("Action=DescribeImages&Owner.1=self&ImageId.1=ami-00000004",
				"mock-server/aws/describe-images-00000004-multiple-volumes.xml");
		mockAws("Action=DeregisterImage&ImageId=ami-00000004", "mock-server/aws/deregister-image.xml");
		mockAws("Action=DeleteSnapshot&SnapshotId.1=snap-0a9e26b713aeec40e&SnapshotId.2=snap-0a9e26b713aeec40f",
				"mock-server/aws/delete-snapshot.xml");
		resource.delete(status);
		Assertions.assertTrue(status.isFinished());
		Assertions.assertFalse(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("deleting-snapshots", status.getPhase());
		Assertions.assertNull(status.getStatusText());
		Assertions.assertEquals(3, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertEquals("ami-00000004", status.getSnapshotInternalId());
	}

	@Test
	public void createTagsFail() throws SAXException, IOException, ParserConfigurationException {
		final VmSnapshotStatus status = mockStatus();
		mockAws("Action=CreateImage&NoReboot=false&InstanceId=i-12345678&Name=ligoj-snapshot/" + subscription + "/"
				+ new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(status.getStart())
				+ "&Description=Snapshot+created+from+Ligoj", "mock-server/aws/create-images.xml");
		checkCreateTagsFail(status);
	}

	@Test
	public void createTagsFailReturnFalse() throws Exception {
		final VmSnapshotStatus status = mockStatus();
		mockAws("Action=CreateImage&NoReboot=false&InstanceId=i-12345678&Name=ligoj-snapshot/" + subscription + "/"
				+ new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(status.getStart())
				+ "&Description=Snapshot+created+from+Ligoj", "mock-server/aws/create-images.xml");
		mockAws("Action=CreateTags&ResourceId.1=ami-00000004&Tag.1.Key=ligoj:subscription&Tag.1.Value=" + subscription
				+ "&Tag.2.Key=ligoj:audit&Tag.2.Value=ligoj-admin", "mock-server/aws/create-tags-return-false.xml");
		checkCreateTagsFail(status);
	}

	private void checkCreateTagsFail(final VmSnapshotStatus status)
			throws SAXException, IOException, ParserConfigurationException {
		// Main call
		resource.create(status);

		Assertions.assertTrue(status.isFinished());
		Assertions.assertTrue(status.isFailed());
		Assertions.assertTrue(status.isFinishedRemote());
		Assertions.assertEquals("tagging-ami", status.getPhase());
		Assertions.assertEquals(VmAwsPluginResource.KEY + ":ami-tag-failed", status.getStatusText());
		Assertions.assertEquals(1, status.getDone());
		Assertions.assertEquals(3, status.getWorkload());
		Assertions.assertEquals("ami-00000004", status.getSnapshotInternalId());
	}

	private VmSnapshotStatus mockStatus() {
		final VmSnapshotStatus status = new VmSnapshotStatus();
		status.setAuthor("ligoj-admin");
		status.setStop(true);
		status.setStart(DateUtils.newCalendar().getTime());
		status.setLocked(subscriptionRepository.findOneExpected(subscription));
		status.setOperation(SnapshotOperation.CREATE);

		Mockito.when(resource.snapshotResource.getTask(subscription)).thenReturn(status);
		Mockito.when(resource.snapshotResource.nextStep(ArgumentMatchers.eq(subscription),
				ArgumentMatchers.argThat(new ArgumentMatcher<Consumer<VmSnapshotStatus>>() {

					@Override
					public boolean matches(final Consumer<VmSnapshotStatus> f) {
						f.accept(status);
						return true;
					}
				}))).thenReturn(null);
		Mockito.when(resource.snapshotResource.endTask(ArgumentMatchers.eq(subscription), ArgumentMatchers.eq(true),
				ArgumentMatchers.argThat(new ArgumentMatcher<Consumer<VmSnapshotStatus>>() {

					@Override
					public boolean matches(final Consumer<VmSnapshotStatus> f) {
						f.accept(status);
						status.setFailed(true);
						status.setEnd(new Date());
						return true;
					}
				}))).thenReturn(null);
		Mockito.when(resource.snapshotResource.endTask(ArgumentMatchers.eq(subscription), ArgumentMatchers.eq(false),
				ArgumentMatchers.argThat(new ArgumentMatcher<Consumer<VmSnapshotStatus>>() {

					@Override
					public boolean matches(final Consumer<VmSnapshotStatus> f) {
						f.accept(status);
						status.setEnd(new Date());
						return true;
					}
				}))).thenReturn(null);
		return status;
	}

	private Snapshot checkAmiPartNoVolume(final Snapshot snapshot) {
		Assertions.assertEquals("Information", snapshot.getDescription());
		Assertions.assertTrue(snapshot.isAvailable());
		Assertions.assertFalse(snapshot.isPending());
		Assertions.assertEquals("ami-00000001", snapshot.getId());
		Assertions.assertEquals("sample-ligo2", snapshot.getName());
		Assertions.assertEquals("available", snapshot.getStatusText());
		Assertions.assertEquals("ligoj-admin", snapshot.getAuthor().getId());
		Assertions.assertNull(snapshot.getStopRequested());
		return snapshot;
	}

	private Snapshot checkAmiPart(final Snapshot snapshot) {
		Assertions.assertEquals(1, snapshot.getVolumes().size());
		Assertions.assertEquals(8, snapshot.getVolumes().get(0).getSize());
		Assertions.assertEquals("snap-0a9e26b713aeec40e", snapshot.getVolumes().get(0).getId());
		Assertions.assertEquals("/dev/sda1", snapshot.getVolumes().get(0).getName());
		return checkAmiPartNoVolume(snapshot);
	}

	private void mockAws(final String url, final String response) throws IOException {
		final Map<String, String> parameters = subscriptionResource.getParametersNoCheck(subscription);
		Mockito.when(resource.resource.processEC2(ArgumentMatchers.eq(subscription),
				ArgumentMatchers.argThat(new ArgumentMatcher<Function<Map<String, String>, String>>() {

					@Override
					public boolean matches(final Function<Map<String, String>, String> f) {
						return f.apply(parameters).equals(url);
					}
				}))).thenReturn(IOUtils.toString(new ClassPathResource(response).getInputStream(), "UTF-8"));
		Mockito.when(resource.resource.processEC2(ArgumentMatchers.eq(parameters), ArgumentMatchers.eq(url)))
				.thenReturn(IOUtils.toString(new ClassPathResource(response).getInputStream(), "UTF-8"));
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
}

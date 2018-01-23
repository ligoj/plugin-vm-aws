package org.ligoj.app.plugin.vm.aws;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.plugin.vm.snapshot.Snapshot;
import org.ligoj.app.plugin.vm.snapshot.SnapshotResource;
import org.ligoj.app.plugin.vm.snapshot.VolumeSnapshot;
import org.ligoj.app.resource.plugin.XmlUtils;
import org.ligoj.bootstrap.core.DateUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import lombok.extern.slf4j.Slf4j;

/**
 * AWS VM snapshot resource.
 * 
 * @see <a href=
 *      "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateSnapshot.html">API_CreateSnapshot</a>
 * @see <a href=
 *      "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeSnapshots.html">API_DescribeSnapshots</a>
 * @see <a href=
 *      "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DeleteSnapshot.html">API_DeleteSnapshot</a>
 * @see <a href=
 *      "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateImage.html">API_CreateImage</a>
 * @see <a href=
 *      "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DeregisterImage.html">API_DeregisterImage</a>
 * @see <a href=
 *      "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeImages.html">API_DescribeImages</a>
 */
@Service
@Slf4j
public class VmAwsSnapshotResource {

	/**
	 * The shared XPATH factory. TODO Remove with API 2.0.3+
	 */
	public final XPathFactory xpathFactory = XPathFactory.newInstance();

	/**
	 * AWS tag prefix.
	 */
	public static final String TAG_PREFIX = "ligoj:";

	/**
	 * AWS tag suffix for backup.
	 */
	public static final String TAG_SNAPSHOT = TAG_PREFIX + "snapshot";
	/**
	 * AWS tag for backup.
	 */
	public static final String TAG_SUBSCRIPTION = TAG_PREFIX + "subscription";
	/**
	 * AWS tag for audit.
	 */
	public static final String TAG_AUDIT = TAG_PREFIX + "audit";

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	private VmAwsPluginResource resource;

	@Autowired
	private SnapshotResource snapshotResource;

	@Autowired
	protected XmlUtils xml;

	/**
	 * Return all AMIs matching to the given criteria and also associated to the
	 * given subscription. Note that "DescribeImages" does not work exactly the same
	 * way when <code>ImageId.N</code> filter is enabled. Without this filter, there
	 * is delay between CreateImage and its visibility.
	 *
	 * @param subscription
	 *            The related subscription identifier.
	 * @param criteria
	 *            The search criteria. Case is insensitive. The criteria try to
	 *            match the AMI's identifier, the AMI's name or one of its volume
	 *            snapshots identifier machine name and identifier.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	public List<Snapshot> findAllByNameOrId(final int subscription, final String criteria) {
		return findAllByNameOrId(subscription, criteria, snapshotResource.getTask(subscription));
	}

	/**
	 * Return all AMIs matching to the given criteria and also associated to the
	 * given subscription. Note that "DescribeImages" does not work exactly the same
	 * way when <code>ImageId.N</code> filter is enabled. Without this filter, there
	 * is delay between CreateImage and its visibility.
	 *
	 * @param subscription
	 *            The related subscription identifier.
	 * @param criteria
	 *            The search criteria. Case is insensitive. The criteria try to
	 *            match the AMI's identifier, the AMI's name or one of its volume
	 *            snapshots identifier machine name and identifier.
	 * @param task
	 *            The current task used to prepend the globally visible AMIs.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	private List<Snapshot> findAllByNameOrId(final int subscription, final String criteria, final VmSnapshotStatus task) {
		final List<Snapshot> snapshots = findAllBySubscription(subscription).stream().filter(s -> matches(s, criteria))
				.sorted((a, b) -> b.getDate().compareTo(a.getDate())).collect(Collectors.toList());
		Optional.ofNullable(getNotValidatedAmi(snapshots, task)).filter(s -> matches(s, criteria)).ifPresent(s -> {
			snapshots.add(0, s);

			// Override the status since this AMI is not really GA
			s.setPending(true);
			s.setAvailable(false);
			s.setStopRequested(task.isStop());
		});
		return snapshots;
	}

	/**
	 * Complete the task status from remote AWS information. Is considered as not
	 * completely finished when AMI tasks are finished without error at client side,
	 * and that AMI can be found by its identifier and yet not listed with tag
	 * filters.
	 * 
	 * @param task
	 *            The task to complete.
	 */
	public void completeStatus(final VmSnapshotStatus task) {
		if (isMayNotFinishedRemote(task) && getNotValidatedAmi(task) == null) {
			// Availability is now checked, update the status and the progress
			updateAsFinishedRemote(task);
		}
	}

	/**
	 * Update the given task as finished remotely
	 */
	private void updateAsFinishedRemote(final VmSnapshotStatus task) {
		task.setFinishedRemote(true);
		task.setDone(3);
		task.setPhase("checking-availability");
	}

	/**
	 * Check the task may not be finished remotely : finished locally, not failed
	 * and not yet actually checked remotely.
	 */
	private boolean isMayNotFinishedRemote(final VmSnapshotStatus task) {
		return task.isFinished() && !task.isFinishedRemote() && !task.isFailed();
	}

	/**
	 * Return the AMI corresponding to the given task and that is not yet globally
	 * visible.
	 * 
	 * @param task
	 *            The task to check.
	 * @return The not yet globally visible AMI or <code>null</code>.
	 */
	private Snapshot getNotValidatedAmi(final VmSnapshotStatus task) {
		return Optional.ofNullable(findById(task.getLocked().getId(), task.getSnapshotInternalId()))
				.filter(s -> findAllBySubscription(task.getLocked().getId()).stream().noneMatch(o -> o.getId().equals(s.getId())))
				.orElse(null);
	}

	/**
	 * Return the AMI corresponding to the given task and that is not in the given
	 * snapshot list.
	 * 
	 * @param snapshots
	 *            The snapshots list to check.
	 * @param task
	 *            The task to check.
	 * @return The not yet globally visible AMI or <code>null</code>.
	 */
	private Snapshot getNotValidatedAmi(final List<Snapshot> snapshots, final VmSnapshotStatus task) {
		if (isMayNotFinishedRemote(task)) {
			if (snapshots.stream().filter(s -> s.getId().equals(task.getSnapshotInternalId())).findAny().isPresent()) {
				// AMI has been completed after the shutdown of the client
				updateAsFinishedRemote(task);
			} else {
				// Snapshot created by the task is not in the given snapshot, find it by its id
				return findById(task.getLocked().getId(), task.getSnapshotInternalId());
			}
		}
		return null;
	}

	/**
	 * Return all AMIs associated to the given subscription. Note that
	 * "DescribeImages" does not work exactly the same way when
	 * <code>ImageId.N</code> filter is enabled. Without this filter, there is delay
	 * between CreateImage and its visibility.
	 *
	 * @param subscription
	 *            The related subscription identifier.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	private List<Snapshot> findAllBySubscription(final int subscription) {
		return findAll(subscription, "&Filter.1.Name=tag:" + TAG_SUBSCRIPTION + "&Filter.1.Value=" + subscription);
	}

	/**
	 * Return all AMIs visible owned by the account associated to the subscription.
	 * Note that "DescribeImages" does not work exactly the same way when
	 * <code>ImageId.N</code> filter is enabled. Without this filter, there is delay
	 * between CreateImage and its visibility.
	 *
	 * @param subscription
	 *            The related subscription identifier.
	 * @param filter
	 *            The additional "DescribeImages" filters. The base filter is
	 *            "Owner.1=self". When <code>null</code> or empty, all owned AMIs
	 *            are returned.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	private List<Snapshot> findAll(final int subscription, final String filter) {

		// Get all AMI associated to a snapshot and the subscription
		try {
			return toAmis(
					resource.processEC2(subscription, p -> "Action=DescribeImages&Owner.1=self" + StringUtils.defaultString(filter, "")));
		} catch (final Exception e) {
			log.error("DescribeImages failed for subscription {} and filter '{}'", subscription, filter, e);
			throw new BusinessException("snapshot-find failed");
		}
	}

	/**
	 * Find an AMI by its identifier. The images are not filtered by subscription
	 * since the AMI identifier is provided by the CreateImage service.
	 */
	private Snapshot findById(final int subscription, final String ami) {
		return findAll(subscription, "&ImageId.1=" + ami).stream().findAny().orElse(null);
	}

	private boolean matches(Snapshot snapshot, String criteria) {
		return StringUtils.containsIgnoreCase(StringUtils.defaultIfEmpty(snapshot.getName(), ""), criteria)
				|| StringUtils.containsIgnoreCase(snapshot.getId(), criteria)
				|| snapshot.getVolumes().stream().anyMatch(v -> StringUtils.containsIgnoreCase(v.getId(), criteria));
	}

	/**
	 * Create a new AMI from the given subscription. First, the related VM is
	 * located, then AMI is created, then tagged. Related volumes snapshots are also
	 * tagged.
	 * 
	 * @param subscription
	 *            The related subscription identifier.
	 * @param parameters
	 *            the subscription parameters.
	 * @param stop
	 *            When <code>true</code> the relate is stopped before the snapshot.
	 * @return The create AMI details.
	 */
	public void create(final int subscription, final Map<String, String> parameters, final boolean stop)
			throws XPathException, SAXException, IOException, ParserConfigurationException, InterruptedException {
		// Create the AMI
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("creating-ami");
			s.setWorkload(3);
		});
		final String instanceId = parameters.get(VmAwsPluginResource.PARAMETER_INSTANCE_ID);
		final String amiResponse = resource.processEC2(subscription,
				p -> "Action=CreateImage&NoReboot=" + (!stop) + "&InstanceId=" + instanceId + "&Name=ligoj-snapshot/" + subscription + "/"
						+ DateUtils.newCalendar().getTimeInMillis() + "&Description=Snapshot+created+from+Ligoj");
		if (amiResponse == null) {
			// AMI creation failed
			snapshotResource.endTask(subscription, true, s -> s.setStatusText(VmAwsPluginResource.KEY + ":ami-failed"));
			return;
		}

		// Get the AMI details from its identifier after a little while
		final String amiId = xml.getTagText(xml.parse(amiResponse), "imageId");
		// Thread.sleep(5000); // This throttle is required or AMI may not be visible

		// Tag for subscription and audit association
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("tagging-ami");
			s.setSnapshotInternalId(amiId);
			s.setDone(1);
		});
		if (resource.processEC2(subscription, p -> "Action=CreateTags&ResourceId.1=" + amiId + "&Tag.1.Key=" + TAG_SUBSCRIPTION
				+ "&Tag.1.Value=" + subscription + "&Tag.2.Key=" + TAG_AUDIT + "&Tag.2.Value=" + securityHelper.getLogin()) == null) {
			snapshotResource.endTask(subscription, true, s -> s.setStatusText(VmAwsPluginResource.KEY + ":ami-tag"));
		} else {
			snapshotResource.endTask(subscription, false, s -> {
				s.setDone(2);

				// This step is optional
				s.setPhase("checking-availability");
			});
		}
	}

	/**
	 * Convert a XML AMI node to a {@link Snapshot} instance.
	 */
	private Snapshot toAmi(final Element element) {
		final Snapshot snapshot = new Snapshot();
		snapshot.setId(xml.getTagText(element, "imageId"));
		snapshot.setName(xml.getTagText(element, "name"));
		snapshot.setDescription(xml.getTagText(element, "description"));
		snapshot.setStatusText(xml.getTagText(element, "imageState"));
		snapshot.setAvailable("available".equals(snapshot.getStatusText()));
		snapshot.setPending("pending".equals(snapshot.getStatusText()));

		final String date = xml.getTagText(element, "creationDate");
		try {
			// Volumes
			final XPath xPath = xpathFactory.newXPath();
			final NodeList volumes = (NodeList) xPath.compile("blockDeviceMapping/item").evaluate(element, XPathConstants.NODESET);
			snapshot.setVolumes(IntStream.range(0, volumes.getLength()).mapToObj(volumes::item).map(v -> toVolume((Element) v))
					.filter(v -> v.getId() != null).collect(Collectors.toList()));

			// Creation date
			snapshot.setDate(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").parse(date));
		} catch (final Exception pe) {
			snapshot.setVolumes(ListUtils.emptyIfNull(snapshot.getVolumes()));
			snapshot.setDate(new Date(0));
			log.info("Details of AMI {} cannot be fully parsed", snapshot.getId(), pe);
		}

		return snapshot;
	}

	/**
	 * Convert a XML AMI mapping device to {@link VolumeSnapshot} instance.
	 */
	private VolumeSnapshot toVolume(final Element element) {
		final VolumeSnapshot snapshot = new VolumeSnapshot();
		snapshot.setName(xml.getTagText(element, "deviceName"));

		// Only for EBS
		final NodeList ebs = element.getElementsByTagName("ebs");
		IntStream.range(0, ebs.getLength()).mapToObj(ebs::item).findFirst().ifPresent(v -> {
			final Element se = (Element) v;
			snapshot.setId(xml.getTagText(se, "snapshotId"));
			snapshot.setSize(Integer.valueOf(StringUtils.defaultString(xml.getTagText(se, "volumeSize"), "0")));
		});

		return snapshot;
	}

	/**
	 * Parse <code>DescribeImagesResponse</code> response to {@link Snapshot} list.
	 *
	 * @param amisAsXml
	 *            AMI descriptions as XML.
	 * @return The parsed AMI as {@link Snapshot}.
	 */
	private List<Snapshot> toAmis(final String amisAsXml)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final NodeList items = xml.getXpath(
				StringUtils.defaultIfEmpty(amisAsXml, "<DescribeImagesResponse><imagesSet></imagesSet></DescribeImagesResponse>"),
				"/DescribeImagesResponse/imagesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> toAmi((Element) n)).collect(Collectors.toList());
	}

}

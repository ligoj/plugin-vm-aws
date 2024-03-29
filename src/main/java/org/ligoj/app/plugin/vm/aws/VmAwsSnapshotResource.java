/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.aws;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.iam.IamProvider;
import org.ligoj.app.iam.SimpleUser;
import org.ligoj.app.iam.UserOrg;
import org.ligoj.app.plugin.vm.model.SnapshotOperation;
import org.ligoj.app.plugin.vm.model.VmSnapshotStatus;
import org.ligoj.app.plugin.vm.snapshot.Snapshot;
import org.ligoj.app.plugin.vm.snapshot.VmSnapshotResource;
import org.ligoj.app.plugin.vm.snapshot.VolumeSnapshot;
import org.ligoj.app.resource.plugin.XmlUtils;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.IntStream;

/**
 * AWS VM snapshot resource.
 *
 * @see <a href= "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateSnapshot.html">API_CreateSnapshot</a>
 * @see <a href=
 * "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeSnapshots.html">API_DescribeSnapshots</a>
 * @see <a href= "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DeleteSnapshot.html">API_DeleteSnapshot</a>
 * @see <a href= "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateImage.html">API_CreateImage</a>
 * @see <a href=
 * "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DeregisterImage.html">API_DeregisterImage</a>
 * @see <a href= "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeImages.html">API_DescribeImages</a>
 * @see <a href= "https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateTags.html">API_CreateTags</a>
 */
@Service
@Slf4j
public class VmAwsSnapshotResource {

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
	protected VmAwsPluginResource resource;

	@Autowired
	protected SubscriptionResource subscriptionResource;

	@Autowired
	protected VmSnapshotResource snapshotResource;

	@Autowired
	protected XmlUtils xml;

	@Autowired
	protected IamProvider[] iamProvider;

	/**
	 * Complete the task status from remote AWS information. Is considered as not completely finished when AMI tasks are
	 * finished without error at client side, and that AMI can be found by its identifier and yet not listed with tag
	 * filters.
	 *
	 * @param task The task to complete.
	 */
	protected void completeStatus(final VmSnapshotStatus task) {
		if (task.getOperation() == SnapshotOperation.CREATE && task.getSnapshotInternalId() != null) {
			// Create task is finished locally, AMI id is attached, check it remotely
			final var id = task.getSnapshotInternalId();
			final var ami = findById(task.getLocked().getId(), id);
			if (ami == null) {
				// AMI has been deleted of never been correctly created
				task.setFailed(true);
				task.setEnd(new Date());
				task.setFinishedRemote(true);
				task.setStatusText("not-found");
			} else if (findAllBySubscription(task.getLocked().getId()).stream().anyMatch(o -> o.getId().equals(id))) {
				// AMI is created and now listed
				setFinishedRemote(task);
			} else {
				// AMI is created and not yet listed
				task.setStatusText("not-finished-remote");
			}
		}
	}

	/**
	 * Create a new AMI from the given subscription. First, the name is fixed and based from the subscription and the
	 * current date, then AMI is created, then tagged.
	 *
	 * @param task A transient instance of the related task, and also linked to a subscription. Note it is a read-only
	 *             view.
	 * @throws IOException                  When VM definition XML cannot be retrieved.
	 * @throws SAXException                 When the XML validation failed.
	 * @throws ParserConfigurationException When the XML parsing failed.
	 */
	protected void create(final VmSnapshotStatus task) throws SAXException, IOException, ParserConfigurationException {
		final int subscription = task.getLocked().getId();
		final var parameters = subscriptionResource.getParametersNoCheck(subscription);
		// Create the AMI
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("creating-ami");
			s.setWorkload(3);
		});
		final var instanceId = parameters.get(VmAwsPluginResource.PARAMETER_INSTANCE_ID);
		final var amiCreateDate = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(task.getStart());
		final var amiName = subscription + "/" + amiCreateDate;
		final var amiResponse = resource.processEC2(parameters,
				"Action=CreateImage&NoReboot=" + (!task.isStop()) + "&InstanceId=" + instanceId
						+ "&Name=ligoj-snapshot/" + amiName + "&Description=Snapshot+created+from+Ligoj");
		if (amiResponse == null) {
			// AMI creation failed
			snapshotResource.endTask(subscription, true, s -> {
				s.setStatusText(VmAwsPluginResource.KEY + ":ami-create-failed");
				s.setFinishedRemote(true);
			});
			return;
		}

		// Get the AMI details from its identifier after a little while
		final var amiId = xml.getTagText(xml.parse(amiResponse), "imageId");

		// Tag for subscription and audit association
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("tagging-ami");
			s.setSnapshotInternalId(amiId);
			s.setDone(1);
		});
		if (!isReturnTrue(resource.processEC2(subscription,
				p -> "Action=CreateTags&ResourceId.1=" + amiId + "&Tag.1.Key=" + TAG_SUBSCRIPTION + "&Tag.1.Value="
						+ subscription + "&Tag.2.Key=" + TAG_AUDIT + "&Tag.2.Value=" + task.getAuthor()))) {
			snapshotResource.endTask(subscription, true, s -> {
				s.setStatusText(VmAwsPluginResource.KEY + ":ami-tag-failed");
				s.setFinishedRemote(true);
			});
		} else {
			snapshotResource.endTask(subscription, false, s -> {
				s.setDone(2);

				// This step is optional
				s.setPhase("checking-availability");
			});
		}
	}

	/**
	 * Delete a snapshot.
	 *
	 * @param task A transient instance of the related task, and also linked to a subscription. Note it is a read-only
	 *             view.
	 * @throws ParserConfigurationException XML parsing failed.
	 * @throws IOException                  XML reading failed by the parser.
	 * @throws SAXException                 XML processing failed.
	 */
	public void delete(final VmSnapshotStatus task) throws SAXException, IOException, ParserConfigurationException {
		final var subscription = task.getLocked().getId();
		// Initiate the task, validate the AMI to delete
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("searching-ami");
			s.setWorkload(3);
		});

		final var amiId = task.getSnapshotInternalId();
		final var ami = findById(subscription, amiId);

		if (ami == null) {
			// AMI has been deleted of never been correctly created
			snapshotResource.endTask(subscription, true, s -> {
				s.setStatusText(VmAwsPluginResource.KEY + ":ami-not-found");
				s.setFinishedRemote(true);
			});
			return;
		}

		// AMI has been found, unregister it
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("unregistering-ami");
			s.setDone(1);
		});
		if (!isReturnTrue(resource.processEC2(subscription, p -> "Action=DeregisterImage&ImageId=" + amiId))) {
			// Unregistering failed
			snapshotResource.endTask(subscription, true, s -> {
				s.setStatusText(VmAwsPluginResource.KEY + ":ami-unregistering-failed");
				s.setFinishedRemote(true);
			});
			return;
		}

		// AMI unregistering has been forwarded, need to delete the snapshot now
		snapshotResource.nextStep(subscription, s -> {
			s.setPhase("deleting-snapshots");
			s.setDone(2);
		});
		final var query = new StringBuilder();
		IntStream.range(0, ami.getVolumes().size())
				.forEach(i -> query.append("&SnapshotId.").append(i + 1).append("=").append(ami.getVolumes().get(i).getId()));
		if (!isReturnTrue(resource.processEC2(subscription, p -> "Action=DeleteSnapshot" + query))) {
			// Deleting snapshots failed
			snapshotResource.endTask(subscription, true, s -> {
				s.setStatusText(VmAwsPluginResource.KEY + ":ami-deleting-snapshots-failed");
				s.setFinishedRemote(true);
			});
			return;
		}
		snapshotResource.endTask(subscription, false, s -> {
			s.setDone(3);
			s.setFinishedRemote(true);
		});
	}

	/**
	 * Return all AMIs visible owned by the account associated to the subscription. Note that "DescribeImages" does not
	 * work exactly the same way when <code>ImageId.N</code> filter is enabled. Without this filter, there is delay
	 * between CreateImage and its visibility.
	 *
	 * @param subscription The related subscription identifier.
	 * @param filter       The additional "DescribeImages" filters. The base filter is "Owner.1=self". When
	 *                     <code>null</code> or empty, all owned AMIs are returned.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	private List<Snapshot> findAll(final int subscription, final String filter) {

		// Get all AMI associated to a snapshot and the subscription
		try {
			return toAmiList(resource.processEC2(subscription,
					p -> "Action=DescribeImages&Owner.1=self" + Objects.toString(filter, "")));
		} catch (final Exception e) {
			log.error("DescribeImages failed for subscription {} and filter '{}'", subscription, filter, e);
			throw new BusinessException("DescribeImages-failed");
		}
	}

	/**
	 * Return all AMIs matching to the given criteria and also associated to the given subscription. Note that
	 * "DescribeImages" does not work exactly the same way when <code>ImageId.N</code> filter is enabled. Without this
	 * filter, there is delay between CreateImage and its visibility.
	 *
	 * @param subscription The related subscription identifier.
	 * @param criteria     The search criteria. Case is insensitive. The criteria try to match the AMI's identifier, the
	 *                     AMI's name or one of its volume snapshots identifier machine name and identifier. Not
	 *                     <code>null</code>.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	public List<Snapshot> findAllByNameOrId(final int subscription, final String criteria) {
		return findAllByNameOrId(subscription, criteria, snapshotResource.getTask(subscription));
	}

	/**
	 * Return all AMIs matching to the given criteria and also associated to the given subscription. Note that
	 * "DescribeImages" does not work exactly the same way when <code>ImageId.N</code> filter is enabled. Without this
	 * filter, there is delay between CreateImage and its visibility.
	 *
	 * @param subscription The related subscription identifier.
	 * @param criteria     The search criteria. Case is insensitive. The criteria try to match the AMI's identifier, the
	 *                     AMI's name or one of its volume snapshots identifier machine name and identifier. Not
	 *                     <code>null</code>.
	 * @param task         The current task used to prepend the globally visible AMIs.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	private List<Snapshot> findAllByNameOrId(final int subscription, final String criteria,
			final VmSnapshotStatus task) {
		final var snapshots = new ArrayList<>(findAllBySubscription(subscription).stream().filter(s -> matches(s, criteria))
				.sorted((a, b) -> b.getDate().compareTo(a.getDate())).toList());

		// Add the current task to the possible running snapshots
		if (task != null) {
			Optional.ofNullable(findUnlistedAmi(snapshots, task)).filter(s -> matches(s, criteria))
					.ifPresent(snapshots::addFirst);

			// Update the operation type from the task
			snapshots.stream().filter(s -> StringUtils.equals(task.getSnapshotInternalId(), s.getId()))
					.forEach(s -> s.setOperation(task.getOperation()));
		}
		return snapshots;
	}

	/**
	 * Return all AMIs associated to the given subscription. Note that "DescribeImages" does not work exactly the same
	 * way when <code>ImageId.N</code> filter is enabled. Without this filter, there is delay between CreateImage and
	 * its visibility.
	 *
	 * @param subscription The related subscription identifier.
	 * @return Matching AMIs ordered by descending creation date.
	 */
	private List<Snapshot> findAllBySubscription(final int subscription) {
		return findAll(subscription, "&Filter.1.Name=tag:" + TAG_SUBSCRIPTION + "&Filter.1.Value=" + subscription);
	}

	/**
	 * Find an AMI by its identifier. The images are not filtered by subscription since the AMI identifier is provided
	 * by the CreateImage service.
	 */
	private Snapshot findById(final int subscription, final String ami) {
		return findAll(subscription, "&ImageId.1=" + ami).stream().findAny().orElse(null);
	}

	/**
	 * Return the AMI corresponding to the given task and that is not in the given snapshot list.
	 *
	 * @param snapshots The snapshots list to check.
	 * @param task      The task to check.
	 * @return The not yet globally visible AMI or <code>null</code>.
	 */
	private Snapshot findUnlistedAmi(final List<Snapshot> snapshots, final VmSnapshotStatus task) {
		Snapshot ami = null;
		if (task.isFailed()) {
			task.setFinishedRemote(true);
			ami = toAmi(task, null);
			ami.setPending(false);
		} else if (task.getSnapshotInternalId() == null) {
			// AMI is not yet created at all
			ami = toAmi(task, "not-created");
		} else if (!task.isFinishedRemote()) {
			// Asynchronous management : Create vs Describe
			if (snapshots.stream().anyMatch(s -> s.getId().equals(task.getSnapshotInternalId()))) {
				// AMI is listed, and has been completed after the shutdown of the client
				setFinishedRemote(task);
			} else {
				// AMI is unlisted, and yet has been created by the task, find it by its identifier
				ami = findById(task.getLocked().getId(), task.getSnapshotInternalId());
				if (ami == null) {
					// AMI is unlisted and not yet found by AWS with direct link, would fail
					ami = toAmi(task, "not-found");
				} else {
					// Complete the author from the task data
					ami.setAuthor(getUser(task.getAuthor()));
					setPending(ami, "not-finished-remote");
				}
			}
		}
		return ami;
	}

	/**
	 * Request IAM provider to get user details.
	 *
	 * @param login The requested user login.
	 * @return Either the resolved instance, either <code>null</code> when not found.
	 */
	protected SimpleUser getUser(final String login) {
		return Optional.ofNullable(iamProvider[0].getConfiguration().getUserRepository().findById(login))
				.orElseGet(() -> {
					// Untracked user
					final var user = new UserOrg();
					user.setId(login);
					return user;
				});
	}

	/**
	 * Indicate the AWS response is <code>true</code>.
	 *
	 * @param response The AWS response.
	 * @return <code>true</code> when the AWS response succeed.
	 * @throws ParserConfigurationException XML parsing failed.
	 * @throws IOException                  XML reading failed by the parser.
	 * @throws SAXException                 XML processing failed.
	 */
	private boolean isReturnTrue(final String response) throws SAXException, IOException, ParserConfigurationException {
		return response != null && BooleanUtils.toBoolean(xml.getTagText(xml.parse(response), "return"));
	}

	/**
	 * Check the given snapshot matches to the criteria : name, id, or one of its volume id.
	 */
	private boolean matches(final Snapshot snapshot, final String criteria) {
		return StringUtils.containsIgnoreCase(StringUtils.defaultIfEmpty(snapshot.getName(), ""), criteria)
				|| StringUtils.containsIgnoreCase(snapshot.getId(), criteria)
				|| snapshot.getVolumes().stream().anyMatch(v -> StringUtils.containsIgnoreCase(v.getId(), criteria));
	}

	/**
	 * Update the given task as finished remotely
	 */
	private void setFinishedRemote(final VmSnapshotStatus task) {
		task.setFinishedRemote(true);
		task.setDone(3);
		task.setPhase("checking-availability");
	}

	/**
	 * Mark the given snapshot as unavailable with the given status
	 */
	private void setPending(final Snapshot snapshot, final String statusText) {
		snapshot.setPending(true);
		snapshot.setAvailable(false);
		snapshot.setStatusText(statusText);
	}

	/**
	 * Convert a XML AMI node to a {@link Snapshot} instance.
	 */
	private Snapshot toAmi(final Element element) {
		final var snapshot = new Snapshot();
		snapshot.setId(xml.getTagText(element, "imageId"));
		snapshot.setName(xml.getTagText(element, "name"));
		snapshot.setDescription(StringUtils.trimToNull(xml.getTagText(element, "description")));
		snapshot.setStatusText(xml.getTagText(element, "imageState"));
		snapshot.setAvailable("available".equals(snapshot.getStatusText()));
		snapshot.setPending("pending".equals(snapshot.getStatusText()));

		final var date = xml.getTagText(element, "creationDate");
		final var xPath = xml.xpathFactory.newXPath();
		try {
			// Author
			final var tags = (NodeList) xPath.compile("tagSet/item").evaluate(element, XPathConstants.NODESET);
			snapshot.setAuthor(IntStream.range(0, tags.getLength()).mapToObj(tags::item)
					.filter(t -> TAG_AUDIT.equals(xml.getTagText((Element) t, "key")))
					.map(t -> xml.getTagText((Element) t, "value")).map(this::getUser).findAny().orElse(null));

			// Volumes
			final var volumes = (NodeList) xPath.compile("blockDeviceMapping/item").evaluate(element,
					XPathConstants.NODESET);
			snapshot.setVolumes(IntStream.range(0, volumes.getLength()).mapToObj(volumes::item)
					.map(v -> toVolume((Element) v)).filter(v -> v.getId() != null).toList());

			// Creation date
			snapshot.setDate(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").parse(date));
		} catch (final Exception pe) {
			// Invalid of not correctly managed XML content
			snapshot.setVolumes(ListUtils.emptyIfNull(snapshot.getVolumes()));
			snapshot.setDate(new Date(0));
			log.info("Details of AMI {} cannot be fully parsed", snapshot.getId(), pe);
		}

		return snapshot;
	}

	/**
	 * Convert a task to an unavailable snapshot
	 */
	private Snapshot toAmi(final VmSnapshotStatus task, final String statusText) {
		final var taskAsSnapshot = new Snapshot();
		taskAsSnapshot.setId(task.getSnapshotInternalId());
		taskAsSnapshot.setAuthor(getUser(task.getAuthor()));
		taskAsSnapshot.setDate(task.getStart());
		taskAsSnapshot.setStopRequested(task.isStop());

		// Override the status since this AMI is not really GA
		setPending(taskAsSnapshot, Objects.toString(statusText, task.getStatusText()));
		return taskAsSnapshot;
	}

	/**
	 * Parse <code>DescribeImagesResponse</code> response to {@link Snapshot} list.
	 *
	 * @param amiListAsXml AMI descriptions as XML.
	 * @return The parsed AMI as {@link Snapshot}.
	 */
	private List<Snapshot> toAmiList(final String amiListAsXml)
			throws XPathExpressionException, SAXException, IOException, ParserConfigurationException {
		final var items = xml.getXpath(
				StringUtils.defaultIfEmpty(amiListAsXml,
						"<DescribeImagesResponse><imagesSet></imagesSet></DescribeImagesResponse>"),
				"/DescribeImagesResponse/imagesSet/item");
		return IntStream.range(0, items.getLength()).mapToObj(items::item).map(n -> toAmi((Element) n))
				.toList();
	}

	/**
	 * Convert a XML AMI mapping device to {@link VolumeSnapshot} instance.
	 */
	private VolumeSnapshot toVolume(final Element element) {
		final var snapshot = new VolumeSnapshot();
		snapshot.setName(xml.getTagText(element, "deviceName"));

		// Only for EBS
		final var ebs = element.getElementsByTagName("ebs");
		IntStream.range(0, ebs.getLength()).mapToObj(ebs::item).findFirst().ifPresent(v -> {
			final var se = (Element) v;
			snapshot.setId(xml.getTagText(se, "snapshotId"));
			snapshot.setSize(Integer.parseInt(Objects.toString(xml.getTagText(se, "volumeSize"), "0")));
		});

		return snapshot;
	}
}

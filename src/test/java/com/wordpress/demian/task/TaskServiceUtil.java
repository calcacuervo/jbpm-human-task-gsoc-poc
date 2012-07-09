package com.wordpress.demian.task;

import static org.jbpm.task.service.persistence.TaskPersistenceManager.addParametersToMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityNotFoundException;
import javax.persistence.Query;

import org.drools.RuleBase;
import org.drools.core.util.StringUtils;
import org.jbpm.task.Attachment;
import org.jbpm.task.Comment;
import org.jbpm.task.Content;
import org.jbpm.task.Deadline;
import org.jbpm.task.Group;
import org.jbpm.task.OrganizationalEntity;
import org.jbpm.task.PeopleAssignments;
import org.jbpm.task.Status;
import org.jbpm.task.Task;
import org.jbpm.task.TaskData;
import org.jbpm.task.User;
import org.jbpm.task.identity.UserGroupCallback;
import org.jbpm.task.identity.UserGroupCallbackManager;
import org.jbpm.task.service.Allowed;
import org.jbpm.task.service.CannotAddTaskException;
import org.jbpm.task.service.ContentData;
import org.jbpm.task.service.FaultData;
import org.jbpm.task.service.Operation;
import org.jbpm.task.service.OperationCommand;
import org.jbpm.task.service.PermissionDeniedException;
import org.jbpm.task.service.SendIcal;
import org.jbpm.task.service.TaskException;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.TaskServiceSession;
import org.jbpm.task.service.persistence.TaskPersistenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskServiceUtil {
	private final TaskPersistenceManager tpm;
	private final TaskService service;

	private Map<String, Boolean> userGroupsMap = new HashMap<String, Boolean>();

	private static final Logger logger = LoggerFactory
			.getLogger(TaskServiceSession.class);

	public TaskServiceUtil(final TaskService service,
			final TaskPersistenceManager tpm) {
		this.service = service;
		this.tpm = tpm;
	}

	public TaskPersistenceManager getTaskPersistenceManager() {
		return tpm;
	}

	public org.jbpm.task.service.TaskService getService() {
		return service;
	}

	void evalCommand(final Operation operation,
			final OperationCommand command, final Task task,
			final User user, final OrganizationalEntity targetEntity,
			List<String> groupIds) throws PermissionDeniedException {

		if (!isAllowed(command, task, user, groupIds)) {
							String errorMessage = "User '"
									+ user
									+ "' does not have permissions to execution operation '"
									+ operation + "' on task id "
									+ task.getId();

							throw new PermissionDeniedException(errorMessage);
		}
		commands(command, task, user, targetEntity);
	}

	private boolean isAllowed(final OperationCommand command, final Task task,
			final User user, List<String> groupIds) {
		final PeopleAssignments people = task.getPeopleAssignments();
		final TaskData taskData = task.getTaskData();

		boolean operationAllowed = false;
		for (Allowed allowed : command.getAllowed()) {
			if (operationAllowed) {
				break;
			}
			switch (allowed) {
			case Owner: {
				operationAllowed = (taskData.getActualOwner() != null && taskData
						.getActualOwner().equals(user));
				break;
			}
			case Initiator: {
				operationAllowed = (taskData.getCreatedBy() != null
						&& (taskData.getCreatedBy().equals(user)) || (groupIds != null && groupIds
						.contains(taskData.getCreatedBy().getId())));
				break;
			}
			case PotentialOwner: {
				operationAllowed = isAllowed(user, groupIds,
						people.getPotentialOwners());
				break;
			}
			case BusinessAdministrator: {
				operationAllowed = isAllowed(user, groupIds,
						people.getBusinessAdministrators());
				break;
			}
			case Anyone: {
				operationAllowed = true;
				break;
			}
			}
		}

		if (operationAllowed && command.isUserIsExplicitPotentialOwner()) {
			// if user has rights to execute the command, make sure user is
			// explicitly specified (not as a group)
			operationAllowed = people.getPotentialOwners().contains(user);
		}

		if (operationAllowed && command.isSkippable()) {
			operationAllowed = taskData.isSkipable();
		}

		return operationAllowed;
	}

	private void commands(final OperationCommand command, final Task task,
			final User user, final OrganizationalEntity targetEntity) {
		final PeopleAssignments people = task.getPeopleAssignments();
		final TaskData taskData = task.getTaskData();

		if (command.getNewStatus() != null) {
			taskData.setStatus(command.getNewStatus());
		} else if (command.isSetToPreviousStatus()) {
			taskData.setStatus(taskData.getPreviousStatus());
		}

		if (command.isAddTargetEntityToPotentialOwners()
				&& !people.getPotentialOwners().contains(targetEntity)) {
			people.getPotentialOwners().add(targetEntity);
		}

		if (command.isRemoveUserFromPotentialOwners()) {
			people.getPotentialOwners().remove(user);
		}

		if (command.isSetNewOwnerToUser()) {
			taskData.setActualOwner(user);
		}

		if (command.isSetNewOwnerToNull()) {
			taskData.setActualOwner(null);
		}

		if (command.getExec() != null) {
			switch (command.getExec()) {
			case Claim: {
				taskData.setActualOwner((User) targetEntity);
				// Task was reserved so owner should get icals
				SendIcal.getInstance().sendIcalForTask(task,
						service.getUserinfo());

				// trigger event support
				service.getEventSupport().fireTaskClaimed(task.getId(),
						task.getTaskData().getActualOwner().getId());
				break;
			}
			}
		}
	}

	public void taskOperation(final OperationCommand commands,
			final Operation operation, final long taskId, final String userId,
			final String targetEntityId, final ContentData data,
			List<String> groupIds) throws TaskException {
		OrganizationalEntity targetEntity = null;

		groupIds = doUserGroupCallbackOperation(userId, groupIds);
		doCallbackUserOperation(targetEntityId);
		if (targetEntityId != null) {
			targetEntity = getEntity(OrganizationalEntity.class, targetEntityId);
		}

		final Task task = getTask(taskId);
		User user = getEntity(User.class, userId);

		boolean transactionOwner = false;
		try {

			transactionOwner = tpm.beginTransaction();

			evalCommand(operation, commands, task, user, targetEntity, groupIds);

			switch (operation) {
			case Claim: {
				taskClaimOperation(task);
				break;
			}
			case Complete: {
				taskCompleteOperation(task, data);
				break;
			}
			case Fail: {
				taskFailOperation(task, data);
				break;
			}
			case Skip: {
				taskSkipOperation(task, userId);
				break;
			}
			case Remove: {
				taskRemoveOperation(task, user);
				break;
			}
			case Register: {
				taskRegisterOperation(task, user);
				break;
			}
			}

			tpm.endTransaction(transactionOwner);

		} catch (RuntimeException re) {

			// We may not be the tx owner -- but something has gone wrong.
			// ..which is why we make ourselves owner, and roll the tx back.
			boolean takeOverTransaction = true;
			tpm.rollBackTransaction(takeOverTransaction);

			doOperationInTransaction(new TransactedOperation() {
				public void doOperation() {
					task.getTaskData().setStatus(Status.Error);
				}
			});

			throw re;
		}

		switch (operation) {
		case Start: {
			postTaskStartOperation(task);
			break;
		}
		case Forward: {
			postTaskForwardOperation(task);
			break;
		}
		case Release: {
			postTaskReleaseOperation(task);
			break;
		}
		case Stop: {
			postTaskStopOperation(task);
			break;
		}
		case Claim: {
			postTaskClaimOperation(task);
			break;
		}
		case Complete: {
			postTaskCompleteOperation(task);
			break;
		}
		case Fail: {
			postTaskFailOperation(task);
			break;
		}
		case Skip: {
			postTaskSkipOperation(task, userId);
			break;
		}
		case Exit: {
			postTaskExitOperation(task, userId);
			break;
		}
		}

	}

	private void taskClaimOperation(final Task task) {
		// Task was reserved so owner should get icals
		SendIcal.getInstance().sendIcalForTask(task, service.getUserinfo());
	}

	private void postTaskClaimOperation(final Task task) {
		// trigger event support
		service.getEventSupport().fireTaskClaimed(task.getId(),
				task.getTaskData().getActualOwner().getId());
	}

	private void postTaskStartOperation(final Task task) {
		// trigger event support
		service.getEventSupport().fireTaskStarted(task.getId(),
				task.getTaskData().getActualOwner().getId());
	}

	private void postTaskForwardOperation(final Task task) {
		// trigger event support
		String actualOwner = "";
		if (task.getTaskData().getActualOwner() != null) {
			actualOwner = task.getTaskData().getActualOwner().getId();
		}
		service.getEventSupport().fireTaskForwarded(task.getId(), actualOwner);
	}

	private void postTaskReleaseOperation(final Task task) {
		// trigger event support
		String actualOwner = "";
		if (task.getTaskData().getActualOwner() != null) {
			actualOwner = task.getTaskData().getActualOwner().getId();
		}
		service.getEventSupport().fireTaskReleased(task.getId(), actualOwner);
	}

	private void postTaskStopOperation(final Task task) {
		// trigger event support
		service.getEventSupport().fireTaskStopped(task.getId(),
				task.getTaskData().getActualOwner().getId());
	}

	private void taskCompleteOperation(final Task task, final ContentData data) {
		if (data != null) {
			setOutput(task.getId(),
					task.getTaskData().getActualOwner().getId(), data);
		}
		checkSubTaskStrategy(task);
	}

	private void postTaskCompleteOperation(final Task task) {
		service.unschedule(task.getId());
		// trigger event support
		service.getEventSupport().fireTaskCompleted(task.getId(),
				task.getTaskData().getActualOwner().getId());
	}

	private void taskFailOperation(final Task task, final ContentData data) {
		// set fault data
		if (data != null) {
			setFault(task.getId(), task.getTaskData().getActualOwner().getId(),
					(FaultData) data);
		}
	}

	private void postTaskFailOperation(final Task task) {
		service.unschedule(task.getId());
		// trigger event support
		service.getEventSupport().fireTaskFailed(task.getId(),
				task.getTaskData().getActualOwner().getId());
	}

	private void taskSkipOperation(final Task task, final String userId) {
		checkSubTaskStrategy(task);
	}

	private void postTaskSkipOperation(final Task task, final String userId) {
		service.unschedule(task.getId());
		// trigger event support
		service.getEventSupport().fireTaskSkipped(task.getId(), userId);
	}

	private void postTaskExitOperation(final Task task, final String userId) {
		service.unschedule(task.getId());
	}

	public Task getTask(final long taskId) {
		return getEntity(Task.class, taskId);
	}

	public Deadline getDeadline(final long deadlineId) {
		return (Deadline) tpm.findEntity(Deadline.class, deadlineId);
	}

	public void setTaskStatus(final long taskId, Status status) {
		tpm.setTaskStatusInTransaction(taskId, status);
	}

	public void addComment(final long taskId, final Comment comment) {
		final Task task = getTask(taskId);
		doCallbackOperationForComment(comment);

		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				task.getTaskData().addComment(comment);
			}
		});
	}

	public void addAttachment(final long taskId, final Attachment attachment,
			final Content content) {
		final Task task = getTask(taskId);
		doCallbackOperationForAttachment(attachment);

		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				tpm.saveEntity(content);
				attachment.setContent(content);
				task.getTaskData().addAttachment(attachment);
			}
		});
	}

	public void setDocumentContent(final long taskId, final Content content) {
		final Task task = getTask(taskId);

		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				tpm.saveEntity(content);

				task.getTaskData().setDocumentContentId(content.getId());
			}
		});
	}

	/**
	 * This method should only be called from a ServerHandler or TaskService
	 * implementation. </p> If you need a Content object (and are already
	 * running within a tx), then just use tpm.findEntity(...).
	 * 
	 * @param contentId
	 *            The id of the Content object.
	 * @return The requested Content object.
	 */
	public Content getContent(final long contentId) {
		// The Content object contains a LOB which requires a tx in some db's

		final Content[] result = new Content[1];
		result[0] = null;

		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				result[0] = (Content) tpm.findEntity(Content.class, contentId);
			}
		});

		return result[0];
	}

	public void deleteAttachment(final long taskId, final long attachmentId,
			final long contentId) {
		// TODO I can't get this to work with HQL deleting the Attachment.
		// Hibernate needs both the item removed from the collection and also
		// the item deleted,
		// so for now, we have to load the entire Task.
		// I suspect that this is due to using the same EM which is caching
		// things.
		final Task task = getTask(taskId);

		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				final Attachment removedAttachment = task.getTaskData()
						.removeAttachment(attachmentId);

				if (removedAttachment != null) {
					// need to do this otherwise it just removes the link id,
					// without removing the attachment
					tpm.deleteEntity(removedAttachment);
				}

				// we do this as HQL to avoid streaming in the entire HQL
				final String deleteContent = "delete from Content c where c.id = :id";
				Query query = tpm.createNewQuery(deleteContent);
				query.setParameter("id", contentId);
				query.executeUpdate();
			}
		});
	}

	public void deleteComment(final long taskId, final long commentId) {
		// @TODO I can't get this to work with HQL deleting the Comment.
		// Hibernate needs both the item removed from the collection
		// and also the item deleted, so for now have to load the entire Task, I
		// suspect that this is due to using the same EM which
		// is caching things.
		final Task task = getTask(taskId);

		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				final Comment removedComment = task.getTaskData()
						.removeComment(commentId);

				if (removedComment != null) {
					// need to do this otherwise it just removes the link id,
					// without removing the attachment
					tpm.deleteEntity(removedComment);
				}
			}
		});
	}

	public Task getTaskByWorkItemId(final long workItemId) {
		HashMap<String, Object> params = addParametersToMap("workItemId",
				workItemId);
		Object taskObject = tpm.queryWithParametersInTransaction(
				"TaskByWorkItemId", params, true);
		return (Task) taskObject;
	}

	private void taskRemoveOperation(final Task task, final User user) {
		if (task.getPeopleAssignments().getRecipients().contains(user)) {
			task.getPeopleAssignments().getRecipients().remove(user);
		} else {
			throw new RuntimeException("Couldn't remove user " + user.getId()
					+ " since it isn't a notification recipient");
		}
	}

	private void taskRegisterOperation(final Task task, final User user) {
		if (!task.getPeopleAssignments().getRecipients().contains(user)) {
			task.getPeopleAssignments().getRecipients().add(user);
		}
	}

	public void nominateTask(final long taskId, String userId,
			final List<OrganizationalEntity> potentialOwners) {
		doCallbackUserOperation(userId);
		doCallbackOperationForPotentialOwners(potentialOwners);

		final Task task = getEntity(Task.class, taskId);
		final User user = getEntity(User.class, userId);
		if (isAllowed(user, null, task.getPeopleAssignments()
				.getBusinessAdministrators())) {
			doOperationInTransaction(new TransactedOperation() {
				public void doOperation() {
					task.getTaskData().assignOwnerAndStatus(potentialOwners);
					if (task.getTaskData().getStatus() == Status.Ready) {
						task.getPeopleAssignments().setPotentialOwners(
								potentialOwners);
					}
				}
			});
		} else {
			throw new PermissionDeniedException("User " + userId
					+ " is not allowed to perform Nominate on Task " + taskId);
		}
	}

	private Task getTaskAndCheckTaskUserId(long taskId, String userId,
			String operation) {
		Task task = getEntity(Task.class, taskId);
		if (!userId.equals(task.getTaskData().getActualOwner().getId())) {
			throw new RuntimeException("User " + userId
					+ " is not the actual owner of the task " + taskId
					+ " and can't perform " + operation);
		}
		return task;
	}

	public void setOutput(final long taskId, final String userId,
			final ContentData outputContentData) {
		final Task task = getTaskAndCheckTaskUserId(taskId, userId, "setOutput");
		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				Content content = new Content();
				content.setContent(outputContentData.getContent());
				tpm.saveEntity(content);
				task.getTaskData()
						.setOutput(content.getId(), outputContentData);

			}
		});
	}

	public void setFault(final long taskId, final String userId,
			final FaultData faultContentData) {
		final Task task = getTaskAndCheckTaskUserId(taskId, userId, "setFault");
		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				Content content = new Content();
				content.setContent(faultContentData.getContent());
				tpm.saveEntity(content);
				task.getTaskData().setFault(content.getId(), faultContentData);

			}
		});
	}

	

	private boolean isAllowed(final User user, final List<String> groupIds,
			final List<OrganizationalEntity> entities) {
		// for now just do a contains, I'll figure out group membership later.
		for (OrganizationalEntity entity : entities) {
			if (entity instanceof User && entity.equals(user)) {
				return true;
			}
			if (entity instanceof Group && groupIds != null
					&& groupIds.contains(entity.getId())) {
				return true;
			}
		}
		return false;
	}

	private void checkSubTaskStrategy(final Task task) {
		// for (SubTasksStrategy strategy : task.getSubTaskStrategies()) {
		// strategy.execute(this, service, task);
		// }
		//
		// final Task parentTask;
		// if (task.getTaskData().getParentId() != -1) {
		// parentTask = getTask(task.getTaskData().getParentId());
		// for (SubTasksStrategy strategy : parentTask.getSubTaskStrategies()) {
		// strategy.execute(this, service, parentTask);
		// }
		// }
	}

	/**
	 * Returns the entity of the specified class by for the specified
	 * primaryKey.
	 * 
	 * @param entityClass
	 *            - class of entity to return
	 * @param primaryKey
	 *            - key of entity
	 * @return entity or <code>EntityNotFoundException</code> if the entity
	 *         cannot be found
	 * @throws EntityNotFoundException
	 *             if entity not found
	 */
	private <T> T getEntity(final Class<T> entityClass, final Object primaryKey) {
		final T entity = (T) tpm.findEntity(entityClass, primaryKey);

		if (entity == null) {
			throw new EntityNotFoundException("No "
					+ entityClass.getSimpleName() + " with ID " + primaryKey
					+ " was found!");
		}

		return entity;
	}

	/**
	 * Persists the specified object within a new transaction. If there are any
	 * problems, the transaction will be rolled back.
	 * 
	 * @param object
	 *            object to persists
	 */
	private void persistInTransaction(final Object object) {
		doOperationInTransaction(new TransactedOperation() {
			public void doOperation() {
				tpm.saveEntity(object);
			}
		});
	}

	/**
	 * Executes the specified operation within a transaction. Note that if there
	 * is a currently active transaction, if will reuse it.
	 * 
	 * This logic is unfortunately duplicated in
	 * {@link TaskPersistenceManager#queryWithParametersInTransaction(String, Map)}
	 * . If you change the logic here, please make sure to change the logic
	 * there as well (and vice versa).
	 * 
	 * @param operation
	 *            operation to execute
	 */
	private void doOperationInTransaction(final TransactedOperation operation) {

		boolean txOwner = false;
		boolean operationSuccessful = false;
		boolean txStarted = false;
		try {
			txOwner = tpm.beginTransaction();
			txStarted = true;

			operation.doOperation();
			operationSuccessful = true;

			tpm.endTransaction(txOwner);
		} catch (Exception e) {
			tpm.rollBackTransaction(txOwner);

			String message;
			if (!txStarted) {
				message = "Could not start transaction.";
			} else if (!operationSuccessful) {
				message = "Operation failed";
			} else {
				message = "Could not commit transaction";
			}

			if (e instanceof TaskException) {
				throw (TaskException) e;
			} else {
				throw new RuntimeException(message, e);
			}
		}

	}


	private interface TransactedOperation {
		void doOperation();
	}


	private List<String> doUserGroupCallbackOperation(String userId,
			List<String> groupIds) {
		if (UserGroupCallbackManager.getInstance().existsCallback()) {
			doCallbackUserOperation(userId);
			doCallbackGroupsOperation(userId, groupIds);
			List<String> allGroupIds = null;
			if (UserGroupCallbackManager.getInstance().getProperty(
					"disable.all.groups") == null) {
				// get all groups
				// (The fact that this isn't done in a query will probably
				// become a problem at some point.. )
				Query query = tpm.createNewQuery("select g.id from Group g");
				allGroupIds = ((List<String>) query.getResultList());
			}
			return UserGroupCallbackManager.getInstance().getCallback()
					.getGroupsForUser(userId, groupIds, allGroupIds);
		} else {
			logger.debug("UserGroupCallback has not been registered.");
			return groupIds;
		}
	}

	private boolean doCallbackUserOperation(String userId) {
		if (UserGroupCallbackManager.getInstance().existsCallback()) {
			if (userId != null
					&& UserGroupCallbackManager.getInstance().getCallback()
							.existsUser(userId)) {
				addUserFromCallbackOperation(userId);
				return true;
			}
			return false;
		} else {
			logger.debug("UserGroupCallback has not been registered.");
			// returns true for backward compatibility
			return true;
		}
	}

	private boolean doCallbackGroupOperation(String groupId) {
		if (UserGroupCallbackManager.getInstance().existsCallback()) {
			if (groupId != null
					&& UserGroupCallbackManager.getInstance().getCallback()
							.existsGroup(groupId)) {
				addGroupFromCallbackOperation(groupId);
				return true;
			}
			return false;
		} else {
			logger.debug("UserGroupCallback has not been registered.");
			// returns true for backward compatibility
			return true;
		}
	}


	private void doCallbackOperationForPotentialOwners(
			List<OrganizationalEntity> potentialOwners) {
		if (UserGroupCallbackManager.getInstance().existsCallback()
				&& potentialOwners != null) {
			List<OrganizationalEntity> nonExistingEntities = new ArrayList<OrganizationalEntity>();

			for (OrganizationalEntity orgEntity : potentialOwners) {
				if (orgEntity instanceof User) {
					boolean userExists = doCallbackUserOperation(orgEntity
							.getId());
					if (!userExists) {
						nonExistingEntities.add(orgEntity);
					}
				}
				if (orgEntity instanceof Group) {
					boolean groupExists = doCallbackGroupOperation(orgEntity
							.getId());
					if (!groupExists) {
						nonExistingEntities.add(orgEntity);
					}
				}
			}
			if (!nonExistingEntities.isEmpty()) {
				potentialOwners.removeAll(nonExistingEntities);
			}
		}
	}

	private void doCallbackOperationForPeopleAssignments(
			PeopleAssignments assignments) {
		if (UserGroupCallbackManager.getInstance().existsCallback()) {
			List<OrganizationalEntity> nonExistingEntities = new ArrayList<OrganizationalEntity>();

			if (assignments != null) {
				List<OrganizationalEntity> businessAdmins = assignments
						.getBusinessAdministrators();
				if (businessAdmins != null) {
					for (OrganizationalEntity admin : businessAdmins) {
						if (admin instanceof User) {
							boolean userExists = doCallbackUserOperation(admin
									.getId());
							if (!userExists) {
								nonExistingEntities.add(admin);
							}
						}
						if (admin instanceof Group) {
							boolean groupExists = doCallbackGroupOperation(admin
									.getId());
							if (!groupExists) {
								nonExistingEntities.add(admin);
							}
						}
					}

					if (!nonExistingEntities.isEmpty()) {
						businessAdmins.removeAll(nonExistingEntities);
						nonExistingEntities.clear();
					}
				}

				if (businessAdmins == null || businessAdmins.isEmpty()) {
					// throw an exception as it should not be allowed to create
					// task without administrator
					throw new CannotAddTaskException(
							"There are no known Business Administrators, task cannot be created according to WS-HT specification");
				}

				List<OrganizationalEntity> potentialOwners = assignments
						.getPotentialOwners();
				if (potentialOwners != null) {
					for (OrganizationalEntity powner : potentialOwners) {
						if (powner instanceof User) {
							boolean userExists = doCallbackUserOperation(powner
									.getId());
							if (!userExists) {
								nonExistingEntities.add(powner);
							}
						}
						if (powner instanceof Group) {
							boolean groupExists = doCallbackGroupOperation(powner
									.getId());
							if (!groupExists) {
								nonExistingEntities.add(powner);
							}
						}
					}
					if (!nonExistingEntities.isEmpty()) {
						potentialOwners.removeAll(nonExistingEntities);
						nonExistingEntities.clear();
					}
				}

				if (assignments.getTaskInitiator() != null
						&& assignments.getTaskInitiator().getId() != null) {
					doCallbackUserOperation(assignments.getTaskInitiator()
							.getId());
				}

				List<OrganizationalEntity> excludedOwners = assignments
						.getExcludedOwners();
				if (excludedOwners != null) {
					for (OrganizationalEntity exowner : excludedOwners) {
						if (exowner instanceof User) {
							boolean userExists = doCallbackUserOperation(exowner
									.getId());
							if (!userExists) {
								nonExistingEntities.add(exowner);
							}
						}
						if (exowner instanceof Group) {
							boolean groupExists = doCallbackGroupOperation(exowner
									.getId());
							if (!groupExists) {
								nonExistingEntities.add(exowner);
							}
						}
					}
					if (!nonExistingEntities.isEmpty()) {
						excludedOwners.removeAll(nonExistingEntities);
						nonExistingEntities.clear();
					}
				}

				List<OrganizationalEntity> recipients = assignments
						.getRecipients();
				if (recipients != null) {
					for (OrganizationalEntity recipient : recipients) {
						if (recipient instanceof User) {
							boolean userExists = doCallbackUserOperation(recipient
									.getId());
							if (!userExists) {
								nonExistingEntities.add(recipient);
							}
						}
						if (recipient instanceof Group) {
							boolean groupExists = doCallbackGroupOperation(recipient
									.getId());
							if (!groupExists) {
								nonExistingEntities.add(recipient);
							}
						}
					}
					if (!nonExistingEntities.isEmpty()) {
						recipients.removeAll(nonExistingEntities);
						nonExistingEntities.clear();
					}
				}

				List<OrganizationalEntity> stakeholders = assignments
						.getTaskStakeholders();
				if (stakeholders != null) {
					for (OrganizationalEntity stakeholder : stakeholders) {
						if (stakeholder instanceof User) {
							boolean userExists = doCallbackUserOperation(stakeholder
									.getId());
							if (!userExists) {
								nonExistingEntities.add(stakeholder);
							}
						}
						if (stakeholder instanceof Group) {
							boolean groupExists = doCallbackGroupOperation(stakeholder
									.getId());
							if (!groupExists) {
								nonExistingEntities.add(stakeholder);
							}
						}
					}
					if (!nonExistingEntities.isEmpty()) {
						stakeholders.removeAll(nonExistingEntities);
						nonExistingEntities.clear();
					}
				}
			}
		}

	}

	private void doCallbackOperationForComment(Comment comment) {
		if (comment != null) {
			if (comment.getAddedBy() != null) {
				doCallbackUserOperation(comment.getAddedBy().getId());
			}
		}
	}

	private void doCallbackOperationForAttachment(Attachment attachment) {
		if (attachment != null) {
			if (attachment.getAttachedBy() != null) {
				doCallbackUserOperation(attachment.getAttachedBy().getId());
			}
		}
	}


	private void doCallbackGroupsOperation(String userId, List<String> groupIds) {
		if (UserGroupCallbackManager.getInstance().existsCallback()) {
			if (userId != null) {
				UserGroupCallback callback = UserGroupCallbackManager
						.getInstance().getCallback();
				if (groupIds != null && groupIds.size() > 0) {

					for (String groupId : groupIds) {
						List<String> userGroups = callback.getGroupsForUser(
								userId, groupIds, null);
						if (callback.existsGroup(groupId) && userGroups != null
								&& userGroups.contains(groupId)) {
							addGroupFromCallbackOperation(groupId);
						}
					}
				} else {
					if (!(userGroupsMap.containsKey(userId) && userGroupsMap
							.get(userId).booleanValue())) {
						List<String> userGroups = callback.getGroupsForUser(
								userId, null, null);
						if (userGroups != null && userGroups.size() > 0) {
							for (String group : userGroups) {
								addGroupFromCallbackOperation(group);
							}
							userGroupsMap.put(userId, true);
						}
					}
				}
			} else {
				if (groupIds != null) {
					for (String groupId : groupIds) {
						addGroupFromCallbackOperation(groupId);
					}
				}
			}
		} else {
			logger.debug("UserGroupCallback has not been registered.");
		}
	}

	private void addGroupFromCallbackOperation(String groupId) {
		try {
			boolean groupExists = tpm.findEntity(Group.class, groupId) != null;
			if (!StringUtils.isEmpty(groupId) && !groupExists) {
				Group group = new Group(groupId);
				persistInTransaction(group);
			}
		} catch (Throwable t) {
			logger.debug("Trying to add group " + groupId
					+ ", but it already exists. ");
		}
	}

	private void addUserFromCallbackOperation(String userId) {
		try {
			boolean userExists = tpm.findEntity(User.class, userId) != null;
			if (!StringUtils.isEmpty(userId) && !userExists) {
				User user = new User(userId);
				persistInTransaction(user);
			}
		} catch (Throwable t) {
			logger.debug("Unable to add user " + userId);
		}
	}


}

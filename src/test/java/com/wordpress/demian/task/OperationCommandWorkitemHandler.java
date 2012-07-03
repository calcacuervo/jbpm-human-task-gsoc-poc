package com.wordpress.demian.task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.drools.runtime.process.WorkItem;
import org.drools.runtime.process.WorkItemHandler;
import org.drools.runtime.process.WorkItemManager;
import org.jbpm.task.Status;
import org.jbpm.task.Task;
import org.jbpm.task.service.Allowed;
import org.jbpm.task.service.ContentData;
import org.jbpm.task.service.Operation;
import org.jbpm.task.service.OperationCommand;

public class OperationCommandWorkitemHandler implements WorkItemHandler {
	private TaskServiceUtil session;

	public OperationCommandWorkitemHandler(TaskServiceUtil session) {
		this.session = session;
	}

	@Override
	public void executeWorkItem(WorkItem wi, WorkItemManager wim) {
		List<String> statusString = Arrays.asList(((String) wi
				.getParameter("status")).split(","));
		List<Status> status = new ArrayList<Status>();
		for (String stat : statusString) {
			status.add(Status.valueOf(stat));
		}
		List<String> previousStatusString = Arrays.asList(((String) wi
				.getParameter("previousStatus")).split(","));
		List<Status> previousStatus = new ArrayList<Status>();
		for (String stat : previousStatusString) {
			previousStatus.add(Status.valueOf(stat));
		}

		List<String> allowedString = Arrays.asList(((String) wi
				.getParameter("allowed")).split(","));
		List<Allowed> allowed = new ArrayList<Allowed>();
		for (String stat : allowedString) {
			allowed.add(Allowed.valueOf(stat));
		}
		Status newStatus = Status
				.valueOf((String) wi.getParameter("newStatus"));
		boolean setNewOwnerToUser = ((String) wi
				.getParameter("setNewOwnerToUser")).equals("1") ? true : false;
		boolean setNewOwnerToNull = ((String) wi
				.getParameter("setNewOwnerToNull")).equals("1") ? true : false;
		boolean setToPreviousStatus = ((String) wi
				.getParameter("setToPreviousStatus")).equals("1") ? true
				: false;
		boolean userIsExplicitPotentialOwner = ((String) wi
				.getParameter("userIsExplicitPotentialOwner")).equals("1") ? true
				: false;
		boolean addTargetUserToPotentialOwners = ((String) wi
				.getParameter("addTargetUserToPotentialOwners")).equals("1") ? true
				: false;
		boolean removeUserFromPotentialOwners = ((String) wi
				.getParameter("removeUserFromPotentialOwners")).equals("1") ? true
				: false;
		boolean skippable = ((String) wi.getParameter("skippable")).equals("1") ? true
				: false;
		Operation exec = Operation.valueOf((String) wi.getParameter("exec"));
		Task task = (Task) wi.getParameter("task");
		UserTaskEvent taskEvent = (UserTaskEvent) wi.getParameter("taskEvent");
		OperationCommand command = new OperationCommand();
		command.setAddTargetUserToPotentialOwners(addTargetUserToPotentialOwners);
		command.setAllowed(allowed);
		command.setExec(exec);
		command.setNewStatus(newStatus);
		command.setPreviousStatus(previousStatus);
		command.setRemoveUserFromPotentialOwners(removeUserFromPotentialOwners);
		command.setSetNewOwnerToNull(setNewOwnerToNull);
		command.setSetNewOwnerToUser(setNewOwnerToUser);
		command.setSetToPreviousStatus(setToPreviousStatus);
		command.setSkippable(skippable);
		command.setStatus(status);
		command.setUserIsExplicitPotentialOwner(userIsExplicitPotentialOwner);
		List<OperationCommand> commands = new ArrayList<OperationCommand>();
		commands.add(command);
		this.session.taskOperation(commands, command.getExec(), task.getId(),
				taskEvent.getUserId(), taskEvent.getUserId(), taskEvent.getData(), null);
		wim.completeWorkItem(wi.getId(), null);
	}

	@Override
	public void abortWorkItem(WorkItem arg0, WorkItemManager arg1) {
		// TODO Auto-generated method stub

	}

}

package com.wordpress.demian;

import java.io.StringReader;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import junit.framework.Assert;

import org.drools.SystemEventListenerFactory;
import org.drools.runtime.StatefulKnowledgeSession;
import org.jbpm.task.Status;
import org.jbpm.task.Task;
import org.jbpm.task.service.TaskService;
import org.junit.Test;

import com.wordpress.demian.task.OperationCommandWorkitemHandler;
import com.wordpress.demian.task.TaskServiceUtil;
import com.wordpress.demian.task.UserTaskEvent;

public class LifeCycleStatefulTest extends BaseTest {

	/**
	 * This test will show how an approach with different tasks in different
	 * ksessions will work.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testTaskLifeCycle() throws Exception {
		// First we will create some users and groups
		Map vars = new HashMap();
		users = fillUsersOrGroups("src/main/resources/LoadUsers.mvel");
		groups = fillUsersOrGroups("src/main/resources/LoadGroups.mvel");
		vars.put("users", users);
		vars.put("groups", groups);
		vars.put("now", new Date());

		// we create a samples task definition
		String str = "(with (new Task()) { priority = 55, taskData = (with( new TaskData()) { status = Status.Ready } ), ";
		str += "peopleAssignments = (with ( new PeopleAssignments() ) { potentialOwners = [users['bobba' ], users['darth'] ], businessAdministrators = [users['Administrator' ], users['darth'] ]}),";
		str += "names = [ new I18NText( 'en-UK', 'This is my task name')] })";

		// let's create two different tasks, they will have the same structure.
		Task task1 = (Task) eval(new StringReader(str), vars);
		Task task2 = (Task) eval(new StringReader(str), vars);
		EntityManagerFactory emfTask = Persistence
				.createEntityManagerFactory("org.jbpm.task");
		TaskService taskService = new TaskService(emfTask,
				SystemEventListenerFactory.getSystemEventListener());
		tsession = taskService.createSession();
		for (String user : users.keySet()) {
			tsession.addUser(users.get(user));
		}

		// add the tasks to the task session.
		tsession.addTask(task1, null);
		tsession.addTask(task2, null);
		TaskServiceUtil util = new TaskServiceUtil(taskService,
				tsession.getTaskPersistenceManager());
		OperationCommandWorkitemHandler handler = new OperationCommandWorkitemHandler(
				util);

		// now create two differents sessions for each task. When putting this
		// to the real jbpm code, we will have to have the association taskId -
		// session id saved in persistence, and load the appropiate session.
		StatefulKnowledgeSession sessionTask1 = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		sessionTask1.getWorkItemManager().registerWorkItemHandler(
				"OperationCommand", handler);
		StatefulKnowledgeSession sessionTask2 = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		sessionTask2.getWorkItemManager().registerWorkItemHandler(
				"OperationCommand", handler);

		// now we will start the two processes. They will start waiting for an
		// event.
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("task", task1);
		sessionTask1.startProcess("taskProcess", params);
		params.put("task", task2);
		sessionTask2.startProcess("taskProcess", params);

		// now, the first task was claimed. The Task should be in Reserved
		// status now.
		UserTaskEvent te = new UserTaskEvent();
		te.setUserId("Darth Vader");
		te.setData(null);
		sessionTask1.signalEvent("ClaimTaskEvent", te);

		Assert.assertTrue(tsession.getTask(task1.getId()).getTaskData()
				.getStatus().equals(Status.Reserved));

		// Now the user starts this task.
		sessionTask1.signalEvent("StartClaimedTaskEvent", te);

		Assert.assertTrue(tsession.getTask(task1.getId()).getTaskData()
				.getStatus().equals(Status.InProgress));
		Assert.assertTrue(tsession.getTask(task2.getId()).getTaskData()
				.getStatus().equals(Status.Ready));

		// now interact with task 2 in a different session
		sessionTask2.signalEvent("ClaimTaskEvent", te);
		Assert.assertTrue(tsession.getTask(task2.getId()).getTaskData()
				.getStatus().equals(Status.Reserved));
		Assert.assertTrue(tsession.getTask(task1.getId()).getTaskData()
				.getStatus().equals(Status.InProgress));

		sessionTask2.signalEvent("StartClaimedTaskEvent", te);
		Assert.assertTrue(tsession.getTask(task2.getId()).getTaskData()
				.getStatus().equals(Status.InProgress));
		Assert.assertTrue(tsession.getTask(task1.getId()).getTaskData()
				.getStatus().equals(Status.InProgress));
	}

	/**
	 * This test will show how an approach with different tasks in different
	 * ksessions will work.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testTaskLifeCycle_UserNotAllowed() throws Exception {
		// First we will create some users and groups
		Map vars = new HashMap();
		users = fillUsersOrGroups("src/main/resources/LoadUsers.mvel");
		groups = fillUsersOrGroups("src/main/resources/LoadGroups.mvel");
		vars.put("users", users);
		vars.put("groups", groups);
		vars.put("now", new Date());

		// we create a samples task definition
		String str = "(with (new Task()) { priority = 55, taskData = (with( new TaskData()) { status = Status.Ready } ), ";
		str += "peopleAssignments = (with ( new PeopleAssignments() ) { potentialOwners = [users['bobba' ], users['darth'] ], businessAdministrators = [users['Administrator' ], users['darth'] ]}),";
		str += "names = [ new I18NText( 'en-UK', 'This is my task name')] })";

		// let's create two different tasks, they will have the same structure.
		Task task1 = (Task) eval(new StringReader(str), vars);
		Task task2 = (Task) eval(new StringReader(str), vars);
		EntityManagerFactory emfTask = Persistence
				.createEntityManagerFactory("org.jbpm.task");
		TaskService taskService = new TaskService(emfTask,
				SystemEventListenerFactory.getSystemEventListener());
		tsession = taskService.createSession();
		for (String user : users.keySet()) {
			tsession.addUser(users.get(user));
		}

		// add the tasks to the task session.
		tsession.addTask(task1, null);
		tsession.addTask(task2, null);
		TaskServiceUtil util = new TaskServiceUtil(taskService,
				tsession.getTaskPersistenceManager());
		OperationCommandWorkitemHandler handler = new OperationCommandWorkitemHandler(
				util);

		// now create two differents sessions for each task. When putting this
		// to the real jbpm code, we will have to have the association taskId -
		// session id saved in persistence, and load the appropiate session.
		StatefulKnowledgeSession sessionTask1 = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		sessionTask1.getWorkItemManager().registerWorkItemHandler(
				"OperationCommand", handler);
		StatefulKnowledgeSession sessionTask2 = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		sessionTask2.getWorkItemManager().registerWorkItemHandler(
				"OperationCommand", handler);

		// now we will start the two processes. They will start waiting for an
		// event.
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("task", task1);
		sessionTask1.startProcess("taskProcess", params);
		params.put("task", task2);
		sessionTask2.startProcess("taskProcess", params);

		// now, a user that is not a potential owner will try to claim the task.
		UserTaskEvent te = new UserTaskEvent();
		te.setUserId("salaboy");
		te.setData(null);
		try {
			sessionTask1.signalEvent("ClaimTaskEvent", te);
		} catch (Exception e) {
			// we check that a permision denied exception was thrown.|
			Assert.assertTrue(e.getCause().getCause() instanceof org.jbpm.task.service.PermissionDeniedException);
		}
	}

}

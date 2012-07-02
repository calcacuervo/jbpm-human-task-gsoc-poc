package com.wordpress.demian.persisted;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import junit.framework.Assert;

import org.drools.KnowledgeBase;
import org.drools.KnowledgeBaseFactory;
import org.drools.SystemEventListenerFactory;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderError;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.io.impl.ClassPathResource;
import org.drools.runtime.StatefulKnowledgeSession;
import org.jbpm.task.Group;
import org.jbpm.task.Status;
import org.jbpm.task.Task;
import org.jbpm.task.User;
import org.jbpm.task.identity.UserGroupCallback;
import org.jbpm.task.identity.UserGroupCallbackManager;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.TaskServiceSession;
import org.junit.Test;

import com.wordpress.demian.task.OperationCommandWorkitemHandler;
import com.wordpress.demian.task.OperationCommandWorkitemHandler.UserTaskEvent;
import com.wordpress.demian.task.TaskServiceUtil;

public class LifeCycleNonPersistedTest {

	protected Map<String, User> users;
	protected Map<String, Group> groups;

	@Test
	public void testStartTask() throws Exception {

		StatefulKnowledgeSession session = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		Map vars = new HashMap();
		users = fillUsersOrGroups("src/main/resources/LoadUsers.mvel");
		groups = fillUsersOrGroups("src/main/resources/LoadGroups.mvel");
		vars.put("users", users);
		vars.put("groups", groups);
		vars.put("now", new Date());

		// One potential owner, should go straight to state Reserved
		String str = "(with (new Task()) { priority = 55, taskData = (with( new TaskData()) { status = Status.Ready } ), ";
		str += "peopleAssignments = (with ( new PeopleAssignments() ) { potentialOwners = [users['bobba' ], users['darth'] ], businessAdministrators = [users['Administrator' ], users['darth'] ]}),";
		str += "names = [ new I18NText( 'en-UK', 'This is my task name')] })";

		Task task = (Task) eval(new StringReader(str), vars);
//		task.getPeopleAssignments().set
		EntityManagerFactory emfTask = Persistence.createEntityManagerFactory("org.jbpm.task");
		TaskService taskService = new TaskService(emfTask,
				SystemEventListenerFactory.getSystemEventListener());
		TaskServiceSession tsession = taskService.createSession();
		UserGroupCallbackManager.getInstance().setCallback(new UserGroupCallback() {
			
			@Override
			public List<String> getGroupsForUser(String userId, List<String> groupIds,
					List<String> allExistingGroupIds) {
				return null;
			}
			
			@Override
			public boolean existsUser(String userId) {
				return true;
			}
			
			@Override
			public boolean existsGroup(String groupId) {
				return true;
			}
		});
		tsession.addTask(task, null);
		TaskServiceUtil util = new TaskServiceUtil(taskService,  tsession.getTaskPersistenceManager());
		OperationCommandWorkitemHandler handler = new OperationCommandWorkitemHandler(util);
		session.getWorkItemManager().registerWorkItemHandler("OperationCommand", handler);
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("task", task);
		session.startProcess("taskProcess", params);
		
		UserTaskEvent te = new UserTaskEvent();
		te.setUserId("Darth Vader");
		te.setData(null);
		session.signalEvent("ClaimTaskEvent", te);
		
		Assert.assertTrue(tsession.getTask(task.getId()).getTaskData().getStatus().equals(Status.Reserved));
		
		params = new HashMap<String, Object>();
		params.put("task", task);
		session.startProcess("taskProcess", params);
		session.signalEvent("StartTaskEvent", te);
		
		Assert.assertTrue(tsession.getTask(task.getId()).getTaskData().getStatus().equals(Status.InProgress));
	}

	public static Object eval(Reader reader, Map vars) {
		vars.put("now", new Date());
		return TaskService.eval(reader, vars);
	}

	public static Map fillUsersOrGroups(String mvelFileName) throws Exception {
		Map<String, Object> vars = new HashMap<String, Object>();
		Reader reader = null;
		Map<String, Object> result = null;

		try {
			InputStream is = new FileInputStream(mvelFileName);
			reader = new InputStreamReader(is);
			result = (Map<String, Object>) eval(reader, vars);
		} finally {
			if (reader != null)
				reader.close();
		}

		return result;
	}

	private KnowledgeBase createKnowledgeBase() {
		KnowledgeBuilder kbuilder = KnowledgeBuilderFactory
				.newKnowledgeBuilder();

		kbuilder.add(new ClassPathResource("human-task-nopersistence.bpmn"),
				ResourceType.BPMN2);

		KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
		kbase.addKnowledgePackages(kbuilder.getKnowledgePackages());
		if (kbuilder.hasErrors()) {
			StringBuilder errorMessage = new StringBuilder();
			for (KnowledgeBuilderError error : kbuilder.getErrors()) {
				errorMessage.append(error.getMessage());
				errorMessage.append(System.getProperty("line.separator"));
			}
			System.out.println(errorMessage);
		}
		return kbase;
	}
}

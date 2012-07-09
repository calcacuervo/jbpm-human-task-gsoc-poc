package com.wordpress.demian;

import java.io.StringReader;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import junit.framework.Assert;

import org.drools.SystemEventListenerFactory;
import org.drools.runtime.StatefulKnowledgeSession;
import org.jbpm.task.Status;
import org.jbpm.task.Task;
import org.jbpm.task.identity.UserGroupCallback;
import org.jbpm.task.identity.UserGroupCallbackManager;
import org.jbpm.task.service.TaskService;
import org.junit.Test;

import com.wordpress.demian.task.OperationCommandWorkitemHandler;
import com.wordpress.demian.task.TaskServiceUtil;
import com.wordpress.demian.task.UserTaskEvent;

public class LifeCycleStatelessTest extends BaseTest {

	@Test
	public void testStartTask() throws Exception {
		Map vars = new HashMap();
		users = fillUsersOrGroups("src/main/resources/LoadUsers.mvel");
		groups = fillUsersOrGroups("src/main/resources/LoadGroups.mvel");
		vars.put("users", users);
		vars.put("groups", groups);
		vars.put("now", new Date());

		String str = "(with (new Task()) { priority = 55, taskData = (with( new TaskData()) { status = Status.Ready } ), ";
		str += "peopleAssignments = (with ( new PeopleAssignments() ) { potentialOwners = [users['bobba' ], users['darth'] ], businessAdministrators = [users['Administrator' ], users['darth'] ]}),";
		str += "names = [ new I18NText( 'en-UK', 'This is my task name')] })";

		task = (Task) eval(new StringReader(str), vars);
		EntityManagerFactory emfTask = Persistence.createEntityManagerFactory("org.jbpm.task");
		TaskService taskService = new TaskService(emfTask,
				SystemEventListenerFactory.getSystemEventListener());
		tsession = taskService.createSession();
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
		StatefulKnowledgeSession session = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		session.getWorkItemManager().registerWorkItemHandler("OperationCommand", handler);
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("task", task);
		session.startProcess("taskProcessNoPersistence", params);
		
		UserTaskEvent te = new UserTaskEvent();
		te.setUserId("Darth Vader");
		te.setData(null);
		session.signalEvent("ClaimTaskEvent", te);
		
		Assert.assertTrue(tsession.getTask(task.getId()).getTaskData().getStatus().equals(Status.Reserved));
		
		params = new HashMap<String, Object>();
		params.put("task", task);
		session.startProcess("taskProcessNoPersistence", params);
		session.signalEvent("StartTaskEvent", te);
		
		Assert.assertTrue(tsession.getTask(task.getId()).getTaskData().getStatus().equals(Status.InProgress));
	}


}

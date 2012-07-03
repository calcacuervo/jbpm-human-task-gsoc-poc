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
import org.jbpm.task.Task;
import org.jbpm.task.User;
import org.jbpm.task.identity.UserGroupCallback;
import org.jbpm.task.identity.UserGroupCallbackManager;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.TaskServiceSession;
import org.junit.Before;

import com.wordpress.demian.task.OperationCommandWorkitemHandler;
import com.wordpress.demian.task.TaskServiceUtil;

public class BaseTest {

	protected StatefulKnowledgeSession session;
	
	protected Task task;
	
	protected TaskServiceSession tsession;
	
	protected Map<String, User> users;
	
	protected Map<String, Group> groups;

	@Before
	public void setUp() throws Exception {
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
		session = this.createKnowledgeBase()
				.newStatefulKnowledgeSession();
		session.getWorkItemManager().registerWorkItemHandler("OperationCommand", handler);
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
		kbuilder.add(new ClassPathResource("human-task.bpmn"),
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

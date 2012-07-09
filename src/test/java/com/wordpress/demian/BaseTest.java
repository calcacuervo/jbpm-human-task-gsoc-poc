package com.wordpress.demian;

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
	
	protected Task task;
	
	protected TaskServiceSession tsession;
	
	protected Map<String, User> users;
	
	protected Map<String, Group> groups;
	
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

	protected KnowledgeBase createKnowledgeBase() {
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

package com.wordpress.demian.persisted;

import java.util.HashMap;
import java.util.Map;

import junit.framework.Assert;

import org.jbpm.task.Status;
import org.junit.Test;

import com.wordpress.demian.task.UserTaskEvent;

public class LifeCycleStatelessTest extends BaseTest {

	@Test
	public void testStartTask() throws Exception {

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

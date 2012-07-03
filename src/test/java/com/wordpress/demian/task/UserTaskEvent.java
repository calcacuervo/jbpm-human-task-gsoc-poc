package com.wordpress.demian.task;

import org.jbpm.task.service.ContentData;

public class UserTaskEvent {
	private String userId;
	private ContentData data;

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public ContentData getData() {
		return data;
	}

	public void setData(ContentData data) {
		this.data = data;
	}
}

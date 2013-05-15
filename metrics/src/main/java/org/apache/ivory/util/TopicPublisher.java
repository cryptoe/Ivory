package org.apache.ivory.util;

import java.lang.reflect.InvocationTargetException;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.apache.ivory.aspect.ResourceMessage;
import org.apache.log4j.Logger;

public class TopicPublisher {

	private static final Logger LOG = Logger.getLogger(TopicPublisher.class);
	private String userName;
	private String password;
	private String url;
	private String topicName;
	private String implementation;
	private Connection connection;
	private Session session;
	private MessageProducer producer;
	private Long timeToLive;

	public TopicPublisher(String implementaion, String userName,
			String password, String url, String topicName,Long timeToLive) {
		this.implementation = implementaion;
		this.userName = userName;
		this.password = password;
		this.url = url;
		this.topicName = topicName;
		this.timeToLive=timeToLive;
	}

	@SuppressWarnings("unchecked")
	public boolean startPublisher() throws ClassNotFoundException,
			IllegalArgumentException, SecurityException,
			InstantiationException, IllegalAccessException,
			InvocationTargetException, NoSuchMethodException, JMSException {

		Class<ConnectionFactory> clazz;
		clazz = (Class<ConnectionFactory>) org.apache.ivory.util.TopicPublisher.class
				.getClassLoader().loadClass(this.implementation);
		ConnectionFactory connectionFactory = clazz.getConstructor(
				String.class, String.class, String.class).newInstance(userName,
				password, url);
		try {
			connection = connectionFactory.createConnection();
			connection.start();
			session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			this.producer = session.createProducer(session
					.createTopic(this.topicName));
			this.producer.setDeliveryMode(DeliveryMode.PERSISTENT);
			this.producer. setTimeToLive(this.timeToLive);
			return true;
		} catch (JMSException e) {
			LOG.error("Error starting Publisher of topic: " + this.topicName, e);
			stopPublisher();
			return false;
		}
	}

	public boolean stopPublisher() {

		try {
			if (connection != null) {
				connection.close();
				LOG.info("Closing publisher for topic: " + this.topicName);
			} else
				LOG.debug("No connection to stop of topic: " + this.topicName);
			return true;
		} catch (JMSException e) {
			LOG.error("Error stoping  Publisher of topic: " + this.topicName, e);
			return false;
		}
	}

	public boolean publishMessage(ResourceMessage resourceMessage) {
		try {
			MapMessage mapMessage = session.createMapMessage();
			mapMessage.setString("action", resourceMessage.getAction());
			mapMessage.setLong("execution_time",
					resourceMessage.getExecutionTime());
			mapMessage.setInt("status", resourceMessage.getStatus().ordinal());
			for (String key : resourceMessage.getDimensions().keySet()) {
				mapMessage.setStringProperty(key, resourceMessage
						.getDimensions().get(key));
			}
			producer.send(mapMessage);
			return true;
		} catch (JMSException e) {
			LOG.error("Error sending message on topic: " + this.topicName, e);
			return false;
		}
	}

}

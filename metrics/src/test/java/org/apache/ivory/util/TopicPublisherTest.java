package org.apache.ivory.util;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.ivory.aspect.ResourceMessage;
import org.apache.ivory.aspect.ResourceMessage.Status;
import org.apache.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

public class TopicPublisherTest {
	private static final Logger LOG = Logger
			.getLogger(TopicPublisherTest.class);
	private static final String BROKER_URL = "tcp://localhost:61616";
	private static final String BROKER_IMPL_CLASS = "org.apache.activemq.ActiveMQConnectionFactory";
	private static final String TOPIC_NAME = "IVORY.POSTER.TOPIC";
	private static long TTL = 86400000;
	private static ResourceMessage resourceMessage;

	private static BrokerService broker;
	private static TopicPublisher publisher;
	private volatile AssertionError error;

	@BeforeTest
	public void setup() throws Exception {
		broker = new BrokerService();
		broker.setUseJmx(true);
		broker.addConnector(BROKER_URL);
		broker.start();
	}

	@Test
	public void testPublisher() throws Exception {
		Thread t = new Thread() {
			@Override
			public void run() {
				try {
					recieveMessage();
				} catch (AssertionError e) {
					error = e;
					throw e;
				} catch (JMSException ignore) {
				}

			}
		};
		t.start();
		Thread.sleep(1500);

		Map<String, String> map = new HashMap<String, String>();
		map.put("key1", "val1");
		map.put("key2", "val2");
		resourceMessage = new ResourceMessage("test", map, Status.SUCCEEDED,
				123);
		publisher = new TopicPublisher(BROKER_IMPL_CLASS, "", "", BROKER_URL,
				TOPIC_NAME, TTL);
		Assert.assertTrue(publisher.startPublisher());
		Assert.assertTrue(publisher.publishMessage(resourceMessage));
		LOG.info("Published Message :" + resourceMessage.toString());
		t.join();
		if (error != null) {
			throw error;
		}
	}

	public static void recieveMessage() throws JMSException, AssertionError {

		ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
				BROKER_URL);
		Connection connection = connectionFactory.createConnection();
		connection.start();
		Session session = connection.createSession(false,
				Session.AUTO_ACKNOWLEDGE);
		Destination destination = session.createTopic(TOPIC_NAME);
		MessageConsumer messageConsumer = session.createConsumer(destination);
		Message msg = messageConsumer.receive();
		if (msg instanceof MapMessage) {
			{
				Assert.assertEquals(resourceMessage.getAction(),
						(((MapMessage) msg).getString("action")));
				Assert.assertEquals(resourceMessage.getStatus().ordinal(),
						(((MapMessage) msg).getInt("status")));
				Assert.assertEquals(resourceMessage.getExecutionTime(),
						((MapMessage) msg).getLong("execution_time"));
				@SuppressWarnings("unchecked")
				Enumeration<String> ev = ((MapMessage) msg).getPropertyNames();
				Map<String, String> mapToBeTested = new HashMap<String, String>();
				while (ev.hasMoreElements()) {
					String key = ev.nextElement();
					mapToBeTested.put(key,
							((MapMessage) msg).getStringProperty(key));
				}
				Assert.assertEquals(mapToBeTested,
						resourceMessage.getDimensions());
			}
		} else
			throw new AssertionError("Map message not recived ");
	}

	@AfterClass
	public void stop() {
		try {
			Assert.assertTrue(publisher.stopPublisher());
			broker.stop();
		} catch (Exception e) {
			LOG.debug("Error stoping broker", e);
		}
	}
}

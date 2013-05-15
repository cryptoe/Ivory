package org.apache.ivory.plugin;

import java.lang.reflect.InvocationTargetException;

import javax.jms.JMSException;

import org.apache.ivory.aspect.ResourceMessage;
import org.apache.ivory.util.StartupProperties;
import org.apache.ivory.util.TopicPublisher;

public class PosterPlugin implements MonitoringPlugin {

	private final TopicPublisher topicPublisher;

	private static enum JMSprops {
		IvoryBrokerImplClass("broker.impl.class",
				"org.apache.activemq.ActiveMQConnectionFactory"), IvoryBrokerUrl(
				"broker.url", "tcp://localhost:61616?daemon=true"), IvoryPosterTopic(
				"poster.topic", "IVORY.POSTER.TOPIC"), IvoryPosterTimeToLive(
				"poster.ttl", "86400000"); // Setting default value of
											// poster.ttl as 1 day

		private String propName;
		private String defaultPropValue;

		private JMSprops(String propName, String defaultPropValue) {
			this.propName = propName;
			this.defaultPropValue = defaultPropValue;
		}

	}

	public PosterPlugin() throws IllegalArgumentException, SecurityException,
			ClassNotFoundException, InstantiationException,
			IllegalAccessException, InvocationTargetException,
			NoSuchMethodException, JMSException {

		topicPublisher = new TopicPublisher(
				getPropertyValue(JMSprops.IvoryBrokerImplClass),
				"",
				"",
				getPropertyValue(JMSprops.IvoryBrokerUrl),
				getPropertyValue(JMSprops.IvoryPosterTopic),
				Long.parseLong(getPropertyValue(JMSprops.IvoryPosterTimeToLive)));
		topicPublisher.startPublisher();

	}

	@Override
	public void monitor(ResourceMessage message) {
		topicPublisher.publishMessage(message);

	}

	private String getPropertyValue(JMSprops prop) {

		return StartupProperties.get().getProperty(prop.propName,
				prop.defaultPropValue);

	}

	public void stop() {
		topicPublisher.stopPublisher();
	}

}

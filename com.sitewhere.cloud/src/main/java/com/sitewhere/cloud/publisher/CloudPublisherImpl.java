/*******************************************************************************
 * Copyright (c) 2018 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package com.sitewhere.cloud.publisher;

import static java.util.Objects.nonNull;
import static org.eclipse.kura.core.message.MessageConstants.APP_ID;
import static org.eclipse.kura.core.message.MessageConstants.APP_TOPIC;
import static org.eclipse.kura.core.message.MessageConstants.PRIORITY;
import static org.eclipse.kura.core.message.MessageConstants.QOS;
import static org.eclipse.kura.core.message.MessageConstants.RETAIN;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.cloudconnection.CloudConnectionManager;
import org.eclipse.kura.cloudconnection.listener.CloudConnectionListener;
import org.eclipse.kura.cloudconnection.listener.CloudDeliveryListener;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.cloudconnection.publisher.CloudPublisher;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.message.KuraPayload;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sitewhere.cloud.CloudPublisherDeliveryListener;
import com.sitewhere.cloud.CloudServiceOptions;
import com.sitewhere.cloud.SiteWhereCloudServiceImpl;
import com.sitewhere.cloud.payload.SendMeasurementPayload;

public class CloudPublisherImpl
	implements CloudPublisher, ConfigurableComponent, CloudConnectionListener, CloudPublisherDeliveryListener {

    private final class CloudConnectionManagerTrackerCustomizer
	    implements ServiceTrackerCustomizer<CloudConnectionManager, CloudConnectionManager> {

	@Override
	public CloudConnectionManager addingService(final ServiceReference<CloudConnectionManager> reference) {
	    CloudConnectionManager tempCloudService = CloudPublisherImpl.this.bundleContext.getService(reference);

	    if (tempCloudService instanceof SiteWhereCloudServiceImpl) {
		CloudPublisherImpl.this.cloudServiceImpl = (SiteWhereCloudServiceImpl) tempCloudService;
		CloudPublisherImpl.this.cloudServiceImpl.registerCloudConnectionListener(CloudPublisherImpl.this);
		CloudPublisherImpl.this.cloudServiceImpl
			.registerCloudPublisherDeliveryListener(CloudPublisherImpl.this);
		return tempCloudService;
	    } else {
		CloudPublisherImpl.this.bundleContext.ungetService(reference);
	    }

	    return null;
	}

	@Override
	public void removedService(final ServiceReference<CloudConnectionManager> reference,
		final CloudConnectionManager service) {
	    CloudPublisherImpl.this.cloudServiceImpl.unregisterCloudConnectionListener(CloudPublisherImpl.this);
	    CloudPublisherImpl.this.cloudServiceImpl.unregisterCloudPublisherDeliveryListener(CloudPublisherImpl.this);
	    CloudPublisherImpl.this.cloudServiceImpl = null;
	}

	@Override
	public void modifiedService(ServiceReference<CloudConnectionManager> reference,
		CloudConnectionManager service) {
	    // Not needed
	}
    }

    private static final Logger logger = LoggerFactory.getLogger(CloudPublisherImpl.class);

    private final Set<CloudConnectionListener> cloudConnectionListeners = new CopyOnWriteArraySet<>();
    private final Set<CloudDeliveryListener> cloudDeliveryListeners = new CopyOnWriteArraySet<>();

    private ServiceTrackerCustomizer<CloudConnectionManager, CloudConnectionManager> cloudConnectionManagerTrackerCustomizer;
    private ServiceTracker<CloudConnectionManager, CloudConnectionManager> cloudConnectionManagerTracker;

    private CloudPublisherOptions cloudPublisherOptions;
    private SiteWhereCloudServiceImpl cloudServiceImpl;
    private BundleContext bundleContext;

    private final ExecutorService worker = Executors.newCachedThreadPool();

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
	logger.debug("Activating Cloud Publisher...");
	this.bundleContext = componentContext.getBundleContext();

	this.cloudPublisherOptions = new CloudPublisherOptions(properties);

	this.cloudConnectionManagerTrackerCustomizer = new CloudConnectionManagerTrackerCustomizer();
	initCloudConnectionManagerTracking();

	logger.debug("Activating Cloud Publisher... Done");
    }

    public void updated(Map<String, Object> properties) {
	logger.debug("Updating Cloud Publisher...");

	this.cloudPublisherOptions = new CloudPublisherOptions(properties);

	if (nonNull(this.cloudConnectionManagerTracker)) {
	    this.cloudConnectionManagerTracker.close();
	}
	initCloudConnectionManagerTracking();

	logger.debug("Updating Cloud Publisher... Done");
    }

    protected void deactivate(ComponentContext componentContext) {
	logger.debug("Deactivating Cloud Publisher...");

	if (nonNull(this.cloudConnectionManagerTracker)) {
	    this.cloudConnectionManagerTracker.close();
	}

	this.worker.shutdown();
	logger.debug("Deactivating Cloud Publisher... Done");
    }

    @Override
    public String publish(KuraMessage message) throws KuraException {
	if (this.cloudServiceImpl == null) {
	    logger.info("Null cloud service");
	    throw new KuraException(KuraErrorCode.SERVICE_UNAVAILABLE);
	}

	if (message == null) {
	    logger.warn("Received null message!");
	    throw new IllegalArgumentException();
	}

	CloudServiceOptions cso = this.cloudServiceImpl.getCloudServiceOptions();
	String deviceName = cso.getDeviceDisplayName();
	if (deviceName == null) {
	    deviceName = this.cloudServiceImpl.getSystemService().getDeviceName();
	}
	String appTopic = cso.getSiteWhereTopic();

	int qos = this.cloudPublisherOptions.getQos();
	boolean retain = this.cloudPublisherOptions.isRetain();
	int priority = this.cloudPublisherOptions.getPriority();

	Map<String, Object> publishMessageProps = new HashMap<>();
	publishMessageProps.put(APP_TOPIC.name(), appTopic);
	publishMessageProps.put(APP_ID.name(), this.cloudPublisherOptions.getAppId());
	publishMessageProps.put(QOS.name(), qos);
	publishMessageProps.put(RETAIN.name(), retain);
	publishMessageProps.put(PRIORITY.name(), priority);

	KuraPayload payload = message.getPayload();

	Set<String> keys = payload.metricNames();
	Date timestamp = payload.getTimestamp();

	for (String key : keys) {
	    Double value;
	    try {
		Object payloadValue = payload.getMetric(key);
		value = measurementValue(payloadValue);
		SendMeasurementPayload.Builder builder = SendMeasurementPayload.newBuilder();
		builder.withDeviceToken(deviceName).withMeasurementId(key).withMeasurementValue(value)
			.withUpdateState(true).withEventDate(timestamp.getTime()).withOriginator("kura");

		KuraMessage publishMessage = new KuraMessage(builder.build(), publishMessageProps);
		this.cloudServiceImpl.publish(publishMessage);
	    } catch (ClassCastException e) {
	    }
	}
	return null;
    }

    private Double measurementValue(Object value) {
	if (value instanceof Number) {
	    return ((Number) value).doubleValue();
	}
	try {
	    return Double.valueOf(String.valueOf(value));
	} catch (NumberFormatException e) {
	}
	return Double.valueOf(0.0);
    }

    private void initCloudConnectionManagerTracking() {
	String selectedCloudServicePid = this.cloudPublisherOptions.getCloudServicePid();
	String filterString = String.format("(&(%s=%s)(kura.service.pid=%s))", Constants.OBJECTCLASS,
		CloudConnectionManager.class.getName(), selectedCloudServicePid);
	Filter filter = null;
	try {
	    filter = this.bundleContext.createFilter(filterString);
	} catch (InvalidSyntaxException e) {
	    logger.error("Filter setup exception ", e);
	}
	this.cloudConnectionManagerTracker = new ServiceTracker<>(this.bundleContext, filter,
		this.cloudConnectionManagerTrackerCustomizer);
	this.cloudConnectionManagerTracker.open();
    }

    @Override
    public void onDisconnected() {
	this.cloudConnectionListeners.forEach(listener -> this.worker.execute(listener::onDisconnected));
    }

    @Override
    public void onConnectionLost() {
	this.cloudConnectionListeners.forEach(listener -> this.worker.execute(listener::onConnectionLost));
    }

    @Override
    public void onConnectionEstablished() {
	this.cloudConnectionListeners.forEach(listener -> this.worker.execute(listener::onConnectionEstablished));
    }

    @Override
    public void registerCloudConnectionListener(CloudConnectionListener cloudConnectionListener) {
	this.cloudConnectionListeners.add(cloudConnectionListener);
    }

    @Override
    public void unregisterCloudConnectionListener(CloudConnectionListener cloudConnectionListener) {
	this.cloudConnectionListeners.remove(cloudConnectionListener);
    }

    @Override
    public void registerCloudDeliveryListener(CloudDeliveryListener cloudDeliveryListener) {
	this.cloudDeliveryListeners.add(cloudDeliveryListener);
    }

    @Override
    public void unregisterCloudDeliveryListener(CloudDeliveryListener cloudDeliveryListener) {
	this.cloudDeliveryListeners.remove(cloudDeliveryListener);
    }

    @Override
    public void onMessageConfirmed(String messageId, String topic) {
	if (topic.contains(this.cloudPublisherOptions.getAppId())) {
	    this.cloudDeliveryListeners
		    .forEach(listener -> this.worker.execute(() -> listener.onMessageConfirmed(messageId)));
	}
    }

}

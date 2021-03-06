/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.cloud.encode.protobuf;

import com.google.protobuf.MessageLite;
import com.sitewhere.cloud.payload.DeviceRegistrationPayload;
import com.sitewhere.communication.protobuf.proto.SiteWhere;
import com.sitewhere.communication.protobuf.proto.SiteWhere.DeviceEvent.Command;

/**
 * Encodes Device Registration Message using Protocol Buffer
 * 
 * @author Jorge Villaverde
 */
public class DeviceRegistrationProtoBufEncoder extends ProtoBufEncoder {

    public DeviceRegistrationProtoBufEncoder(DeviceRegistrationPayload payload) {
	super(payload);
    }

    @Override
    protected Command getCommand() {
	return Command.SendRegistration;
    }

    @Override
    protected MessageLite buildPayload() {	
	SiteWhere.DeviceEvent.DeviceRegistrationRequest.Builder payload =
		SiteWhere.DeviceEvent.DeviceRegistrationRequest.newBuilder();
	
	payload.getAreaTokenBuilder().setValue(getPayload().getAreaToken());
	payload.getCustomerTokenBuilder().setValue(getPayload().getCustomerToken());
	payload.getDeviceTypeTokenBuilder().setValue(getPayload().getDeviceTypeToken());
	payload.putAllMetadata(getPayload().getMetadata());
	
	return payload.build();
    }

    @Override
    protected DeviceRegistrationPayload getPayload() {
	return (DeviceRegistrationPayload)super.getPayload();
    }

}

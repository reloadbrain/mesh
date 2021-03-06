package com.gentics.mesh.core.endpoint;

import com.gentics.mesh.context.InternalActionContext;

import io.vertx.ext.web.RoutingContext;

public class PathParameters {

	public static String TAG_FAMILY_PARAM = "tagFamilyUuid";

	public static String TAG_PARAM = "tagUuid";

	public static String getTagUuid(RoutingContext rc) {
		return rc.request().getParam(TAG_PARAM);
	}

	public static String getTagFamilyUuid(RoutingContext rc) {
		return rc.request().getParam(TAG_FAMILY_PARAM);
	}

	public static String getTagFamilyUuid(InternalActionContext ac) {
		return ac.getParameter(TAG_FAMILY_PARAM);
	}

	public static String getTagUuid(InternalActionContext ac) {
		return ac.getParameter(TAG_PARAM);
	}

}

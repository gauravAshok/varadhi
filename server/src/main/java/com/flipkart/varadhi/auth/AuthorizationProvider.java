package com.flipkart.varadhi.auth;

import com.flipkart.varadhi.entities.UserContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface AuthorizationProvider {
    Future<Boolean> init(JsonObject configuration);

    Future<Boolean> isAuthorized(UserContext userContext, ResourceAction action, String resource);

    class NoAuthorizationProvider implements AuthorizationProvider {

        @Override
        public Future<Boolean> init(JsonObject configuration) {
            return Future.succeededFuture(true);
        }

        @Override
        public Future<Boolean> isAuthorized(UserContext userContext, ResourceAction action, String resource) {
            return Future.succeededFuture(false);
        }
    }
}
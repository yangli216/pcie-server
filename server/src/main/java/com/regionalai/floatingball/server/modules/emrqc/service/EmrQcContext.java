package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisSessionScope;

public class EmrQcContext {

    private final AdminCurrentUser user;
    private final PhisSessionScope.Scope scope;
    private final String idOrg;
    private final String requestId;

    public EmrQcContext(AdminCurrentUser user,
                        PhisSessionScope.Scope scope,
                        String idOrg,
                        String requestId) {
        this.user = user;
        this.scope = scope;
        this.idOrg = idOrg;
        this.requestId = requestId;
    }

    public AdminCurrentUser getUser() {
        return user;
    }

    public PhisSessionScope.Scope getScope() {
        return scope;
    }

    public String getSessionId() {
        return user == null ? null : user.getAuthSessionId();
    }

    public String getIdOrg() {
        return idOrg;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOperatorId() {
        if (user == null) {
            return null;
        }
        return user.getBbpUserId() != null ? user.getBbpUserId() : user.getIdUser();
    }

    public String getOperatorName() {
        return user == null ? null : user.getNaUser();
    }
}

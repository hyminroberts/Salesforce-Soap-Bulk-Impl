/*
 * Copyright (c) 2019 Etix / Intellimark, Inc - All Rights Reserved.
 * This file is part of 'web-app', unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.etix.api.salesforce;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.sforce.soap.enterprise.Connector;
import com.sforce.soap.enterprise.EnterpriseConnection;
import com.sforce.soap.enterprise.LoginResult;
import com.sforce.soap.enterprise.QueryResult;
import com.sforce.soap.enterprise.SaveResult;
import com.sforce.soap.enterprise.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;

/**
 * Manager class for integration with Salesforce SOAP APIs.
 * @author Zhonghui Luo
 * @since Mar 22nd, 2019, DEV-4309
 */
public class SalesforceSoapManager {

    private static EnterpriseConnection enterpriseConnection;

    /**
     * Login to connect to Salesforce.
     * @param user salfesforce account
     * @param psw password
     * @return an instance of MetadataConnection
     * @throws ConnectionException when connecting exception met
     * https://developer.salesforce.com/docs/atlas.en-us.api.meta/api/sforce_api_calls_login.htm
     */
    public LoginResult loginBySoap(String user, String psw) throws ConnectionException {
        ConnectorConfig config = new ConnectorConfig();
        config.setAuthEndpoint(SalesforceConstants.SF_SOAP_ENDPOINT);
        config.setManualLogin(true);
        LoginResult loginResult = loginToSalesforce(user, psw, config);
        
        //After logging in, make sure that your client application performs these tasks:
        //1). Sets the session ID in the SOAP header so that the API can validate subsequent requests for this session;
        //2). Specifies the server URL as the target for subsequent service requests. The login server supports only login calls;
        if (!loginResult.getPasswordExpired()) {
            config.setSessionId(loginResult.getSessionId());
            config.setServiceEndpoint(loginResult.getServerUrl());
            enterpriseConnection = getEnterpriseConnection(config);
        }

        return loginResult;
    }

    /**
     * Salesforce recommends that you always call logout() to end a session when it is no longer needed.
     * This call ends any child sessions in addition to the session being logged out.
     * Logging out instead of waiting for the configured session expiration provides the most protection.
     */
    public void logout() {
        try {
            enterpriseConnection.logout();
        } catch (ConnectionException ex) {
            Logger.getLogger(SalesforceSoapManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private LoginResult loginToSalesforce(final String username, final String password, final ConnectorConfig config) throws ConnectionException {
        return getEnterpriseConnection(config).login(username, password);
    }

    private EnterpriseConnection getEnterpriseConnection(final ConnectorConfig config) throws ConnectionException {
        return Connector.newConnection(config);
    }

    /**
     * Create custom object on Salesforce platform.
     * @param sfObjects array of SObject
     * @return an array of SaveResult objects representing result of creating each SObject.
     * @throws com.sforce.ws.ConnectionException when exception encountered
     */
    public SaveResult[] create(SObject[] sfObjects) throws ConnectionException {
        return enterpriseConnection.create(sfObjects);
    }
    
    /**
     * Execute Query to get single SObject.
     * @param queryString query string
     * @return queried SObject or null if there is no
     */
    public SObject[] queryObject(String queryString) {
        SObject[] res = null;
        try {
            QueryResult queryResults = enterpriseConnection.query(queryString);
            if (queryResults.getRecords().length > 0) {
                res = queryResults.getRecords();
            }
        } catch (ConnectionException ex) {
            Logger.getLogger(SalesforceSoapManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return res;
    }

}
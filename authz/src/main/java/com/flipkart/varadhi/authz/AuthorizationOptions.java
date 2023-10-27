package com.flipkart.varadhi.authz;

import lombok.Data;

import java.util.List;

@Data
public class AuthorizationOptions {

    private List<String> superUsers;

    /**
     * Fully qualified package path to the class implementing AuthorizationProvider interface.
     */
    private String providerClassName;

    /**
     * Path to a file having authorization provider configs.<br>
     * This file can be in any format, since the provider will implement the logic to parse this config file.<br>
     */
    private String configFile;
}

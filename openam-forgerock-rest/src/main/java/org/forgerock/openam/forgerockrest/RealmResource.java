/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2012-2013 ForgeRock Inc.
 */
package org.forgerock.openam.forgerockrest;

import java.lang.Exception;
import java.lang.String;
import java.security.AccessController;
import java.text.MessageFormat;
import java.util.*;
import java.util.logging.Level;

import com.iplanet.sso.SSOToken;
import com.sun.identity.cli.realm.RealmUtils;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.AMIdentityRepository;
import com.sun.identity.idm.IdConstants;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.sm.OrganizationConfigManager;
import com.sun.identity.sm.SMSException;


import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;
import org.forgerock.json.resource.*;
import com.sun.identity.idsvcs.*;

import static org.forgerock.openam.forgerockrest.RestUtils.getCookieFromServerContext;
import static org.forgerock.openam.forgerockrest.RestUtils.hasPermission;

/**
 * A simple {@code Map} based collection resource provider.
 */
public final class RealmResource implements CollectionResourceProvider {
    // TODO: filters, sorting, paged results.

    private Set subRealms = null;

    final private static String SERVICE_NAMES = "serviceNames";

    /**
     * Creates a new empty backend.
     */
    public RealmResource() {
        // No implementation required.
        this.subRealms = null;
    }

    public RealmResource(Set subRealms) {
        this.subRealms = subRealms;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void actionCollection(final ServerContext context, final ActionRequest request,
                                 final ResultHandler<JsonValue> handler) {
        final ResourceException e =
                new NotSupportedException("Actions are not supported for resource instances");
        handler.handleError(e);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void actionInstance(final ServerContext context, final String resourceId, final ActionRequest request,
                               final ResultHandler<JsonValue> handler) {
        final ResourceException e =
                new NotSupportedException("Actions are not supported for resource Realms");
        handler.handleError(e);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createInstance(final ServerContext context, final CreateRequest request,
                               final ResultHandler<Resource> handler) {

        Resource resource = null;
        String parentRealm = null;
        String childRealm = null;

        if (!hasPermission(context)) {
            handler.handleError(new PermanentException(401, "Unauthorized", null));
        }

        final JsonValue jVal = request.getContent();
        // get the realm
        String realm = jVal.get("realm").asString();

        try {
            if(realm.isEmpty()){
                handler.handleError(new BadRequestException("No realm name provided."));
            }else if (realm != null && !realm.startsWith("/")) {
                realm = "/" + realm;
            }
            // build realm to comply with format
            String holdRealm = request.getResourceName();
            if (holdRealm != null && !holdRealm.isEmpty()) {
                // take out last realms param, a realm can be called realms
                String tmp = new String(holdRealm.substring(0, holdRealm.lastIndexOf("/realms/")));
                realm = tmp + realm;
            }

            parentRealm = RealmUtils.getParentRealm(realm);
            childRealm = RealmUtils.getChildRealm(realm);

            OrganizationConfigManager ocm = new OrganizationConfigManager(getSSOToken(), parentRealm);

            Map defaultValues = createServicesMap(jVal);
            ocm.createSubOrganization(childRealm, defaultValues);

            // handle response
            // create a resource for handler to return
            OrganizationConfigManager realmCreated = new OrganizationConfigManager(getSSOToken(),childRealm);
            resource = new Resource(childRealm, "0", createJsonMessage("realmCreated",
                    realmCreated.getOrganizationName()));
            handler.handleResult(resource);

        } catch (SMSException smse) {
            try {
                configureErrorMessage(smse);
            } catch (NotFoundException nf) {
                RestDispatcher.debug.error("RealmResource.createInstance()" + "Cannot find "
                        + realm + ":" + smse);
                handler.handleError(nf);
            } catch (ForbiddenException fe) {
                //User does not have authorization
                RestDispatcher.debug.error("RealmResource.createInstance()" + "Cannot CREATE "
                        + realm + ":" + smse);
                handler.handleError(fe);
            } catch (PermanentException pe) {
                RestDispatcher.debug.error("RealmResource.createInstance()" + "Cannot CREATE "
                        + realm + ":" + smse);
                //Cannot recover from this exception
                handler.handleError(pe);
            } catch (ConflictException ce) {
                RestDispatcher.debug.error("RealmResource.createInstance()" + "Cannot CREATE "
                        + realm + ":" + smse);
                handler.handleError(ce);
            } catch (BadRequestException be) {
                RestDispatcher.debug.error("RealmResource.createInstance()" + "Cannot CREATE "
                        + realm + ":" + smse);
                handler.handleError(be);
            } catch (Exception e) {
                handler.handleError(new BadRequestException(e.getMessage(), e));
            }
        } catch (Exception e) {
            RestDispatcher.debug.error("RealmResource.createInstance()" + realm + ":" + e);
            handler.handleError(new BadRequestException(e.getMessage(), e));
        }

    }

    /**
     * Returns a JsonValue containing appropriate identity details
     *
     * @param message Description of result
     * @return The JsonValue Object
     */
    private JsonValue createJsonMessage(String key, Object message) {
        JsonValue result = new JsonValue(new LinkedHashMap<String, Object>(1));
        try {
            result.put(key, message);
            return result;
        } catch (final Exception e) {
            throw new JsonValueException(result);
        }
    }

    /**
     * Returns a JsonValue containing Service Names and Values
     *
     * @param ocm          The organization configuration manager
     * @param serviceNames Names of the services available to the organization
     * @return The JsonValue Object containing attributes assigned
     *         to the services
     */
    private JsonValue serviceNamesToJSON(OrganizationConfigManager ocm, Set serviceNames) throws SMSException {
        JsonValue realmServices = new JsonValue(new LinkedHashMap<String, Object>(1));
        try {
            for (Object service : serviceNames) {
                String tmp = (String) service;
                Object holdAttrForService = ocm.getAttributes(tmp);
                realmServices.add(tmp, holdAttrForService);
            }
        } catch (SMSException e) {
            RestDispatcher.debug.error("RealmResource.serviceNamesToJSON :: " + e);
            throw e;
        }
        return realmServices;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteInstance(final ServerContext context, final String resourceId,
                               final DeleteRequest request, final ResultHandler<Resource> handler) {

        if (!hasPermission(context)) {
            handler.handleError(new PermanentException(401, "Unauthorized", null));
        }
        boolean recursive = false;
        Resource resource = null;

        try {
            OrganizationConfigManager ocm = new OrganizationConfigManager(
                    getSSOToken(), resourceId);
            ocm.deleteSubOrganization(null, recursive);
            // handle resource
            resource = new Resource(resourceId, "0", createJsonMessage("success", "true"));
            handler.handleResult(resource);
        } catch (SMSException smse) {
            try {
                configureErrorMessage(smse);
            } catch (NotFoundException nf) {
                RestDispatcher.debug.error("RealmResource.deleteInstance()" + "Cannot find "
                        + resourceId + ":" + smse);
                handler.handleError(nf);
            } catch (ForbiddenException fe) {
                // User does not have authorization
                RestDispatcher.debug.error("RealmResource.deleteInstance()" + "Cannot DELETE "
                        + resourceId + ":" + smse);
                handler.handleError(fe);
            } catch (PermanentException pe) {
                RestDispatcher.debug.error("RealmResource.deleteInstance()" + "Cannot DELETE "
                        + resourceId + ":" + smse);
                // Cannot recover from this exception
                handler.handleError(pe);
            } catch (ConflictException ce) {
                RestDispatcher.debug.error("RealmResource.deleteInstance()" + "Cannot DELETE "
                        + resourceId + ":" + smse);
                handler.handleError(ce);
            } catch (BadRequestException be) {
                RestDispatcher.debug.error("RealmResource.deleteInstance()" + "Cannot DELETE "
                        + resourceId + ":" + smse);
                handler.handleError(be);
            } catch (Exception e) {
                handler.handleError(new BadRequestException(e.getMessage(), e));
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void patchInstance(final ServerContext context, final String resourceId, final PatchRequest request,
                              final ResultHandler<Resource> handler) {
        final ResourceException e = new NotSupportedException("Patch operations are not supported for resource Realms");
        handler.handleError(e);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void queryCollection(final ServerContext context, final QueryRequest request,
                                final QueryResultHandler handler) {
        if (hasPermission(context)) { //check to make sure admin
            for (Object theRealm : subRealms) {
                String realm = (String) theRealm;
                JsonValue val = new JsonValue(realm);
                Resource resource = new Resource("0", "0", val);
                handler.handleResource(resource);
            }
            handler.handleResult(new QueryResult());
        } else {
            handler.handleError(new PermanentException(401, "Unauthorized", null));
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readInstance(final ServerContext context, final String resourceId,
                             final ReadRequest request, final ResultHandler<Resource> handler) {

        if (!hasPermission(context)) {
            handler.handleError(new PermanentException(401, "Unauthorized", null));
        }

        Resource resource = null;
        JsonValue jval = null;
        try {
            OrganizationConfigManager ocm = new OrganizationConfigManager(
                    getSSOToken(), resourceId);
            // get associated services for this realm , include mandatory service names.
            Set serviceNames = ocm.getAssignedServices();
            jval = createJsonMessage(SERVICE_NAMES, serviceNames);
            resource = new Resource(resourceId, "0", jval);
            handler.handleResult(resource);

        } catch (SMSException smse) {
            try {
                configureErrorMessage(smse);
            } catch (NotFoundException nf) {
                RestDispatcher.debug.error("RealmResource.readInstance()" + "Cannot find "
                        + resourceId + ":" + smse);
                handler.handleError(nf);
            } catch (ForbiddenException fe) {
                // User does not have authorization
                RestDispatcher.debug.error("RealmResource.readInstance()" + "Cannot READ "
                        + resourceId + ":" + smse);
                handler.handleError(fe);
            } catch (PermanentException pe) {
                RestDispatcher.debug.error("RealmResource.readInstance()" + "Cannot READ "
                        + resourceId + ":" + smse);
                // Cannot recover from this exception
                handler.handleError(pe);
            } catch (ConflictException ce) {
                RestDispatcher.debug.error("RealmResource.readInstance()" + "Cannot READ "
                        + resourceId + ":" + smse);
                handler.handleError(ce);
            } catch (BadRequestException be) {
                RestDispatcher.debug.error("RealmResource.readInstance()" + "Cannot READ "
                        + resourceId + ":" + smse);
                handler.handleError(be);
            } catch (Exception e) {
                handler.handleError(new BadRequestException(e.getMessage(), e));
            }
        }
    }

    /**
     * Throws an appropriate HTTP status code
     *
     * @param exception SMSException to be mapped to HTTP Status code
     * @throws ForbiddenException
     * @throws NotFoundException
     * @throws PermanentException
     * @throws ConflictException
     * @throws BadRequestException
     */
    private void configureErrorMessage(final SMSException exception)
            throws ForbiddenException, NotFoundException, PermanentException,
            ConflictException, BadRequestException {
        if (exception.getErrorCode().equalsIgnoreCase("sms-REALM_NAME_NOT_FOUND")) {
            throw new NotFoundException(exception.getMessage(), exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-INVALID_SSO_TOKEN")) {
            throw new PermanentException(401, "Unauthorized-Invalid SSO Token", exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-organization_already_exists1")) {
            throw new ConflictException(exception.getMessage(), exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-invalid-org-name")) {
            throw new BadRequestException(exception.getMessage(), exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-cannot_delete_rootsuffix")) {
            throw new PermanentException(401, "Unauthorized-Cannot delete root suffix", exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-entries-exists")) {
            throw new ConflictException(exception.getMessage(), exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-SMSSchema_service_notfound")) {
            throw new NotFoundException(exception.getMessage(), exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-no-organization-schema")) {
            throw new NotFoundException(exception.getMessage(), exception);
        } else if (exception.getErrorCode().equalsIgnoreCase("sms-attribute-values-does-not-match-schema")) {
            throw new BadRequestException(exception.getMessage(), exception);
        } else {
            throw new BadRequestException(exception.getMessage(), exception);
        }

    }

    /**
     * Creates Organization within OpenAM
     *
     * @param ocm   Organization Configuration Manager
     * @param jVal  JSONvalue that contains the payload
     * @param realm Name of the realm to be created
     * @throws SMSException
     * @throws Exception
     */
    private void createOrganization(OrganizationConfigManager ocm,
                                    JsonValue jVal, String realm) throws Exception {
        Map defaultValues = null;
        OrganizationConfigManager realmCreatedOcm;
        try {
            JsonValue realmDetails = jVal;
            if (jVal != null) {
                defaultValues = createServicesMap(jVal);
            }
            ocm.createSubOrganization(realm, defaultValues);
            // Get the Organization Configuration Manager for the new Realm
            realmCreatedOcm = new OrganizationConfigManager(getSSOToken(), realm);
            List newServiceNames = realmDetails.get(SERVICE_NAMES).asList();
            if (!newServiceNames.isEmpty() && newServiceNames != null) {
                // assign services to realm
                assignServices(realmCreatedOcm, newServiceNames);
            }
        } catch (SMSException smse) {
            // send back
            throw smse;
        } catch (Exception e) {
            RestDispatcher.debug.error("RealmResource.createOrganization()" + e);
            throw e;
        }
    }

    /**
     * Creates a Map from JsonValue content
     *
     * @param realmDetails Payload that is from request
     * @return Map of default Services needed to create realm
     * @throws Exception
     */
    private Map createServicesMap(JsonValue realmDetails) throws Exception {
        // Default Attribtes
        final String rstatus = realmDetails.get(IdConstants.ORGANIZATION_STATUS_ATTR).asString();
        // get the realm/DNS Aliases
        final String realmAliases = realmDetails.get(IdConstants.ORGANIZATION_ALIAS_ATTR).asString();
        Map defaultValues = new HashMap(2);
        try {
            Map map = new HashMap(2);
            Set values = new HashSet(2);
            values.add(rstatus);
            map.put(IdConstants.ORGANIZATION_STATUS_ATTR, values);
            if (realmAliases != null && !realmAliases.isEmpty()) {
                Set values1 = new HashSet(2);
                values1.add(realmAliases);
                map.put(IdConstants.ORGANIZATION_ALIAS_ATTR, values1);
            }
            defaultValues.put(IdConstants.REPO_SERVICE, map);
        } catch (Exception e) {
            throw e;
        }
        return defaultValues;
    }

    /**
     * Update a service with new attributes
     *
     * @param ocm          Organization Configuration Manager
     * @param serviceNames Map of service names
     * @throws SMSException
     */
    private void updateConfiguredServices(OrganizationConfigManager ocm,
                                          Map serviceNames) throws SMSException {
        try {
            ocm.setAttributes(IdConstants.REPO_SERVICE,(Map) serviceNames.get(IdConstants.REPO_SERVICE));
        } catch (SMSException smse) {
            throw smse;
        }
    }


    /**
     * Assigns Services to a realm
     *
     * @param ocm             Organization Configuration Manager
     * @param newServiceNames List of service names to be assigned/unassigned
     * @throws SMSException
     */
    private void assignServices(OrganizationConfigManager ocm, List newServiceNames)
            throws SMSException {
        try {
            // include mandatory, otherwise pass in false
            Set assignedServices = ocm.getAssignedServices();
            // combine new services names with current assigned services
            Set allServices = new HashSet(newServiceNames.size() + assignedServices.size());

            // add all to make union of the two sets of service names
            allServices.addAll(assignedServices);
            allServices.addAll(newServiceNames);

            // update services associated with realm
            for (Object tmp : allServices) {
                String serviceName = (String) tmp;
                if (newServiceNames.contains(serviceName) && assignedServices.contains(serviceName)) {
                    // do nothing, keep current service name as it is for now
                } else if (newServiceNames.contains(serviceName) && !assignedServices.contains(serviceName)) {
                    // assign the service to realm
                    ocm.assignService(serviceName, null);
                } else if (!newServiceNames.contains(serviceName) && assignedServices.contains(serviceName)) {
                    // unassign the service from the realm  if not mandatory
                    ocm.unassignService(serviceName);
                }
            }
        } catch (SMSException smse) {
            throw smse;
        }
    }

    /**
     * Create an amAdmin SSOToken
     *
     * @return SSOToken adminSSOtoken
     */
    private SSOToken getSSOToken() {
        return (SSOToken) AccessController.doPrivileged(AdminTokenAction.getInstance());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateInstance(final ServerContext context, final String resourceId,
                               final UpdateRequest request, final ResultHandler<Resource> handler) {
        // check to make sure superuser
        if (!hasPermission(context)) {
            handler.handleError(new PermanentException(401, "Unauthorized", null));
        }

        final JsonValue realmDetails = request.getNewContent();
        Resource resource = null;
        String realm = null;
        OrganizationConfigManager ocm = null;
        OrganizationConfigManager realmCreatedOcm = null;
        realm = resourceId;
        if (realm != null && !realm.startsWith("/")) {
            realm = "/" + realm;
        }

        try {
            // The initial attempt to UPDATE a realm,
            // if the realm does not exist it must be created
            ocm = new OrganizationConfigManager(getSSOToken(), realm);

            List newServiceNames = null;
            // update ID_REPO attributes
            updateConfiguredServices(ocm, createServicesMap(realmDetails));
            newServiceNames = realmDetails.get(SERVICE_NAMES).asList();
            if (!newServiceNames.isEmpty() && newServiceNames != null) {
                assignServices(ocm, newServiceNames); //assign services to realm
            }
            // READ THE REALM
            realmCreatedOcm = new OrganizationConfigManager(getSSOToken(),realm);
            // create a resource for handler to return
            resource = new Resource(realm, "0", createJsonMessage("realmUpdated",
                    realmCreatedOcm.getOrganizationName()));
            handler.handleResult(resource);
        } catch (SMSException e) {
            try {
                configureErrorMessage(e);
            } catch (NotFoundException nfe) {
                RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot find "
                        + resourceId + ":" + e + "\n" + "CREATING " + resourceId);
                // Realm was NOT found, therefore create the realm
                try {
                    String holdRealm = request.getResourceName();
                    if (holdRealm != null && !holdRealm.isEmpty()) {
                        // take out last realm param
                        String tmp = new String(holdRealm.substring(0, holdRealm.lastIndexOf("/realms/")));
                        realm = tmp + realm;
                    }
                    String parentRealm = RealmUtils.getParentRealm(realm);
                    String childRealm = RealmUtils.getChildRealm(realm);
                    ocm = new OrganizationConfigManager(getSSOToken(), parentRealm);
                    // create the realm
                    createOrganization(ocm, realmDetails, childRealm);

                    // handle response
                    // read the realm to make sure that it has been created...
                    realmCreatedOcm = new OrganizationConfigManager(getSSOToken(), childRealm);
                    resource = new Resource(childRealm, "0", createJsonMessage("realmCreated",
                            realmCreatedOcm.getOrganizationName()));
                    handler.handleResult(resource);
                } catch (SMSException smse) {
                    try {
                        configureErrorMessage(smse);
                    } catch (NotFoundException nf) {
                        RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot find "
                                + resourceId + ":" + smse);
                        handler.handleError(nf);
                    } catch (ForbiddenException fe) {
                        // User does not have authorization
                        RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                                + resourceId + ":" + smse);
                        handler.handleError(fe);
                    } catch (PermanentException pe) {
                        RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                                + resourceId + ":" + smse);
                        // Cannot recover from this exception
                        handler.handleError(pe);
                    } catch (ConflictException ce) {
                        RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                                + resourceId + ":" + smse);
                        handler.handleError(ce);
                    } catch (BadRequestException be) {
                        RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                                + resourceId + ":" + smse);
                        handler.handleError(be);
                    }
                } catch (Exception ex) {
                    RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                            + resourceId + ":" + ex);
                    handler.handleError(new NotFoundException("Cannot update realm.", ex));
                }

            } catch (ForbiddenException fe) {
                // User does not have authorization
                RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                        + resourceId + ":" + e);
                handler.handleError(fe);
            } catch (PermanentException pe) {
                RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                        + resourceId + ":" + e);
                // Cannot recover from this exception
                handler.handleError(pe);
            } catch (ConflictException ce) {
                RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                        + resourceId + ":" + e);
                handler.handleError(ce);
            } catch (BadRequestException be) {
                RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                        + resourceId + ":" + e);
                handler.handleError(be);
            } catch (Exception ex) {
                RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                        + resourceId + ":" + ex);
                handler.handleError(new NotFoundException("Cannot update realm.", ex));
            }
        } catch (Exception ex) {
            RestDispatcher.debug.error("RealmResource.updateInstance()" + "Cannot UPDATE "
                    + resourceId + ":" + ex);
            handler.handleError(new NotFoundException("Cannot update realm.", ex));
        }
    }
}
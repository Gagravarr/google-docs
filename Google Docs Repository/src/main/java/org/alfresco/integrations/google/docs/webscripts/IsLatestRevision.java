/**
 * Copyright (C) 2005-2013 Alfresco Software Limited.
 * 
 * This file is part of Alfresco
 * 
 * Alfresco is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * Alfresco is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.alfresco.integrations.google.docs.webscripts;


import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.alfresco.integrations.google.docs.GoogleDocsModel;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsAuthenticationException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsRefreshTokenException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsServiceException;
import org.alfresco.integrations.google.docs.service.GoogleDocsService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptException;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.social.google.api.drive.FileRevision;

/**
 * @author Jared Ottley <jared.ottley@alfresco.com>
 */
public class IsLatestRevision
    extends DeclarativeWebScript
{
    private static final Log    log                      = LogFactory.getLog(IsLatestRevision.class);

    private GoogleDocsService   googledocsService;
    private NodeService         nodeService;

    private boolean             isLatestRevision         = false;

    private final static String PARAM_NODEREF            = "nodeRef";

    private static final String MODEL_IS_LATEST_REVISION = "isLatestRevision";


    public void setGoogledocsService(GoogleDocsService googledocsService)
    {
        this.googledocsService = googledocsService;
    }


    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }


    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache)
    {
        Map<String, Object> model = new HashMap<String, Object>();

        /* Get the nodeRef to test */
        String param_nodeRef = req.getParameter(PARAM_NODEREF);
        NodeRef nodeRef = new NodeRef(param_nodeRef);
        log.debug("Comparing Node Revision Id from Alfresco and Google: " + nodeRef);

        /* The revision Id persisted on the node */
        String currentRevision = null;
        /* The latest revision Id from Google for the file */
        String latestRevision = null;

        try
        {
            /* The node needs the editingInGoogle aspect if not then tell return 412 */
            if (nodeService.hasAspect(nodeRef, GoogleDocsModel.ASPECT_EDITING_IN_GOOGLE))
            {
                /* get the nodes revision Id null if not found */
                Serializable property = nodeService.getProperty(nodeRef, GoogleDocsModel.PROP_REVISION_ID);
                currentRevision = property != null ? property.toString() : null;
                log.debug("currentRevision: " + currentRevision);

                /* get the latest revision Id null if not found */
                FileRevision fileRevision = googledocsService.getLatestRevision(nodeRef);
                latestRevision = fileRevision != null ? fileRevision.getId() : null;
                log.debug("latestRevision: " + latestRevision);

                /* compare the revision Ids */
                if (currentRevision!= null && latestRevision != null)
                {
                    
                    isLatestRevision = currentRevision.equals(latestRevision);
                }

                model.put(MODEL_IS_LATEST_REVISION, isLatestRevision);
            }
            else
            {
                throw new WebScriptException(HttpStatus.SC_PRECONDITION_FAILED, "Node: " + nodeRef.toString()
                                                                                + " has no revision Ids.");
            }
        }
        catch (GoogleDocsAuthenticationException gdae)
        {
            throw new WebScriptException(HttpStatus.SC_BAD_GATEWAY, gdae.getMessage());
        }
        catch (GoogleDocsServiceException gdse)
        {
            if (gdse.getPassedStatusCode() > -1)
            {
                throw new WebScriptException(gdse.getPassedStatusCode(), gdse.getMessage());
            }
            else
            {
                throw new WebScriptException(gdse.getMessage());
            }
        }
        catch (GoogleDocsRefreshTokenException gdrte)
        {
            throw new WebScriptException(HttpStatus.SC_BAD_GATEWAY, gdrte.getMessage());
        }

        return model;
    }
}

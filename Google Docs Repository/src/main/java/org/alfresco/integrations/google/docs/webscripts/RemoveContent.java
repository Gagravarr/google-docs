
package org.alfresco.integrations.google.docs.webscripts;


import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.alfresco.integrations.google.docs.GoogleDocsConstants;
import org.alfresco.integrations.google.docs.GoogleDocsModel;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsAuthenticationException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsRefreshTokenException;
import org.alfresco.integrations.google.docs.exceptions.GoogleDocsServiceException;
import org.alfresco.integrations.google.docs.service.GoogleDocsService;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.permissions.AccessDeniedException;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.TransactionListenerAdapter;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.dictionary.ConstraintException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.surf.util.Content;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptException;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.social.google.api.drive.DriveFile;


public class RemoveContent
    extends DeclarativeWebScript
{
    private static final Log    log              = LogFactory.getLog(RemoveContent.class);

    private GoogleDocsService   googledocsService;
    private NodeService         nodeService;
    private TransactionService  transactionService;

    private static final String JSON_KEY_NODEREF = "nodeRef";
    private static final String JSON_KEY_FORCE   = "force";

    private static final String MODEL_SUCCESSFUL = "success";


    public void setGoogledocsService(GoogleDocsService googledocsService)
    {
        this.googledocsService = googledocsService;
    }


    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }


    public void setTransactionService(TransactionService transactionService)
    {
        this.transactionService = transactionService;
    }


    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache)
    {
        Map<String, Object> model = new HashMap<String, Object>();

        boolean success = false;

        /* The node we are working on */
        Map<String, Serializable> map = parseContent(req);
        final NodeRef nodeRef = (NodeRef)map.get(JSON_KEY_NODEREF);

        /* Make sure the node is currently "checked out" to Google */
        if (nodeService.hasAspect(nodeRef, GoogleDocsModel.ASPECT_EDITING_IN_GOOGLE))
        {
            try
            {
                /* Get the metadata for the file we are working on */
                DriveFile driveFile = googledocsService.getDriveFile(nodeRef);
                /* remove it from users Google account and free it in the repo */
                googledocsService.removeContent(nodeRef, driveFile, (Boolean)map.get(JSON_KEY_FORCE));

                /* if we reach this point all should be completed */
                success = true;

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
            catch (ConstraintException ce)
            {
                throw new WebScriptException(GoogleDocsConstants.STATUS_INTEGIRTY_VIOLATION, ce.getMessage(), ce);
            }
            catch (AccessDeniedException ade)
            {
                // This code will make changes after the rollback has occurred to clean up the node
                // (remove the lock and the Google Docs aspect
                AlfrescoTransactionSupport.bindListener(new TransactionListenerAdapter()
                {
                    public void afterRollback()
                    {
                        log.debug("Rollback Save to node: " + nodeRef);
                        transactionService.getRetryingTransactionHelper().doInTransaction(new RetryingTransactionCallback<Object>()
                        {
                            public Object execute()
                                throws Throwable
                            {
                                return AuthenticationUtil.runAsSystem(new AuthenticationUtil.RunAsWork<Object>()
                                {
                                    public Object doWork()
                                        throws Exception
                                    {
                                        googledocsService.unlockNode(nodeRef);
                                        googledocsService.unDecorateNode(nodeRef);

                                        // If the node was just created ('Create Content') it will
                                        // have the temporary aspect and should be completely removed.
                                        if (nodeService.hasAspect(nodeRef, ContentModel.ASPECT_TEMPORARY))
                                        {
                                            nodeService.deleteNode(nodeRef);
                                        }

                                        return null;
                                    }
                                });
                            }
                        }, false, true);
                    }
                });

                throw new WebScriptException(HttpStatus.SC_FORBIDDEN, ade.getMessage(), ade);
            }
            catch (Exception e)
            {
                throw new WebScriptException(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getMessage(), e);
            }
        }

        model.put(MODEL_SUCCESSFUL, success);

        return model;
    }


    private Map<String, Serializable> parseContent(final WebScriptRequest req)
    {
        final Map<String, Serializable> result = new HashMap<String, Serializable>();
        Content content = req.getContent();
        String jsonStr = null;
        JSONObject json = null;

        try
        {
            if (content == null || content.getSize() == 0)
            {
                throw new WebScriptException(HttpStatus.SC_BAD_REQUEST, "No content sent with request.");
            }

            jsonStr = content.getContent();

            if (jsonStr == null || jsonStr.trim().length() == 0)
            {
                throw new WebScriptException(HttpStatus.SC_BAD_REQUEST, "No content sent with request.");
            }
            log.debug("Parsed JSON: " + jsonStr);

            json = new JSONObject(jsonStr);

            if (!json.has(JSON_KEY_NODEREF))
            {
                throw new WebScriptException(HttpStatus.SC_BAD_REQUEST, "Key " + JSON_KEY_NODEREF + " is missing from JSON: "
                                                                        + jsonStr);
            }
            else
            {
                NodeRef nodeRef = new NodeRef(json.getString(JSON_KEY_NODEREF));
                result.put(JSON_KEY_NODEREF, nodeRef);
            }

            if (!json.has(JSON_KEY_FORCE))
            {
                result.put(JSON_KEY_FORCE, false);
            }
            else
            {
                result.put(JSON_KEY_FORCE, json.getBoolean(JSON_KEY_FORCE));
            }

        }
        catch (final IOException ioe)
        {
            throw new WebScriptException(HttpStatus.SC_INTERNAL_SERVER_ERROR, ioe.getMessage(), ioe);
        }
        catch (final JSONException je)
        {
            throw new WebScriptException(HttpStatus.SC_BAD_REQUEST, "Unable to parse JSON: " + jsonStr);
        }
        catch (final WebScriptException wse)
        {
            throw wse; // Ensure WebScriptExceptions get rethrown verbatim
        }
        catch (final Exception e)
        {
            throw new WebScriptException(HttpStatus.SC_BAD_REQUEST, "Unable to parse JSON '" + jsonStr + "'.", e);
        }

        return result;
    }

}

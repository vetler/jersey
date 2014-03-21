/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.internal.routing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedAction;
import java.util.List;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.SecurityContext;

import org.glassfish.jersey.internal.Errors;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.internal.util.PropertiesHelper;
import org.glassfish.jersey.model.internal.RankedComparator;
import org.glassfish.jersey.model.internal.RankedProvider;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.SubjectSecurityContext;
import org.glassfish.jersey.server.internal.JerseyResourceContext;
import org.glassfish.jersey.server.internal.LocalizationMessages;
import org.glassfish.jersey.server.internal.process.MappableException;
import org.glassfish.jersey.server.internal.process.RequestProcessingContext;
import org.glassfish.jersey.server.model.ComponentModelValidator;
import org.glassfish.jersey.server.model.ModelProcessor;
import org.glassfish.jersey.server.model.ModelValidationException;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.model.ResourceModel;
import org.glassfish.jersey.server.model.ResourceModelComponent;
import org.glassfish.jersey.server.model.internal.ModelErrors;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.spi.internal.ParameterValueHelper;

import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.ServiceLocator;

/**
 * An methodAcceptorPair to accept sub-resource requests.
 * It first retrieves the sub-resource instance by invoking the given model method.
 * Then the {@link RuntimeModelBuilder} is used to generate corresponding methodAcceptorPair.
 * Finally the generated methodAcceptorPair is invoked to return the request methodAcceptorPair chain.
 * <p/>
 *
 * @author Jakub Podlesak (jakub.podlesak at oracle.com)
 * @author Miroslav Fuksa (miroslav.fuksa at oracle.com)
 */
class SubResourceLocatorRouter implements Router {

    private final ServiceLocator locator;
    private final ResourceMethod locatorModel;
    private final List<Factory<?>> valueProviders;
    private final RuntimeModelBuilder runtimeModelBuilder;
    private final JerseyResourceContext resourceContext;
    private final boolean disableValidation;
    private final boolean ignoreValidationErrors;

    /**
     * Create a new sub-resource locator router.
     *
     * @param locator             HK2 locator.
     * @param runtimeModelBuilder original runtime model builder.
     * @param locatorModel        resource locator method model.
     */
    public SubResourceLocatorRouter(
            final ServiceLocator locator,
            final RuntimeModelBuilder runtimeModelBuilder,
            final ResourceMethod locatorModel) {
        this.locator = locator;
        this.runtimeModelBuilder = runtimeModelBuilder;
        this.locatorModel = locatorModel;
        this.valueProviders = ParameterValueHelper.createValueProviders(locator, locatorModel.getInvocable());
        this.resourceContext = locator.getService(JerseyResourceContext.class);

        final Configuration config = locator.getService(Configuration.class);
        this.disableValidation = PropertiesHelper.getValue(config.getProperties(),
                ServerProperties.RESOURCE_VALIDATION_DISABLE,
                Boolean.FALSE,
                Boolean.class);
        this.ignoreValidationErrors = PropertiesHelper.getValue(config.getProperties(),
                ServerProperties.RESOURCE_VALIDATION_IGNORE_ERRORS,
                Boolean.FALSE,
                Boolean.class);
    }

    @Override
    public Continuation apply(final RequestProcessingContext processingContext) {
        Object subResourceInstance = getResource(processingContext);

        final RoutingContext routingContext = processingContext.routingContext();

        if (subResourceInstance == null) {
            throw new NotFoundException();
        }

        Resource subResource;
        if (subResourceInstance.getClass().isAssignableFrom(Resource.class)) {
            subResource = (Resource) subResourceInstance;
        } else {
            if (subResourceInstance.getClass().isAssignableFrom(Class.class)) {
                final Class<?> clazz = (Class<?>) subResourceInstance;
                subResourceInstance = Injections.getOrCreate(locator, clazz);
            } else {
                routingContext.pushMatchedResource(subResourceInstance);
                resourceContext.bindResourceIfSingleton(subResourceInstance);
            }

            Resource.Builder builder = Resource.builder(subResourceInstance.getClass());
            if (builder == null) {
                // resource is empty - do not throw 404, wait if ModelProcessors add any method
                builder = Resource.builder().name(subResourceInstance.getClass().getName());
            }
            subResource = builder.build();
        }

        ResourceModel resourceModel = new ResourceModel.Builder(true).addResource(subResource).build();
        resourceModel = processSubResource(resourceModel);
        if (!disableValidation) {
            validate(resourceModel, ignoreValidationErrors);
        }

        subResource = resourceModel.getResources().get(0);
        routingContext.pushLocatorSubResource(subResource);
        processingContext.triggerEvent(RequestEvent.Type.SUBRESOURCE_LOCATED);


        for (Class<?> handlerClass : subResource.getHandlerClasses()) {
            resourceContext.bindResource(handlerClass);
        }

        // TODO: implement generated sub-resource methodAcceptorPair caching
        Router subResourceAcceptor = runtimeModelBuilder.buildModel(resourceModel.getRuntimeResourceModel(), true);

        return Continuation.of(processingContext, subResourceAcceptor);
    }

    private ResourceModel processSubResource(ResourceModel subResourceModel) {
        final Configuration configuration = locator.getService(Configuration.class);
        final Iterable<RankedProvider<ModelProcessor>> allRankedProviders = Providers.getAllRankedProviders(locator,
                ModelProcessor.class);
        final Iterable<ModelProcessor> modelProcessors = Providers.sortRankedProviders(new RankedComparator<ModelProcessor>(),
                allRankedProviders);

        for (ModelProcessor modelProcessor : modelProcessors) {
            subResourceModel = modelProcessor.processSubResource(subResourceModel, configuration);
            validateEnhancedModel(subResourceModel);
        }
        return subResourceModel;
    }

    private void validateEnhancedModel(final ResourceModel subResourceModel) {
        if (subResourceModel.getResources().size() != 1) {
            throw new ProcessingException(LocalizationMessages.ERROR_SUB_RESOURCE_LOCATOR_MORE_RESOURCES(
                    subResourceModel.getResources().size()));
        }

    }

    private void validate(final ResourceModelComponent component, final boolean ignoreFatalIssues) {
        Errors.process(new Runnable() {
            @Override
            public void run() {
                final ComponentModelValidator validator = new ComponentModelValidator(locator);
                validator.validate(component);

                if (Errors.fatalIssuesFound() && !ignoreFatalIssues) {
                    throw new ModelValidationException(LocalizationMessages.ERROR_VALIDATION_SUBRESOURCE(),
                            ModelErrors.getErrorsAsResourceModelIssues());
                }
            }
        });
    }

    private Object getResource(final RequestProcessingContext context) {

        final Object resource = context.routingContext().peekMatchedResource();
        final Method handlingMethod = locatorModel.getInvocable().getHandlingMethod();
        final Object[] parameterValues = ParameterValueHelper.getParameterValues(valueProviders);

        context.triggerEvent(RequestEvent.Type.LOCATOR_MATCHED);

        final PrivilegedAction invokeMethodAction = new PrivilegedAction() {
            @Override
            public Object run() {
                try {

                    return handlingMethod.invoke(resource, parameterValues);

                } catch (IllegalAccessException | IllegalArgumentException | UndeclaredThrowableException ex) {
                    throw new ProcessingException(LocalizationMessages.ERROR_RESOURCE_JAVA_METHOD_INVOCATION(), ex);
                } catch (InvocationTargetException ex) {
                    final Throwable cause = ex.getCause();
                    if (cause instanceof WebApplicationException) {
                        throw (WebApplicationException) cause;
                    }
                    // handle all exceptions as potentially mappable (incl. ProcessingException)
                    throw new MappableException(cause);
                } catch (Throwable t) {
                    throw new ProcessingException(t);
                }
            }
        };

        final SecurityContext securityContext = context.request().getSecurityContext();
        return (securityContext instanceof SubjectSecurityContext)
                ? ((SubjectSecurityContext) securityContext).doAsSubject(invokeMethodAction) : invokeMethodAction.run();

    }
}

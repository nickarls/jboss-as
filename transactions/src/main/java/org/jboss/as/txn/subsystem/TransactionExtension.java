/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.txn.subsystem;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UNDEFINE_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;
import static org.jboss.as.txn.TransactionLogger.ROOT_LOGGER;

import javax.management.MBeanServer;

import org.jboss.as.controller.Extension;
import org.jboss.as.controller.ExtensionContext;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SubsystemRegistration;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.StandardResourceDescriptionResolver;
import org.jboss.as.controller.operations.common.GenericSubsystemDescribeHandler;
import org.jboss.as.controller.parsing.ExtensionParsingContext;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.services.path.ResolvePathHandler;
import org.jboss.as.controller.transform.DiscardAttributesTransformer;
import org.jboss.as.controller.transform.DiscardUndefinedAttributesTransformer;
import org.jboss.as.controller.transform.RejectExpressionValuesTransformer;
import org.jboss.as.controller.transform.TransformersSubRegistration;
import org.jboss.as.controller.transform.chained.ChainedOperationTransformer;
import org.jboss.as.controller.transform.chained.ChainedResourceTransformationContext;
import org.jboss.as.controller.transform.chained.ChainedResourceTransformer;
import org.jboss.as.controller.transform.chained.ChainedResourceTransformerEntry;
import org.jboss.as.txn.TransactionMessages;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;

/**
 * The transaction management extension.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author Emanuel Muckenhuber
 * @author Scott Stark (sstark@redhat.com) (C) 2011 Red Hat Inc.
 * @author Mike Musgrove (mmusgrov@redhat.com) (C) 2012 Red Hat Inc.
 */
public class TransactionExtension implements Extension {
    public static final String SUBSYSTEM_NAME = "transactions";
    /**
     * The operation name to resolve the object store path
     */
    public static final String RESOLVE_OBJECT_STORE_PATH = "resolve-object-store-path";

    private static final String RESOURCE_NAME = TransactionExtension.class.getPackage().getName() + ".LocalDescriptions";

    private static final int MANAGEMENT_API_MAJOR_VERSION = 1;
    private static final int MANAGEMENT_API_MINOR_VERSION = 2;
    private static final int MANAGEMENT_API_MICRO_VERSION = 0;

    private static final ServiceName MBEAN_SERVER_SERVICE_NAME = ServiceName.JBOSS.append("mbean", "server");
    static final PathElement LOG_STORE_PATH = PathElement.pathElement(LogStoreConstants.LOG_STORE, LogStoreConstants.LOG_STORE);
    static final PathElement SUBSYSTEM_PATH = PathElement.pathElement(ModelDescriptionConstants.SUBSYSTEM, TransactionExtension.SUBSYSTEM_NAME);
    static final PathElement PARTECIPANT_PATH = PathElement.pathElement(LogStoreConstants.PARTICIPANTS);
    static final PathElement TRANSACTION_PATH = PathElement.pathElement(LogStoreConstants.TRANSACTIONS);


    static StandardResourceDescriptionResolver getResourceDescriptionResolver(final String... keyPrefix) {
        StringBuilder prefix = new StringBuilder(SUBSYSTEM_NAME);
        for (String kp : keyPrefix) {
            prefix.append('.').append(kp);
        }
        return new StandardResourceDescriptionResolver(prefix.toString(), RESOURCE_NAME, TransactionExtension.class.getClassLoader(), true, false);
    }

    static MBeanServer getMBeanServer(OperationContext context) {
        final ServiceRegistry serviceRegistry = context.getServiceRegistry(false);
        final ServiceController<?> serviceController = serviceRegistry.getService(MBEAN_SERVER_SERVICE_NAME);
        if (serviceController == null) {
            throw TransactionMessages.MESSAGES.jmxSubsystemNotInstalled();
        }
        return (MBeanServer) serviceController.getValue();
    }

    /**
     * {@inheritDoc}
     */
    public void initialize(ExtensionContext context) {
        ROOT_LOGGER.debug("Initializing Transactions Extension");
        final LogStoreResource resource = new LogStoreResource();
        final boolean registerRuntimeOnly = context.isRuntimeOnlyRegistrationValid();
        final SubsystemRegistration subsystem = context.registerSubsystem(SUBSYSTEM_NAME, MANAGEMENT_API_MAJOR_VERSION,
                MANAGEMENT_API_MINOR_VERSION, MANAGEMENT_API_MICRO_VERSION);

        final TransactionSubsystemRootResourceDefinition rootResourceDefinition = new TransactionSubsystemRootResourceDefinition(registerRuntimeOnly);
        final ManagementResourceRegistration registration = subsystem.registerSubsystemModel(rootResourceDefinition);
        registration.registerOperationHandler(GenericSubsystemDescribeHandler.DEFINITION, GenericSubsystemDescribeHandler.INSTANCE);

        // Create the path resolver handlers
        if (context.getProcessType().isServer()) {
            // It's less than ideal to create a separate operation here, but this extension contains two relative-to attributes
            final ResolvePathHandler objectStorePathHandler = ResolvePathHandler.Builder.of(RESOLVE_OBJECT_STORE_PATH, context.getPathManager())
                   .setPathAttribute(TransactionSubsystemRootResourceDefinition.OBJECT_STORE_PATH)
                   .setRelativeToAttribute(TransactionSubsystemRootResourceDefinition.OBJECT_STORE_RELATIVE_TO)
                   .build();
            registration.registerOperationHandler(objectStorePathHandler.getOperationDefinition(), objectStorePathHandler);

            final ResolvePathHandler resolvePathHandler = ResolvePathHandler.Builder.of(context.getPathManager())
                    .setPathAttribute(TransactionSubsystemRootResourceDefinition.PATH)
                    .setRelativeToAttribute(TransactionSubsystemRootResourceDefinition.RELATIVE_TO)
                    .build();
            registration.registerOperationHandler(resolvePathHandler.getOperationDefinition(), resolvePathHandler);
        }


        ManagementResourceRegistration logStoreChild = registration.registerSubModel(new LogStoreDefinition(resource));
        if (registerRuntimeOnly) {
            ManagementResourceRegistration transactionChild = logStoreChild.registerSubModel(new LogStoreTransactionDefinition(resource));
            transactionChild.registerSubModel(LogStoreTransactionParticipantDefinition.INSTANCE);
        }

        subsystem.registerXMLElementWriter(TransactionSubsystem13Parser.INSTANCE);

        if (context.isRegisterTransformers()) {
            // Register the model transformers
            registerTransformers(subsystem);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void initializeParsers(ExtensionParsingContext context) {
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.TRANSACTIONS_1_0.getUriString(), TransactionSubsystem10Parser.INSTANCE);
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.TRANSACTIONS_1_1.getUriString(), TransactionSubsystem11Parser.INSTANCE);
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.TRANSACTIONS_1_2.getUriString(), TransactionSubsystem12Parser.INSTANCE);
        context.setSubsystemXmlMapping(SUBSYSTEM_NAME, Namespace.TRANSACTIONS_1_3.getUriString(), TransactionSubsystem13Parser.INSTANCE);
    }

    // Transformation

    /**
     * Register the transformers for older model versions.
     *
     * @param subsystem the subsystems registration
     */
    private static void registerTransformers(final SubsystemRegistration subsystem) {

        // Commonly used transformers
        final DiscardUndefinedAttributesTransformer discardJdbcStoreTransformer =
                new DiscardUndefinedAttributesTransformer(TransactionSubsystemRootResourceDefinition.attributes_1_2);
        final ChainedResourceTransformerEntry addUUIDTransformer = new ChainedResourceTransformerEntry() {
            @Override
            public void transformResource(ChainedResourceTransformationContext context, PathAddress address, Resource resource)
                    throws OperationFailedException {
                ModelNode model = resource.getModel();
                if (!model.hasDefined(TransactionSubsystemRootResourceDefinition.PROCESS_ID_UUID.getName())) {
                    model.get(TransactionSubsystemRootResourceDefinition.PROCESS_ID_UUID.getName()).set(false);
                }
            }
        };

        // Transformations to the 1.1.1 Model:
        final ModelVersion version111 = ModelVersion.create(1, 1, 1);
        // 1) Remove JDBC store attributes if not used
        // 2) Fail if new attributes are set (and not removed by step 1)
        final TransformersSubRegistration transformers111 = subsystem.registerModelTransformers(version111,
                new ChainedResourceTransformer(UnneededJDBCStoreTransformer.INSTANCE, discardJdbcStoreTransformer, addUUIDTransformer));
        transformers111.registerOperationTransformer(ADD, new ChainedOperationTransformer(UnneededJDBCStoreTransformer.INSTANCE, discardJdbcStoreTransformer));
        transformers111.registerOperationTransformer(WRITE_ATTRIBUTE_OPERATION, discardJdbcStoreTransformer.getWriteAttributeTransformer());
        transformers111.registerOperationTransformer(UNDEFINE_ATTRIBUTE_OPERATION, discardJdbcStoreTransformer.getUndefineAttributeTransformer());
        // Check the resource and operations for expressions

        // Transformations to the 1.1.0 Model:
        final ModelVersion version110 = ModelVersion.create(1, 1, 0);
        // 1) Remove JDBC store attributes if not used
        // 2) Fail if new attributes are set (and not removed by step 1)
        // 3) Reject expressions
        final RejectExpressionValuesTransformer reject =
                new RejectExpressionValuesTransformer(TransactionSubsystemRootResourceDefinition.attributes);
        final TransformersSubRegistration registration = subsystem.registerModelTransformers(version110,
                new ChainedResourceTransformer(
                    UnneededJDBCStoreTransformer.INSTANCE,
                    discardJdbcStoreTransformer,
                    reject.getChainedTransformer(),
                    addUUIDTransformer));
        registration.registerOperationTransformer(ADD,
                new ChainedOperationTransformer(UnneededJDBCStoreTransformer.INSTANCE, discardJdbcStoreTransformer, reject.getChainedTransformer()));
        registration.registerOperationTransformer(WRITE_ATTRIBUTE_OPERATION,
                new ChainedOperationTransformer(discardJdbcStoreTransformer.getWriteAttributeTransformer(), reject.getWriteAttributeTransformer()));
        registration.registerOperationTransformer(UNDEFINE_ATTRIBUTE_OPERATION, discardJdbcStoreTransformer.getUndefineAttributeTransformer());
    }

    static class UnneededJDBCStoreTransformer extends DiscardAttributesTransformer {
        private static final UnneededJDBCStoreTransformer INSTANCE = new UnneededJDBCStoreTransformer();

        private UnneededJDBCStoreTransformer() {
            super(new AttributeValueDiscardApprover(TransactionSubsystemRootResourceDefinition.USE_JDBC_STORE.getName(), new ModelNode(false), true),
                    TransactionSubsystemRootResourceDefinition.attributes_1_2);
        }
    }
}

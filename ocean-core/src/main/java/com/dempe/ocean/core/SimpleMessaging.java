/*
 * Copyright (c) 2012-2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package com.dempe.ocean.core;


import com.dempe.ocean.common.OceanConfig;
import com.dempe.ocean.core.interception.InterceptHandler;
import com.dempe.ocean.core.spi.IMessagesStore;
import com.dempe.ocean.core.spi.ISessionsStore;
import com.dempe.ocean.core.spi.impl.subscriptions.SubscriptionsStore;
import com.dempe.ocean.core.spi.persistence.MapDBPersistentStore;
import com.dempe.ocean.core.spi.security.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * Singleton class that orchestrate the execution of the protocol.
 *
 * It's main responsibility is instantiate the ProtocolProcessor.
 *
 * @author andrea
 */
public class SimpleMessaging {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleMessaging.class);

    private SubscriptionsStore subscriptions;

    private MapDBPersistentStore m_mapStorage;

    private BrokerInterceptor m_interceptor;

    private static SimpleMessaging INSTANCE;
    
    private final ProtocolProcessorNew m_processor = new ProtocolProcessorNew();

    private SimpleMessaging() {
    }

    public static SimpleMessaging getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SimpleMessaging();
        }
        return INSTANCE;
    }

    /**
     * Initialize the processing part of the broker.
     * @param props the properties carrier where some props like port end host could be loaded.
     *              For the full list check of configurable properties check moquette.conf file.
     * @param embeddedObservers a list of callbacks to be notified of certain events inside the broker.
     *                          Could be empty list of null.
     * @param authenticator an implementation of the authenticator to be used, if null load that specified in config
     *                      and fallback on the default one (permit all).
     * @param authorizator an implementation of the authorizator to be used, if null load that specified in config
     *                      and fallback on the default one (permit all).
     * */
    public ProtocolProcessorNew init(OceanConfig config, List<? extends InterceptHandler> embeddedObservers,
                                  IAuthenticator authenticator, IAuthorizator authorizator) {
        subscriptions = new SubscriptionsStore();

        m_mapStorage = new MapDBPersistentStore(config);
        m_mapStorage.initStore();
        IMessagesStore messagesStore = m_mapStorage.messagesStore();
        ISessionsStore sessionsStore = m_mapStorage.sessionsStore(messagesStore);

        List<InterceptHandler> observers = new ArrayList<>(embeddedObservers);
        String interceptorClassName = config.getInterceptHandler();
        if (interceptorClassName != null && !interceptorClassName.isEmpty()) {
            try {
                InterceptHandler handler = Class.forName(interceptorClassName).asSubclass(InterceptHandler.class).newInstance();
                observers.add(handler);
            } catch (Throwable ex) {
                LOG.error("Can't load the intercept handler {}", ex);
            }
        }
        m_interceptor = new BrokerInterceptor(observers);

        subscriptions.init(sessionsStore);

        String configPath = System.getProperty("moquette.path", null);
        String authenticatorClassName = config.authenticatorClass();

        if (!authenticatorClassName.isEmpty()) {
            authenticator = (IAuthenticator)loadClass(authenticatorClassName, IAuthenticator.class);
            LOG.info("Loaded custom authenticator {}", authenticatorClassName);
        }

        if (authenticator == null) {
            String passwdPath = config.pwdFile();
            if (passwdPath.isEmpty()) {
                authenticator = new AcceptAllAuthenticator();
            } else {
                authenticator = new FileAuthenticator(configPath, passwdPath);
            }
        }

        String authorizatorClassName = config.authenticatorClass();
        if (!authorizatorClassName.isEmpty()) {
            authorizator = (IAuthorizator)loadClass(authorizatorClassName, IAuthorizator.class);
            LOG.info("Loaded custom authorizator {}", authorizatorClassName);
        }

        if (authorizator == null) {
            String aclFilePath = config.aclFile();
            if (aclFilePath != null && !aclFilePath.isEmpty()) {
                authorizator = new DenyAllAuthorizator();
                File aclFile = new File(configPath, aclFilePath);
                try {
                    authorizator = ACLFileParser.parse(aclFile);
                } catch (ParseException pex) {
                    LOG.error(String.format("Format error in parsing acl file %s", aclFile), pex);
                }
                LOG.info("Using acl file defined at path {}", aclFilePath);
            } else {
                authorizator = new PermitAllAuthorizator();
                LOG.info("Starting without ACL definition");
            }

        }

        boolean allowAnonymous = config.allowAnonymous();
        m_processor.init(subscriptions, messagesStore, sessionsStore, authenticator, allowAnonymous, authorizator, m_interceptor);
        return m_processor;
    }
    
    private Object loadClass(String className, Class<?> cls) {
        Object instance = null;
        try {
            Class<?> clazz = Class.forName(className);

            // check if method getInstance exists
            Method method = clazz.getMethod("getInstance", new Class[] {});
            try {
                instance = method.invoke(null, new Object[] {});
            } catch (IllegalArgumentException | InvocationTargetException | IllegalAccessException ex) {
                LOG.error(null, ex);
                throw new RuntimeException("Cannot call method "+ className +".getInstance", ex);
            }
        }
        catch (NoSuchMethodException nsmex) {
            try {
                instance = this.getClass().getClassLoader()
                        .loadClass(className)
                        .asSubclass(cls)
                        .newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException ex) {
                LOG.error(null, ex);
                throw new RuntimeException("Cannot load custom authenticator class " + className, ex);
            }
        } catch (ClassNotFoundException ex) {
            LOG.error(null, ex);
            throw new RuntimeException("Class " + className + " not found", ex);
        } catch (SecurityException ex) {
            LOG.error(null, ex);
            throw new RuntimeException("Cannot call method "+ className +".getInstance", ex);
        }

        return instance;
    }

    public void shutdown() {
        this.m_mapStorage.close();
    }
}
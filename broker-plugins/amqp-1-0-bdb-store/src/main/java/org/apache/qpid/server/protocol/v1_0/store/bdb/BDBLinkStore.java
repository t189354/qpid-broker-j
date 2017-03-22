/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.qpid.server.protocol.v1_0.store.bdb;


import static org.apache.qpid.server.store.berkeleydb.BDBUtils.DEFAULT_DATABASE_CONFIG;

import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.sleepycat.bind.tuple.LongBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.model.BrokerModel;
import org.apache.qpid.server.model.ModelVersion;
import org.apache.qpid.server.protocol.v1_0.LinkDefinition;
import org.apache.qpid.server.protocol.v1_0.LinkDefinitionImpl;
import org.apache.qpid.server.protocol.v1_0.LinkKey;
import org.apache.qpid.server.protocol.v1_0.store.LinkStore;
import org.apache.qpid.server.protocol.v1_0.store.LinkStoreUpdater;
import org.apache.qpid.server.protocol.v1_0.type.messaging.TerminusDurability;
import org.apache.qpid.server.store.StoreException;
import org.apache.qpid.server.store.berkeleydb.BDBEnvironmentContainer;
import org.apache.qpid.server.store.berkeleydb.EnvironmentFacade;

public class BDBLinkStore implements LinkStore
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BDBLinkStore.class);
    private static final String LINKS_DB_NAME = "AMQP_1_0_LINKS";
    private static final String LINKS_VERSION_DB_NAME = "AMQP_1_0_LINKS_VERSION";

    private final ReentrantReadWriteLock _useOrCloseRWLock = new ReentrantReadWriteLock(true);
    private final BDBEnvironmentContainer<?> _environmentContainer;
    private volatile StoreState _storeState = StoreState.CLOSED;

    BDBLinkStore(final BDBEnvironmentContainer<?> environmentContainer)
    {
        _environmentContainer = environmentContainer;
    }

    @Override
    public Collection<LinkDefinition> openAndLoad(final LinkStoreUpdater updater) throws StoreException
    {
        _useOrCloseRWLock.readLock().lock();
        try
        {
            Collection<LinkDefinition> links = getLinkDefinitions(updater);
            _storeState = StoreState.OPENED;
            return links;
        }
        catch (RuntimeException e)
        {
            throw getEnvironmentFacade().handleDatabaseException("Failed recovery of links", e);
        }
        finally
        {
            _useOrCloseRWLock.readLock().unlock();
        }
    }

    @Override
    public void saveLink(final LinkDefinition link)
    {
        _useOrCloseRWLock.readLock().lock();
        try
        {
            if (_storeState != StoreState.OPENED)
            {
                throw new StoreException("Store is not opened");
            }

            Database linksDatabase = getEnvironmentFacade().openDatabase(LINKS_DB_NAME, DEFAULT_DATABASE_CONFIG);
            save(linksDatabase, null, link);
        }
        catch (RuntimeException e)
        {
            throw getEnvironmentFacade().handleDatabaseException(String.format("Failed saving of link '%s'", new LinkKey(link)), e);
        }
        finally
        {
            _useOrCloseRWLock.readLock().unlock();
        }
    }

    @Override
    public void deleteLink(final LinkDefinition linkDefinition)
    {
        LinkKey linkKey = new LinkKey(linkDefinition);
        _useOrCloseRWLock.readLock().lock();
        try
        {
            if (_storeState != StoreState.OPENED)
            {
                throw new StoreException("Store is not opened");
            }

            Database linksDatabase = getEnvironmentFacade().openDatabase(LINKS_DB_NAME, DEFAULT_DATABASE_CONFIG);

            final DatabaseEntry databaseEntry = new DatabaseEntry();
            LinkKeyEntryBinding.getInstance().objectToEntry(linkKey, databaseEntry);
            OperationStatus status = linksDatabase.delete(null, databaseEntry);
            if (status != OperationStatus.SUCCESS)
            {
                LOGGER.debug(String.format("Unexpected status '%s' for deletion of '%s'", status, linkKey));
            }
        }
        catch (RuntimeException e)
        {
            throw getEnvironmentFacade().handleDatabaseException(String.format("Failed deletion of link '%s'", linkKey), e);
        }
        finally
        {
            _useOrCloseRWLock.readLock().unlock();
        }
    }


    @Override
    public void close()
    {
        _useOrCloseRWLock.writeLock().lock();
        try
        {
            _storeState = StoreState.CLOSED;
        }
        finally
        {
            _useOrCloseRWLock.writeLock().unlock();
        }
    }

    @Override
    public void delete()
    {
        _useOrCloseRWLock.writeLock().lock();
        try
        {
            close();
            getEnvironmentFacade().deleteDatabase(LINKS_DB_NAME);
            getEnvironmentFacade().deleteDatabase(LINKS_VERSION_DB_NAME);
        }
        catch (RuntimeException e)
        {
            getEnvironmentFacade().handleDatabaseException("Failed deletion of database", e);
            LOGGER.info("Failed to delete links database", e);
        }
        finally
        {
            _useOrCloseRWLock.writeLock().unlock();
        }
    }

    @Override
    public TerminusDurability getHighestSupportedTerminusDurability()
    {
        return TerminusDurability.CONFIGURATION;
    }

    private EnvironmentFacade getEnvironmentFacade()
    {
        return _environmentContainer.getEnvironmentFacade();
    }


    private Collection<LinkDefinition> getLinkDefinitions(final LinkStoreUpdater updater)
    {
        Database linksDatabase = getEnvironmentFacade().openDatabase(LINKS_DB_NAME, DEFAULT_DATABASE_CONFIG);
        Collection<LinkDefinition> links = new HashSet<>();

        ModelVersion currentVersion =
                new ModelVersion(BrokerModel.MODEL_MAJOR_VERSION, BrokerModel.MODEL_MINOR_VERSION);
        ModelVersion storedVersion = getStoredVersion();
        if (currentVersion.lessThan(storedVersion))
        {
            throw new StoreException(String.format("Cannot downgrade preference store from '%s' to '%s'", storedVersion, currentVersion));
        }

        try (Cursor cursor = linksDatabase.openCursor(null, null))
        {
            final DatabaseEntry key = new DatabaseEntry();
            final DatabaseEntry value = new DatabaseEntry();
            LinkKeyEntryBinding keyEntryBinding = LinkKeyEntryBinding.getInstance();
            LinkValueEntryBinding linkValueEntryBinding = LinkValueEntryBinding.getInstance();
            while (cursor.getNext(key, value, LockMode.READ_UNCOMMITTED) == OperationStatus.SUCCESS)
            {
                LinkKey linkKey = keyEntryBinding.entryToObject(key);
                LinkValue linkValue = linkValueEntryBinding.entryToObject(value);
                LinkDefinition link = new LinkDefinitionImpl(linkKey.getRemoteContainerId(), linkKey.getLinkName(), linkKey.getRole(), linkValue.getSource(), linkValue.getTarget());
                links.add(link);
            }
        }

        if (storedVersion.lessThan(currentVersion))
        {
            links = updater.update(storedVersion.toString(), links);
            final Transaction txn = getEnvironmentFacade().beginTransaction(null);
            try
            {
                linksDatabase = getEnvironmentFacade().clearDatabase(txn, LINKS_DB_NAME, DEFAULT_DATABASE_CONFIG);
                for (LinkDefinition link : links)
                {
                    save(linksDatabase, txn, link);
                }
                txn.commit();
                linksDatabase.close();
            }
            catch (Exception e)
            {
                txn.abort();
                throw e;
            }
        }

        return links;
    }

    private void save(Database database, Transaction txn, final LinkDefinition link)
    {
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();

        LinkKey linkKey = new LinkKey(link);
        LinkKeyEntryBinding.getInstance().objectToEntry(linkKey, key);
        LinkValueEntryBinding.getInstance().objectToEntry(new LinkValue(link), value);

        OperationStatus status = database.put(txn, key, value); // TODO: create transaction
        if (status != OperationStatus.SUCCESS)
        {
            throw new StoreException(String.format("Cannot save link %s", linkKey));
        }
    }

    private ModelVersion getStoredVersion() throws RuntimeException
    {
        try(Cursor cursor = getLinksVersionDb().openCursor(null, null))
        {
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();

            ModelVersion storedVersion = null;
            while (cursor.getNext(key, value, LockMode.READ_UNCOMMITTED) == OperationStatus.SUCCESS)
            {
                String versionString = StringBinding.entryToString(key);
                ModelVersion version = ModelVersion.fromString(versionString);
                if (storedVersion == null || storedVersion.lessThan(version))
                {
                    storedVersion = version;
                }
            }
            if (storedVersion == null)
            {
                throw new StoreException("No link version information.");
            }
            return storedVersion;
        }
        catch (RuntimeException e)
        {
            throw getEnvironmentFacade().handleDatabaseException("Cannot visit link version", e);
        }
    }

    private Database getLinksVersionDb()
    {
        Database linksVersionDb;
        try
        {
            DatabaseConfig config = new DatabaseConfig().setTransactional(true).setAllowCreate(false);
            linksVersionDb = getEnvironmentFacade().openDatabase(LINKS_VERSION_DB_NAME, config);
        }
        catch (DatabaseNotFoundException e)
        {
            linksVersionDb = getEnvironmentFacade().openDatabase(LINKS_VERSION_DB_NAME, DEFAULT_DATABASE_CONFIG);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();
            StringBinding.stringToEntry(BrokerModel.MODEL_VERSION, key);
            LongBinding.longToEntry(System.currentTimeMillis(), value);
            linksVersionDb.put(null, key, value);
        }

        return linksVersionDb;
    }

    enum StoreState
    {
        CLOSED, OPENED
    }
}

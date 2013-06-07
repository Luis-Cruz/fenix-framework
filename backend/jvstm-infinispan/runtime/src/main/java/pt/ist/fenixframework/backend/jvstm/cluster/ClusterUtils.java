/*
 * Fenix Framework, a framework to develop Java Enterprise Applications.
 *
 * Copyright (C) 2013 Fenix Framework Team and/or its affiliates and other contributors as indicated by the @author tags.
 *
 * This file is part of the Fenix Framework.  Read the file COPYRIGHT.TXT for more copyright and licensing information.
 */
package pt.ist.fenixframework.backend.jvstm.cluster;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pt.ist.fenixframework.backend.jvstm.infinispan.JvstmIspnConfig;
import pt.ist.fenixframework.core.TransactionError;

import com.hazelcast.core.AtomicNumber;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;

public class ClusterUtils {

    private static final Logger logger = LoggerFactory.getLogger(ClusterUtils.class);
    private static final String FF_GLOBAL_LOCK_NAME = "ff.hzl.global.lock";
    private static final String FF_COMMIT_TOPIC_NAME = "ff.hzl.commits";

    private static HazelcastInstance HAZELCAST_INSTANCE;

    // remote commits that have not been applied yet
    private static final ConcurrentLinkedQueue<RemoteCommit> REMOTE_COMMITS = new ConcurrentLinkedQueue<RemoteCommit>();

    private ClusterUtils() {
    }

    public static void initializeGroupCommunication(JvstmIspnConfig thisConfig) {
        com.hazelcast.config.Config hzlCfg = thisConfig.getHazelcastConfig();
        HAZELCAST_INSTANCE = Hazelcast.newHazelcastInstance(hzlCfg);

        // register listener for remote commits
        registerListenerForRemoteCommits();
    }

    private static void registerListenerForRemoteCommits() {
        ITopic<RemoteCommit> topic = getHazelcastInstance().getTopic(FF_COMMIT_TOPIC_NAME);

        topic.addMessageListener(new MessageListener<RemoteCommit>() {

            @Override
            public void onMessage(Message<RemoteCommit> message) {
                RemoteCommit remoteCommit = message.getMessageObject();
                logger.debug("Received remote commit message. serverId={}, tx={}", remoteCommit.getServerId(),
                        remoteCommit.getTxNumber());

                REMOTE_COMMITS.offer(remoteCommit);
            }
        });
    }

    // the instance should have been initialized in a single thread within the
    // FenixFramework static initializer's lock (via the invocation of the method
    // initializeGroupCommunication.
    private static HazelcastInstance getHazelcastInstance() {
        return HAZELCAST_INSTANCE;
    }

    public static void globalLock() {
        try {
            getHazelcastInstance().getLock(FF_GLOBAL_LOCK_NAME).lock();
        } catch (RuntimeException e) {
            logger.error("Failed to acquire global lock");
            throw new TransactionError(e);
        }
    }

    public static void globalUnlock() {
        try {
            getHazelcastInstance().getLock(FF_GLOBAL_LOCK_NAME).unlock();
        } catch (RuntimeException e) {
            logger.error("Failed to release global lock");
            throw new TransactionError(e);
        }
    }

    public static void notifyStartupComplete() {
        logger.info("Notify other nodes that startup completed");

        AtomicNumber initMarker = getHazelcastInstance().getAtomicNumber("initMarker");
        initMarker.incrementAndGet();
    }

    public static void waitForStartupFromFirstNode() {
        logger.info("Waiting for startup from first node");

        // check initMarker in AtomicNumber (value 1)
        AtomicNumber initMarker = getHazelcastInstance().getAtomicNumber("initMarker");

        while (initMarker.get() == 0) {
            logger.debug("Waiting for first node to startup...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        logger.debug("First node startup is complete.  We can proceed.");
    }

    public static int obtainNewServerId() {
        /* currently does not reuse the server Id value while any server is up.
        This can be changed if needed.  However, we currently depend on the first
        server getting the AtomicNumber 0 to know that it is the first member
        to appear.  By reusing numbers with the cluster alive, we either don't
        reuse 0 or change the algorithm  that detects the first member */

        AtomicNumber serverIdGenerator = getHazelcastInstance().getAtomicNumber("serverId");
        long longId = serverIdGenerator.getAndAdd(1L);

        logger.info("Got (long) serverId: {}", longId);

        int intId = (int) longId;
        if (intId != longId) {
            throw new Error("Failed to obtain a valid id");
        }

        return intId;
    }

    public static void sendCommitInfoToOthers(RemoteCommit remoteCommit) {
        if (logger.isDebugEnabled()) {
            StringBuilder str = new StringBuilder();
            str.append("serverId=").append(remoteCommit.getServerId());
            str.append(", txNumber=").append(remoteCommit.getTxNumber());
            str.append(", changes={");
            int size = remoteCommit.getOids().length;
            for (int i = 0; i < size; i++) {
                if (i != 0) {
                    str.append(", ");
                }
                long oid = remoteCommit.getOids()[i];
                String slotName = remoteCommit.getSlotNames()[i];
                str.append('(').append(Long.toHexString(oid)).append(':').append(slotName).append(')');
            }
            str.append("}");

            logger.debug("Send commit info to others: {}", str.toString());
        }

        ITopic<RemoteCommit> topic = getHazelcastInstance().getTopic(FF_COMMIT_TOPIC_NAME);
        topic.publish(remoteCommit);
    }

    public static ConcurrentLinkedQueue<RemoteCommit> getRemoteCommits() {
        return REMOTE_COMMITS;
    }

    public static void shutdown() {
        getHazelcastInstance().getLifecycleService().shutdown();
    }

}

package pt.ist.fenixframework.backend.infinispan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import javax.transaction.SystemException;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.manager.CacheContainer;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.manager.DefaultCacheManager;

import pt.ist.fenixframework.DomainObject;
import pt.ist.fenixframework.DomainRoot;
import pt.ist.fenixframework.TransactionManager;
import pt.ist.fenixframework.backend.BackEnd;
import pt.ist.fenixframework.core.AbstractDomainObject;
import pt.ist.fenixframework.core.DomainObjectAllocator;
import pt.ist.fenixframework.core.Externalization;
import pt.ist.fenixframework.core.IdentityMap;
import pt.ist.fenixframework.core.SharedIdentityMap;

public class InfinispanBackEnd implements BackEnd {
    private static final Logger logger = LoggerFactory.getLogger(InfinispanBackEnd.class);

    public static final String BACKEND_NAME = "ispn";
    private static final String DOMAIN_CACHE_NAME = "DomainCache";

    private static final InfinispanBackEnd instance = new InfinispanBackEnd();

    protected final InfinispanTransactionManager transactionManager;
    protected Cache<String, Object> domainCache;

    private InfinispanBackEnd() {
        this.transactionManager = new InfinispanTransactionManager();
    }

    public static InfinispanBackEnd getInstance() {
        return instance;
    }

    @Override
    public String getName() {
        return BACKEND_NAME;
    }

    @Override
    public DomainRoot getDomainRoot() {
        return fromOid(OID.ROOT_OBJECT_ID);
    }

    @Override
    public <T extends DomainObject> T getDomainObject(String externalId) {
        return fromOid(new OID(externalId));
    }

    @Override
    public TransactionManager getTransactionManager() {
        return this.transactionManager;
    }

    @Override
    public <T extends DomainObject> T fromOid(Object oid) {
        OID internalId = (OID)oid;
        if (logger.isTraceEnabled()) {
            logger.trace("fromOid(" + internalId.getFullId() + ")");
        }
        
        IdentityMap cache = getIdentityMap();
        AbstractDomainObject obj = cache.lookup(internalId);
        
	if (obj == null) {
            if (logger.isTraceEnabled()) {
                logger.trace("Object not found in IdentityMap: " + internalId.getFullId());
            }
	    obj = DomainObjectAllocator.allocateObject(internalId.getObjClass(), internalId);

	    // cache object and return the canonical object
	    obj = cache.cache(obj);
	}

	return (T) obj;
    }

    /**
     * Shuts down Infinispan's cache(s) and the(ir) manager(s)
     */
    @Override
    public void shutdown() {
        // not sure whether is still safe, after a stop() to getCacheManager(), so I get it first
        EmbeddedCacheManager manager = domainCache.getCacheManager();
        domainCache.stop();
        manager.stop();
    }

    protected void configInfinispan(InfinispanConfig config) {
        setupCache(config);
        setupTxManager(config);
    }

    
    private void setupCache(InfinispanConfig config) {
        long start = System.currentTimeMillis();
        CacheContainer cc = null;
        try {
            cc = new DefaultCacheManager(config.getIspnConfigFile());
        } catch (java.io.IOException ioe) {
            String message = "Error creating Infinispan cache manager with configuration file: "
                + config.getIspnConfigFile();
            logger.error(message, ioe);
            throw new Error(message, ioe);
        }
        domainCache = cc.getCache(DOMAIN_CACHE_NAME);
        if (logger.isDebugEnabled()) {
            DateFormat df = new SimpleDateFormat("HH:mm.ss");
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            logger.debug("Infinispan initialization took " +
                         df.format(new Date(System.currentTimeMillis() - start)));
        }
    }

    private void setupTxManager(InfinispanConfig config) {
        transactionManager.setDelegateTxManager(domainCache.getAdvancedCache().getTransactionManager());
    }

    protected IdentityMap getIdentityMap() {
        return SharedIdentityMap.getCache();
    }

    /**
     * Store in Infinispan.  This method supports null values.  This method is used by the code
     * generated in the Domain Objects.
     */
    public final void cachePut(String key, Object value) {
        domainCache.put(key, (value != null) ? value : Externalization.NULL_OBJECT);
    }

    /**
     * Reads from Infinispan a value with a given key.  This method is used by the code generated in
     * the Domain Objects.
     */
    public final <T> T cacheGet(String key) {
        Object obj = domainCache.get(key);
        return (T)(obj instanceof Externalization.NullClass ? null : obj);
    }
    
    /**
     * Reads from Infinispan a value with a given key such that the transactional context does not keep 
     * track of this key. This means that this read can never cause the trasactin to abort.
     * This method is used by the code generated in the Domain Objects.
     */
    public final <T> T cacheGetUnsafe(String key) {
	Object obj = domainCache.getAdvancedCache().withFlags(Flag.READ_WITHOUT_REGISTERING).get(key);
        return (T)(obj instanceof Externalization.NullClass ? null : obj);
    }
    
    public final void registerGet(String key) {
	AdvancedCache advCache = domainCache.getAdvancedCache();
	try {
	    advCache.getTxTable().getLocalTransaction(advCache.getTransactionManager().getTransaction()).addReadKey(key);
	} catch (SystemException e) {
	    logger.error("Exception while getting the current JPA Transaction to register a key read", e);
	}
    }
}

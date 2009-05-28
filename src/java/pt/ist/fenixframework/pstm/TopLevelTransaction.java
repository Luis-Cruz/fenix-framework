package pt.ist.fenixframework.pstm;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import jvstm.ActiveTransactionsRecord;
import jvstm.CommitException;
import jvstm.ResumeException;
import jvstm.VBoxBody;
import jvstm.cps.ConsistencyCheckTransaction;
import jvstm.cps.ConsistentTopLevelTransaction;
import jvstm.cps.DependenceRecord;
import jvstm.util.Cons;
import pt.ist.fenixframework.DomainObject;
import pt.ist.fenixframework.TxIntrospector;
import pt.ist.fenixframework.TxIntrospector.Entry;
import pt.ist.fenixframework.TxIntrospector.RelationChangelog;

import pt.ist.fenixframework.pstm.DBChanges.AttrChangeLog;
import pt.ist.fenixframework.pstm.DBChanges.RelationTupleInfo;

import org.apache.ojb.broker.PersistenceBroker;
import org.apache.ojb.broker.PersistenceBrokerFactory;
import org.apache.ojb.broker.accesslayer.LookupException;

import org.apache.log4j.Logger;

public class TopLevelTransaction extends ConsistentTopLevelTransaction 
    implements FenixTransaction, TxIntrospector {

    private static final Logger logger = Logger.getLogger(TopLevelTransaction.class);
    private static int NUM_READS_THRESHOLD = 10000000;
    private static int NUM_WRITES_THRESHOLD = 100000;

    private static final Object COMMIT_LISTENERS_LOCK = new Object();
    private static volatile Cons<CommitListener> COMMIT_LISTENERS = Cons.empty();

    public static void addCommitListener(CommitListener listener) {
        synchronized (COMMIT_LISTENERS_LOCK) {
            COMMIT_LISTENERS = COMMIT_LISTENERS.cons(listener);
        }
    }

    public static void removeCommitListener(CommitListener listener) {
        synchronized (COMMIT_LISTENERS_LOCK) {
            COMMIT_LISTENERS = COMMIT_LISTENERS.removeFirst(listener);
        }
    }

    private static void notifyBeforeCommit(TopLevelTransaction tx) {
        for (CommitListener cl : COMMIT_LISTENERS) {
            cl.beforeCommit(tx);
        }
    }

    private static void notifyAfterCommit(TopLevelTransaction tx) {
        for (CommitListener cl : COMMIT_LISTENERS) {
            cl.afterCommit(tx);
        }
    }

    // Each TopLevelTx has its DBChanges
    // If this slot is changed to null, it is an indication that the
    // transaction does not allow more changes
    private DBChanges dbChanges = null;

    private ServiceInfo serviceInfo = ServiceInfo.getCurrentServiceInfo();

    private PersistenceBroker broker;

    // for statistics
    protected int numBoxReads = 0;
    protected int numBoxWrites = 0;


    TopLevelTransaction(ActiveTransactionsRecord record) {
        super(record);

        initDbConnection(false);
        initDbChanges();
    }

    protected void initDbConnection(boolean resuming) {
        // first, get a new broker that will give access to the DB connection
        this.broker = PersistenceBrokerFactory.defaultPersistenceBroker();

	// open a connection to the database and set this tx number to the number that
	// corresponds to that connection number.  The connection number should always be 
	// greater than the current number, because the current number is obtained from
	// Transaction.getCommitted, which is set only after the commit to the database
        ActiveTransactionsRecord newRecord = updateFromTxLogsOnDatabase(this.activeTxRecord);
        if (newRecord != this.activeTxRecord) {
            // if a new record is returned, that means that this transaction will belong 
            // to that new record, so we must take it off from its current record and set
            // it properly

            // but, if we are resuming, we must ensure first that the
            // transaction is still valid for the new transaction
            // record
            if (resuming) {
                checkValidity(newRecord);
            }

            newRecord.incrementRunning();
            this.activeTxRecord.decrementRunning();
            this.activeTxRecord = newRecord;
            setNumber(newRecord.transactionNumber);
        }
    }

    protected void checkValidity(ActiveTransactionsRecord record) {
        // we must see whether any of the boxes read by this
        // transaction was changed by some transaction upto the one
        // corresponding to the new record (newer ones don't matter)

        int newTxNumber = record.transactionNumber;

        for (Map.Entry<jvstm.VBox,VBoxBody> entry : this.bodiesRead.entrySet()) {
            if (entry.getKey().body.getBody(newTxNumber) != entry.getValue()) {
                throw new ResumeException("Transaction is no longer valid for resuming");
            }
        }
    }

    @Override
    protected void suspendTx() {
        // close the broker to release the db connection on suspension
        if (broker != null) {
            broker.close();
            broker = null;
        }

        super.suspendTx();
    }

    @Override
    protected void resumeTx() {
        super.resumeTx();
        initDbConnection(true);
    }

    protected void initDbChanges() {
        this.dbChanges = new DBChanges();
    }

    public PersistenceBroker getOJBBroker() {
        return broker;
    }

    public DomainObject readDomainObject(String classname, int oid) {
        return TransactionChangeLogs.readDomainObject(broker, classname, oid);
    }

    public void setReadOnly() {
        // a null dbChanges indicates a read-only tx
        this.dbChanges = null;
    }

    @Override
    public Transaction makeNestedTransaction() {
        throw new Error("Nested transactions not supported yet...");
    }

    private ActiveTransactionsRecord updateFromTxLogsOnDatabase(ActiveTransactionsRecord record) {
        try {
            return TransactionChangeLogs.updateFromTxLogsOnDatabase(getOJBBroker(), record);
        } catch (Exception sqle) {
//            sqle.printStackTrace();
            throw new Error("Error while updating from FF$TX_CHANGE_LOGS: Cannot proceed: " + sqle.getMessage(), sqle);
        }
    }

    public void logServiceInfo() {
        System.out.println("Transaction " + this + " created for service: " + serviceInfo.serviceName
                + ", username = " + serviceInfo.username
        // + ", args = " + serviceInfo.getArgumentsAsString()
                );
        //         System.out.println("Currently executing:");
        //         StackTraceElement[] txStack = getThread().getStackTrace();
        //         for (int i = 0; (i < txStack.length) && (i < 5); i++) {
        //             System.out.println("-----> " + txStack[i]);
        //         }
    }

    @Override
    protected void finish() {
        super.finish();
        if (broker != null) {
            broker.close();
            broker = null;
        }
        dbChanges = null;
    }

    @Override
    protected void doCommit() {
        if (isWriteTransaction()) {
            Transaction.STATISTICS.incWrites(this);
        } else {
            Transaction.STATISTICS.incReads(this);
        }

        if ((numBoxReads > NUM_READS_THRESHOLD) || (numBoxWrites > NUM_WRITES_THRESHOLD)) {
            logger.warn(String.format("WARN: Very-large transaction (reads = %d, writes = %d, uri = %s)",
                                      numBoxReads,
                                      numBoxWrites,
                                      RequestInfo.getRequestURI()));
        }

        // reset statistics counters
        numBoxReads = 0;
        numBoxWrites = 0;

        notifyBeforeCommit(this);
        super.doCommit();
        notifyAfterCommit(this);
    }

    @Override
    protected boolean validateCommit() {
        boolean result = super.validateCommit();

        if (! result) {
            Transaction.STATISTICS.incConflicts();
        }

        return result;
    }

    public ReadSet getReadSet() {
        return new ReadSet(bodiesRead);
    }

    @Override
    public <T> void setBoxValue(jvstm.VBox<T> vbox, T value) {
        if (dbChanges == null) {
            throw new IllegalWriteException();
        } else {
            numBoxWrites++;
            super.setBoxValue(vbox, value);
        }
    }

    @Override
    public <T> void setPerTxValue(jvstm.PerTxBox<T> box, T value) {
        if (dbChanges == null) {
            throw new IllegalWriteException();
        } else {
            super.setPerTxValue(box, value);
        }
    }

    public <T> T getBoxValue(VBox<T> vbox, Object obj, String attr) {
        numBoxReads++;
        T value = getLocalValue(vbox);

        if (value == null) {
            // no local value for the box

            VBoxBody<T> body = vbox.body.getBody(number);
            if (body.value == VBox.NOT_LOADED_VALUE) {
                vbox.reload(obj, attr);
                // after the reload, the same body should have a new value
                // if not, then something gone wrong and its better to abort
                if (body.value == VBox.NOT_LOADED_VALUE) {
                    System.out.println("Couldn't load the attribute " + attr + " for class "
                                       + obj.getClass());
                    throw new VersionNotAvailableException();
                }
            }

            if (bodiesRead == EMPTY_MAP) {
                bodiesRead = new HashMap<jvstm.VBox,VBoxBody>();
            }
            bodiesRead.put(vbox, body);
            value = body.value;
        }

        return (value == NULL_VALUE) ? null : value;
    }

    public boolean isBoxValueLoaded(VBox vbox) {
        Object localValue = getLocalValue(vbox);

        if (localValue == VBox.NOT_LOADED_VALUE)  {
            return false;
        }

        if (localValue != null)  {
            return true;
        }

        VBoxBody body = vbox.body.getBody(number);
        return (body.value != VBox.NOT_LOADED_VALUE);
    }

    public DBChanges getDBChanges() {
        if (dbChanges == null) {
            // if it is null, it means that the transaction is a read-only
            // transaction
            throw new IllegalWriteException();
        } else {
            return dbChanges;
        }
    }

    protected boolean isWriteTransaction() {
        return ((dbChanges != null) && dbChanges.needsWrite()) || super.isWriteTransaction();
    }

    @Override
    protected Cons<VBoxBody> performValidCommit() {
        // in memory everything is ok, but we need to check against the db
        PersistenceBroker pb = getOJBBroker();

        int currentPriority = Thread.currentThread().getPriority();
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        long time1 = System.currentTimeMillis();
        long time2 = 0;
        long time3 = 0;
        long time4 = 0;
        long time5 = 0;
        long time6 = 0;
        long time7 = 0;
        long time8 = 0;
        long time9 = 0;

        try {
            if (!pb.isInTransaction()) {
                pb.beginTransaction();
            }
            time2 = System.currentTimeMillis();
            try {
                time3 = System.currentTimeMillis();
                // the updateFromTxLogs is made with the txNumber minus 1 to ensure that the select
                // for update will return at least a record, and, therefore, lock the record
                // otherwise, the mysql server may allow the select for update to continue
                // concurrently with other executing commits in other servers
                ActiveTransactionsRecord myRecord = this.activeTxRecord;
                if (TransactionChangeLogs.updateFromTxLogsOnDatabase(pb, myRecord, true) != myRecord) {
                    // the cache may have been updated, so perform the
                    // tx-validation again
                    time4 = System.currentTimeMillis();
                    if (!validateCommit()) {
                        System.out.println("Invalid commit. Restarting.");
                        throw new jvstm.CommitException();
                    }
                    time5 = System.currentTimeMillis();
                }
            } catch (SQLException sqlex) {
                System.out.println("SqlException: " + sqlex.getMessage());
                throw new CommitException();
            } catch (LookupException le) {
                throw new Error("Error while obtaining database connection", le);
            }

            time6 = System.currentTimeMillis();
            Cons<VBoxBody> newBodies = super.performValidCommit();
            time7 = System.currentTimeMillis();
            // ensure that changes are visible to other TXs before releasing
            // lock
            try {
                pb.commitTransaction();
                time8 = System.currentTimeMillis();
            } catch (Throwable t) {
                t.printStackTrace();
                System.out.println("Error while commiting exception. Terminating server.");
                System.err.flush();
                System.out.flush();
                System.exit(-1);
            }
            pb = null;
            return newBodies;
        } finally {
            if (pb != null) {
                pb.abortTransaction();
                time9 = System.currentTimeMillis();
            }

            if ((time8 - time1) > 500) {
                System.out.println(
                                   "performValidCommit: ,1: " + (time1 == 0 || time2 == 0 ? "" : (time2 - time1))
                                   + "   ,2: " + (time2 == 0 || time3 == 0 ? "" : (time3 - time2))
                                   + "   ,3: " + (time3 == 0 || time4 == 0 ? "" : (time4 - time3))
                                   + "   ,4: " + (time4 == 0 || time5 == 0 ? "" : (time5 - time4))
                                   + "   ,5: " + (time5 == 0 || time6 == 0 ? "" : (time6 - time5))
                                   + "   ,6: " + (time6 == 0 || time7 == 0 ? "" : (time7 - time6))
                                   + "   ,7: " + (time7 == 0 || time8 == 0 ? "" : (time8 - time7))
                                   + "   ,8: " + (time8 == 0 || time9 == 0 ? "" : (time9 - time8))
                                   );
            }
        }
        } finally {
            Thread.currentThread().setPriority(currentPriority);
        }
    }

    @Override
    protected Cons<VBoxBody> doCommit(int newTxNumber) {
        persistTransaction(newTxNumber);
        return super.doCommit(newTxNumber);
    }

    protected void persistTransaction(int newTxNumber) {
        long time1 = System.currentTimeMillis();
        long time2 = 0;
        long time3 = 0;
        long time4 = 0;
        long time5 = 0;
        long time6 = 0;

        try {
            time2 = System.currentTimeMillis();
            dbChanges.makePersistent(getOJBBroker(), newTxNumber);
            time3 = System.currentTimeMillis();
        } catch (SQLException sqle) {
            throw new Error("Error while accessing database", sqle);
        } catch (LookupException le) {
            throw new Error("Error while obtaining database connection", le);
        }
        time4 = System.currentTimeMillis();
        dbChanges.cache();
        time5 = System.currentTimeMillis();

        if ((time5 - time1) > 500) {
            System.out.println(
                               "doCommit: ,1: " + (time1 == 0 || time2 == 0 ? "" : (time2 - time1))
                               + "   ,2: " + (time2 == 0 || time3 == 0 ? "" : (time3 - time2))
                               + "   ,3: " + (time3 == 0 || time4 == 0 ? "" : (time4 - time3))
                               + "   ,4: " + (time4 == 0 || time5 == 0 ? "" : (time5 - time4))
                               );
        }
    }


    // consistency-predicates-system methods

    @Override
    protected void checkConsistencyPredicates() {
        // check all the consistency predicates for the objects modified in this transaction
        for (Object obj : getDBChanges().getModifiedObjects()) {
            checkConsistencyPredicates(obj);
	}

        super.checkConsistencyPredicates();
    }

    @Override
    protected void checkConsistencyPredicates(Object obj) {
        if (getDBChanges().isDeleted(obj)) {
            // don't check deleted objects
            return;
        } else {
            super.checkConsistencyPredicates(obj);
        }
    }

    @Override
    protected ConsistencyCheckTransaction makeConsistencyCheckTransaction(Object obj) {
        return new FenixConsistencyCheckTransaction(this, obj);
    }

    @Override
    protected Iterator<DependenceRecord> getDependenceRecordsToRecheck() {
        // for now, just return an empty iterator
        return Util.emptyIterator();
    }

    // implement the TxIntrospector interface

    public Set<DomainObject> getNewObjects() {
        return getDBChanges().getNewObjects();
    }

    public Set<DomainObject> getModifiedObjects() {
        return getDBChanges().getModifiedObjects();
    }

    public Set<Entry> getReadSetLog() {
        throw new Error("getReadSetLog not implemented yet");
//         Set<Entry> entries = new HashSet<Entry>(bodiesRead.size());

//         for (Map.Entry<VBox,VBoxBody> entry : bodiesRead.entrySet()) {
//             entries.add(new Entry());
//         }

//         return entries;
    }

    public Set<Entry> getWriteSetLog() {
        Set<AttrChangeLog> attrChangeLogs = getDBChanges().getAttrChangeLogs();
        Set<Entry> entries = new HashSet<Entry>(attrChangeLogs.size());

        for (AttrChangeLog log : attrChangeLogs) {
            AbstractDomainObject obj = (AbstractDomainObject)log.obj;
            entries.add(new Entry(obj, log.attr, getLocalValue(obj.getBoxFor(log.attr))));
        }

        return entries;
    }
    
    public Set<RelationChangelog> getRelationsChangelog() {
        Set<RelationTupleInfo> tupleInfos = getDBChanges().getMtoNRelationTupleInfos();
        Set<RelationChangelog> entries = new HashSet<RelationChangelog>(tupleInfos.size());

        for (RelationTupleInfo tuple : tupleInfos) {
            entries.add(new RelationChangelog(tuple.relation, tuple.obj1, tuple.obj2, tuple.remove));
        }

        return entries;
    }
}
